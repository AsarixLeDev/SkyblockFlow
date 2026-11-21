package dev.asarix.skyblockflow;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import dev.asarix.skyblockflow.ui.SharedEditsScreen;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Network and decoding work stays on one bounded worker; Minecraft state stays on its own thread.
 */
public final class SharedEdits implements AutoCloseable {
  public static class Settings {
    public String relayUrl = "https://flow-edit-relay-production.up.railway.app";
    public boolean enabled = false, blockAll = false;
    public boolean keepDownloadedSongs = false;
    public String acceptedRelay = "";
    public float volumeLimit = 1;
    public EditScene avatar;
    public boolean shareTerrain = true;
    public int terrainRadius = 32;
    public Map<String, String> blocked = new LinkedHashMap<>();
  }

  public static class Invitation {
    public String id,
        senderUuid,
        senderName,
        receiverUuid,
        receiverName,
        world,
        entityUuid,
        entityName,
        bundleId,
        audioSha256,
        state;
    public long startAtMs;
    public EditScene scene;
    public RemoteScene remote;
  }

  private final SkyblockFlowClient app;
  public final RemoteScenes remoteScenes;
  private RemoteScenes.Prepared preparedRemote;
  private final Minecraft mc = Minecraft.getInstance();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "Flow shared edits");
            t.setDaemon(true);
            return t;
          });
  public Settings settings = new Settings();
  public String status = "Sharing is off";
  public volatile EditTransfer transfer;

  public Invitation invitation;
  private volatile EditRelayClient relay;
  private boolean busy, authenticated, prepared, playing, openRequested;
  private long nextPoll, cooldownUntil, nextAttempt;
  public long cooldownSeconds() { return Math.max(0, (cooldownUntil - System.currentTimeMillis() + 999) / 1000); }
  private void readCooldown(JsonObject response) {
    if (response.has("cooldownSeconds")) cooldownUntil = System.currentTimeMillis() + Math.max(0, Math.min(300, response.get("cooldownSeconds").getAsLong())) * 1000;
  }
  private String pendingCancel = "", pendingFinish = "";
  private boolean preferencesDirty;
  private volatile int generation, stopRevision;
  private boolean resetPending;
  private String world = "", announced = "", ownUuid = "";
  private final Map<String, String> consented = new HashMap<>();
  private final Set<String> endedInvitations = new LinkedHashSet<>();

  private static String consentKey(Invitation i) {
    return i.senderUuid
        + "|"
        + i.receiverUuid
        + "|"
        + i.entityUuid
        + "|"
        + i.world
        + "|"
        + i.bundleId
        + "|"
        + ConfigStore.GSON.toJson(i.scene)
        + "|"
        + ConfigStore.GSON.toJson(i.remote);
  }

  private SharedEditBundle.Manifest manifest;
  private SongAnalysis song;
  private com.mojang.authlib.GameProfile sceneProfile;
  private final PlayerProfileLookup profileLookup = new PlayerProfileLookup();
  private boolean localStarting;
  private int localRevision;

  private boolean available(Invitation i) {
    if (i == null) return false;
    if (i.remote != null) return mc.level != null && mc.player != null;
    return i.scene == null ? entity(i.entityUuid) != null : EditAvatar.available(i.scene);
  }

  private com.mojang.authlib.GameProfile resolve(EditScene scene, boolean byName) throws Exception {
    var resolver = mc.services().profileResolver();
    com.mojang.authlib.GameProfile profile = null;
    try {
      profile =
          (byName
                  ? resolver.fetchByName(scene.playerName())
                  : resolver.fetchById(UUID.fromString(scene.playerId())))
              .orElse(null);
    } catch (RuntimeException unavailable) {
      // Vanilla can cache a failed lookup, or throw when a profile endpoint is unavailable.
    }
    if (profile == null) {
      // Resolve names through the other official endpoint, then fetch signed skin data by UUID.
      var identity =
          byName
              ? profileLookup.find(scene.playerName())
              : new PlayerProfileLookup.Identity(
                  UUID.fromString(scene.playerId()), scene.playerName());
      var result = mc.services().sessionService().fetchProfile(identity.id(), true);
      if (result == null || !result.profile().id().equals(identity.id()))
        throw new java.io.IOException(
            "Minecraft skin service is unavailable; try this avatar again shortly");
      profile = result.profile();
    }
    // Load signed official skins before the synchronized start; no sender-supplied texture URLs.
    mc.getSkinManager().get(profile).get(30, TimeUnit.SECONDS);
    return profile;
  }

  public void watch(boolean aimed, CelebrationSettings settings, EditScene scene) {
    if (localStarting || invitation != null || app.celebrations.active()) {
      message("Finish the current edit or invitation first.");
      return;
    }
    Entity target =
        aimed ? (mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null) : mc.player;
    if (mc.level == null || mc.player == null || scene == null && target == null) {
      message("Join a world and choose a loaded subject.");
      return;
    }
    if (scene != null && !EditAvatar.available(scene)) {
      message("Avatar position must be loaded, in this dimension, within 256 blocks.");
      return;
    }
    var copy = ConfigStore.copy(settings, CelebrationSettings.class);
    var level = mc.level;
    var sourceScreen = mc.gui.screen();
    int revision = ++localRevision;
    localStarting = true;
    status = "Preparing local edit...";
    worker.execute(
        () -> {
          try {
            var profile = scene == null ? null : resolve(scene, true);
            var resolved =
                scene == null ? null : scene.profile(profile.id().toString(), profile.name());
            var analysis = app.sounds.analyzeSong(copy, copy.edit).get();
            mc.execute(
                () -> {
                  if (revision != localRevision) return;
                  localStarting = false;
                  if (mc.level != level
                      || mc.player == null
                      || app.celebrations.active()
                      || invitation != null
                      || (target != null && target.isRemoved())) return;
                  if (mc.gui.screen() != null && mc.gui.screen() != sourceScreen) {
                    message("Local edit cancelled because another menu was opened.");
                    return;
                  }
                  try {
                    app.celebrations.showScene(
                        copy,
                        resolved == null ? target.getName().getString() : resolved.playerName(),
                        target,
                        analysis,
                        new Random().nextLong(),
                        System.nanoTime() + 250000000,
                        true,
                        resolved,
                        profile);
                    mc.gui.setScreen(null);
                    status = "Local edit · Esc or /stopedit to stop";
                  } catch (RuntimeException e) {
                    app.celebrations.stop();
                    message(reason(e));
                  }
                });
          } catch (Exception e) {
            mc.execute(
                () -> {
                  if (revision != localRevision) return;
                  localStarting = false;
                  message("Local edit: " + reason(e));
                });
          }
        });
  }

  private Path cachedAudio, cachedImage;
  private final SharedAudioCache audioCache;

  public SharedEdits(SkyblockFlowClient app) {
    this.app = app;
    audioCache = new SharedAudioCache(app.store.sounds());
    remoteScenes = new RemoteScenes(app);
    try {
      var f = app.store.directory().resolve("sharing.json");
      if (Files.exists(f)) {
        var read = ConfigStore.GSON.fromJson(Files.readString(f), Settings.class);
        if (read != null && read.blocked != null) {
          EditRelayClient.address(read.relayUrl);
          settings = read;
        }
      }
    } catch (Exception e) {
      app.error("Shared edit preferences could not be read; sharing is off.");
    }
    if (!settings.relayUrl.equals(settings.acceptedRelay)) settings.enabled = false;
    if (!settings.keepDownloadedSongs) clearTemporaryMedia(app.store.sounds().resolve(".shared"));
    else {
      var root = app.store.sounds().resolve(".shared");
      if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) try (var files = Files.list(root)) {
        for (var path : files.toList()) if (!path.getFileName().toString().equals("cache")) {
          if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) clearTemporaryMedia(path);
          else Files.deleteIfExists(path);
        }
      } catch (Exception e) { app.error("Could not clear temporary edit files"); }
    }
    clearTemporaryMedia(app.store.directory().resolve("celebrations/.shared"));
    clearTemporaryMedia(app.store.directory().resolve("sharing-temp"));
    register();
  }

  private Path temporaryArchive(String prefix) throws java.io.IOException {
    Path directory = app.store.directory().resolve("sharing-temp");
    Files.createDirectories(directory);
    return Files.createTempFile(directory, prefix, ".zip");
  }

  private void clearTemporaryMedia(Path directory) {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(directory)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    } catch (Exception e) { app.error("Could not clear temporary edit files"); }
  }

  public void configure(Settings value) {
    if (value.enabled && !value.relayUrl.equals(value.acceptedRelay))
      throw new IllegalArgumentException("Review the sharing disclosure for this relay before enabling it");
    EditRelayClient.address(value.relayUrl);
    if (!Float.isFinite(value.volumeLimit) || value.volumeLimit < 0 || value.volumeLimit > 1)
      throw new IllegalArgumentException("Incoming volume limit must be 0–1");
    if (value.terrainRadius < 8 || value.terrainRadius > 48)
      throw new IllegalArgumentException("Terrain radius must be 8–48 blocks");
    boolean reconnect =
        settings.enabled != value.enabled
            || !settings.relayUrl.equals(value.relayUrl)
            || settings.blockAll != value.blockAll;
    synchronized (audioCache) {
    if (settings.keepDownloadedSongs != value.keepDownloadedSongs) {
      if (busy || invitation != null || playing)
        throw new IllegalArgumentException("Finish or stop the current share before changing song storage");
      if (!value.keepDownloadedSongs) clearSavedSongs();
    }
    settings = value;
    }
    app.store.config.feature(Feature.SHARED_EDITS).enabled = value.enabled;
    save();
    if (reconnect) {
      disconnect();
      nextPoll = 0;
    }
  }

  public void clearSavedSongs() {
    if (busy || invitation != null || playing)
      throw new IllegalArgumentException("Finish or stop the current share before clearing saved songs");
    try { audioCache.clear(); }
    catch (Exception e) { throw new IllegalStateException("Could not clear saved songs", e); }
  }

  public void save() {
    try {
      Files.createDirectories(app.store.directory());
      var path = app.store.directory().resolve("sharing.json");
      var tmp = path.resolveSibling("sharing.json.tmp");
      Files.writeString(tmp, ConfigStore.GSON.toJson(settings));
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      app.error("Could not save shared edit preferences");
    }
  }

  private void message(String text) {
    status = text;
    app.event(text);
    if (mc.player != null)
      mc.gui.hud.getChat().addClientSystemMessage(dev.asarix.skyblockflow.ui.DisplayText.literal("[Edits] " + text));
  }

  private String currentWorld() {
    if (mc.level == null || mc.player == null || mc.getCurrentServer() == null) return "";
    String context = mc.getCurrentServer().ip.strip().toLowerCase(Locale.ROOT) + "|" + mc.level.dimension().identifier();
    // The relay needs to detect transfers, not know the player's server address.
    try { return SharedEditBundle.hash(context.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    catch (Exception unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
  }

  public Entity entity(String uuid) {
    if (mc.level == null) return null;
    for (Entity e : mc.level.entitiesForRendering())
      if (e.getUUID().toString().equals(uuid)) return e;
    return mc.player != null && mc.player.getUUID().toString().equals(uuid) ? mc.player : null;
  }

  private interface Discardable extends Runnable { void discard(); }

  private interface Operation {
    Runnable run(EditRelayClient client) throws Exception;
  }

  private void submit(Operation operation) {
    submit(operation, null);
  }

  private void submit(Operation operation, java.util.function.Consumer<Exception> failure) {
    if (busy) {
      status = "Please wait for the current transfer.";
      return;
    }
    busy = true;
    int version = generation;
    boolean resetting = resetPending;
    EditRelayClient connection = relay;
    worker.execute(
        () -> {
          try {
            Runnable finish = operation.run(connection);
            mc.execute(
                () -> {
                  if (version == generation) {
                    busy = false;
                    try {
                      finish.run();
                    } catch (RuntimeException error) {
                      if (failure != null) failure.accept(error);
                      else {
                        cancel();
                        message("Edit cancelled: " + reason(error));
                      }
                    }
                  } else if (finish instanceof Discardable stale) stale.discard();
                });
          } catch (Exception e) {
            mc.execute(
                () -> {
                  if (version == generation) {
                    busy = false;
                    if (e instanceof EditRelayClient.Failure f && f.status == 401)
                      authenticated = false;
                    if (resetPending && !resetting) {
                      nextPoll = 0;
                      return;
                    }
                    transfer = null;
                    if (failure != null) {
                      failure.accept(e);
                      return;
                    }
                    status = "Sharing: " + reason(e);
                    app.error(status);
                    nextPoll = System.currentTimeMillis() + 10000;
                  }
                });
          }
        });
  }

  private static String reason(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  public void tick() {
    remoteScenes.tick();
    if (openRequested) {
      openRequested = false;
      mc.gui.setScreen(new SharedEditsScreen(app, mc.gui.screen()));
    }
    String current = currentWorld();
    if (!settings.enabled || !settings.relayUrl.equals(settings.acceptedRelay)
        || !app.store.config.enabled(Feature.SHARED_EDITS) || current.isEmpty()) {
      if (relay != null) disconnect();
      return;
    }
    if (!current.equals(world)) {
      disconnect();
      world = current;
      nextPoll = 0;
    }
    if (playing && (!available(invitation) || mc.player.isDeadOrDying())) {
      cancel();
      return;
    }
    if (playing && !app.celebrations.sharedActive()) {
      playing = false;
      prepared = false;
      pendingFinish = invitation.id;
      cleanup();
    }
    long now = System.currentTimeMillis();
    if (busy || now < nextPoll) return;
    if (authenticated && resetPending) {
      int revision = stopRevision;
      // No invitation id is needed: clear orphan uploads and pending server state too.
      submit(
          c -> {
            c.post("/v1/stop", Map.of());
            return () -> {
              if (revision != stopRevision) return;
              resetPending = false;
              preferencesDirty = true;
              pendingCancel = pendingFinish = "";
              invitation = null;
              consented.clear();
              stopLocal();
              message("Shared edits stopped · ready to send again");
            };
          });
      nextPoll = now + 1000;
      return;
    }
    if (authenticated && (!pendingCancel.isEmpty() || !pendingFinish.isEmpty())) {
      finishInvitation();
      return;
    }
    if (authenticated && preferencesDirty) {
      var p = preferences();
      preferencesDirty = false;
      submit(
          c -> {
            c.post("/v1/preferences", p);
            return () -> {};
          });
      return;
    }
    nextPoll = now + 1000;
    if (!authenticated) {
      status = "Connecting to relay...";
      var user = mc.getUser();
      var prefs = preferences();
      if (relay != null) relay.close();
      relay = new EditRelayClient(settings.relayUrl);
      submit(
          c -> {
            byte[] random = new byte[20]; new java.security.SecureRandom().nextBytes(random);
            String proof = HexFormat.of().formatHex(random);
            var challenge = c.post("/v2/challenge", Map.of("name", user.getName(), "serverId", proof));
            if (!challenge.has("privacyProtocol") || challenge.get("privacyProtocol").getAsInt() != 2
                || !proof.equals(challenge.get("serverId").getAsString()))
              throw new java.io.IOException("This relay needs the privacy update before sharing can connect");
            // Authlib sends the Minecraft token only to Mojang's official session service.
            mc.services()
                .sessionService()
                .joinServer(
                    user.getProfileId(),
                    user.getAccessToken(),
                    proof);
            var auth =
                c.post(
                    "/v1/authenticate",
                    Map.of("challengeId", challenge.get("challengeId").getAsString()));
            if (!user.getProfileId().toString().equals(auth.get("uuid").getAsString()))
              throw new java.io.IOException("Relay identity mismatch");
            c.token(auth.get("token").getAsString());
            c.post("/v1/preferences", prefs);
            String id = auth.get("uuid").getAsString();
            return () -> {
              ownUuid = id;
              authenticated = true;
              status = "Connected · invitations require acceptance";
            };
          });
      return;
    }
    String snapshot = world;
    submit(
        c -> {
          var state = c.post("/v1/state", Map.of("world", snapshot, "sceneProtocol", 3));
          var items = ConfigStore.GSON.fromJson(state.get("invites"), Invitation[].class);
          long offset = c.clockOffsetMs;
          return () -> { readCooldown(state); receive(items, offset); };
        });
  }

  private Map<String, Object> preferences() {
    return Map.of("blockAll", settings.blockAll, "blocked", List.copyOf(settings.blocked.keySet()));
  }

  private void finishInvitation() {
    String id = pendingCancel.isEmpty() ? pendingFinish : pendingCancel;
    String action = pendingCancel.isEmpty() ? "finish" : "cancel";
    int revision = stopRevision;
    submit(
        c -> {
          try {
            var result = c.post("/v1/invites/" + id + "/" + action, Map.of());
            mc.execute(() -> readCooldown(result));
          } catch (EditRelayClient.Failure error) {
            if (error.status != 404) throw error;
          }
          return () -> {
            rememberEnded(id);
            if (revision != stopRevision) return;
            pendingCancel = "";
            pendingFinish = "";
            invitation = null;
            cleanup();
            status = "Shared edit ended";
          };
        });
  }

  private void rememberEnded(String id) {
    endedInvitations.add(id);
    consented.remove(id);
    if (endedInvitations.size() > 128) endedInvitations.remove(endedInvitations.iterator().next());
  }

  private void receive(Invitation[] items, long offset) {
    if (resetPending || !pendingCancel.isEmpty() || !pendingFinish.isEmpty()) return;
    Invitation found = null;
    for (var i : items)
      if (!endedInvitations.contains(i.id) && invitation != null && i.id.equals(invitation.id)) {
        found = i;
        break;
      }
    if (found == null)
      for (var i : items)
        if (!endedInvitations.contains(i.id)
            && (i.state.equals("pending")
                || i.state.equals("accepted")
                || i.state.equals("scheduled"))) {
          found = i;
          break;
        }
    if (found == null) {
      if (invitation != null) {
        stopLocal();
        invitation = null;
      }
      status = "Connected · ready to share";
      return;
    }
    invitation = found;
    if (found.receiverUuid.equals(ownUuid)
        && (settings.blockAll || settings.blocked.containsKey(found.senderUuid))) {
      cancel();
      return;
    }
    if (!found.world.equals(world)
        || found.state.equals("cancelled")
        || found.state.equals("finished")) {
      rememberEnded(found.id);
      stopLocal();
      invitation = null;
      status = "Shared edit ended";
      return;
    }
    if (!found.id.equals(announced)
        && found.receiverUuid.equals(ownUuid)
        && found.state.equals("pending")) {
      announced = found.id;
      message(
          found.senderName
              + " invited you to an edit of "
              + found.entityName
              + ". Open /edits to accept, decline or block.");
    }
    if ((found.state.equals("accepted") || found.state.equals("scheduled"))
        && !consentKey(found).equals(consented.get(found.id))) {
      cancel();
      return;
    }
    if (found.state.equals("accepted") && !prepared) {
      prepare(found);
      return;
    }
    if (found.state.equals("scheduled") && prepared && !playing) {
      long delay = found.startAtMs - offset - System.currentTimeMillis();
      Entity target = entity(found.entityUuid);
      if (delay < 0
          || delay > 15000
          || !available(found)
          || app.celebrations.active()
          || mc.gui.screen() != null && !(mc.gui.screen() instanceof SharedEditsScreen)) {
        message("The edit could not start. Stay connected, close other menus and try again.");
        cancel();
        return;
      }
      if (mc.gui.screen() instanceof SharedEditsScreen) mc.gui.setScreen(null);
      var display = ConfigStore.copy(manifest.settings, CelebrationSettings.class);
      if (found.receiverUuid.equals(ownUuid) && display.volume > 0)
        display.edit.volumeMultiplier =
            Math.min(display.edit.volumeMultiplier, settings.volumeLimit / display.volume);
      if (preparedRemote != null)
        app.celebrations.showRemote(
            display, preparedRemote, song, manifest.seed, System.nanoTime() + delay * 1000000);
      else
        app.celebrations.showScene(
            display,
            found.entityName,
            target,
            song,
            manifest.seed,
            System.nanoTime() + delay * 1000000,
            false,
            found.scene,
            sceneProfile);
      playing = true;
      status = "Shared edit · Esc or /stopedit to stop";
    }
  }

  public void send(String name, boolean aimed) {
    send(name, aimed, app.store.config.jobs.celebration);
  }

  public void send(String name, boolean aimed, CelebrationSettings settings) {
    send(name, aimed, settings, null);
  }

  public void send(String name, boolean aimed, CelebrationSettings settings, EditScene scene) {
    if (name.equalsIgnoreCase(mc.getUser().getName())
        || mc.player != null && name.equalsIgnoreCase(mc.player.getGameProfile().name())) {
      watch(aimed, settings, scene);
      return;
    }
    if (resetPending) {
      message("Stopping shared edits; wait for the relay reset to finish.");
      return;
    }
    if (!authenticated || busy) {
      message("Enable sharing in /edits and wait for Connected.");
      return;
    }
    if (localStarting || invitation != null || app.celebrations.active()) {
      message("Finish the current edit or invitation first.");
      return;
    }
    long now = System.currentTimeMillis();
    if (cooldownSeconds() > 0) { message("Please wait " + cooldownSeconds() + " seconds before sharing another edit"); return; }
    if (now < nextAttempt) { message("Please wait " + Math.max(1, (nextAttempt - now + 999) / 1000) + " seconds before trying again"); return; }
    nextAttempt = now + 15000;
    Entity target =
        aimed && mc.hitResult instanceof EntityHitResult hit
            ? hit.getEntity()
            : aimed ? null : mc.player;
    if (target == null && scene == null) {
      message("Look at a loaded entity, then use /sendedit player target.");
      return;
    }
    if (scene != null && !EditAvatar.available(scene)) {
      message("Avatar position must be nearby and loaded in this dimension.");
      return;
    }
    String uuid = scene == null ? target.getUUID().toString() : scene.playerId(),
        label = scene == null ? target.getName().getString() : scene.playerName();
    if (label.length() > 80) label = label.substring(0, 80);
    String entityName = label, snapshot = world;
    var settingsCopy = ConfigStore.copy(settings, CelebrationSettings.class);
    status = "Preparing and uploading your edit...";
    transfer = EditTransfer.preparing("Preparing your edit");
    int version = generation, revision = stopRevision;
    CompletableFuture<RemoteScenes.Captured> capture =
        this.settings.shareTerrain
            ? remoteScenes.capture(
                target,
                scene,
                this.settings.terrainRadius,
                () -> version == generation && revision == stopRevision)
            : null;
    submit(
        c -> {
          var captured = capture == null ? null : capture.get();
          var profile = scene == null ? null : resolve(scene, true);
          var resolved =
              scene == null ? null : scene.profile(profile.id().toString(), profile.name());
          var analysis = app.sounds.analyzeSong(settingsCopy, settingsCopy.edit).get();
          RemoteScene remote = captured == null ? null : captured.scene();
          if (remote != null && resolved != null) {
            remote.pose = resolved;
            remote.name = resolved.playerName();
          }
          byte[] terrain = captured == null ? null : captured.terrain().encode();
          Path temporary = temporaryArchive("outgoing-");
          try {
            SharedEditBundle.write(
                temporary,
                settingsCopy,
                analysis,
                app.store.sounds(),
                app.celebrations.images(),
                resolved,
                remote,
                terrain);
            synchronized (audioCache) {
              if (this.settings.keepDownloadedSongs && version == generation && revision == stopRevision) {
                var outgoing = SharedEditBundle.read(temporary);
                audioCache.save(outgoing.manifest().audioSha256, outgoing.audio());
              }
            }
            c.uploadProgress(
                (done, total) -> {
                  if (version == generation && revision == stopRevision)
                    transfer =
                        new EditTransfer(
                            done < total
                                ? "Uploading edit to relay"
                                : "Relay is checking your edit",
                            done,
                            total);
                });
            var response =
                c.sendInvite(
                    temporary,
                    snapshot,
                    Map.of(
                        "player",
                        name,
                        "entityUuid",
                        resolved == null ? uuid : resolved.playerId(),
                        "entityName",
                        resolved == null ? entityName : resolved.playerName()),
                    () -> version != generation || revision != stopRevision);
            var invite = ConfigStore.GSON.fromJson(response, Invitation.class);
            return () -> {
              if (resetPending || revision != stopRevision) return;
              transfer = null;
              invitation = invite;
              consented.clear();
              consented.put(invite.id, consentKey(invite));
              message("Invitation sent to " + name + ". Waiting for acceptance.");
            };
          } finally {
            Files.deleteIfExists(temporary);
          }
        });
  }

  public void accept() {
    if (invitation == null
        || !invitation.state.equals("pending")
        || !invitation.receiverUuid.equals(ownUuid)
        || busy
        || resetPending) return;
    if (settings.blockAll || settings.blocked.containsKey(invitation.senderUuid)) {
      message("Unblock the sender before accepting.");
      return;
    }
    if (!available(invitation) || localStarting || app.celebrations.active()) {
      message(
          "Stay connected and finish the current edit. Nearby mode also needs the subject loaded.");
      return;
    }
    String id = invitation.id;
    consented.clear();
    consented.put(id, consentKey(invitation));
    submit(
        c -> {
          var response = c.post("/v1/invites/" + id + "/accept", Map.of());
          return () -> {
            if (resetPending) return;
            invitation = ConfigStore.GSON.fromJson(response, Invitation.class);
            status = "Accepted · preparing both players";
          };
        });
  }

  private void prepare(Invitation invite) {
    if (!available(invite)) {
      cancel();
      return;
    }
    status = "Downloading and checking the shared edit...";
    transfer = EditTransfer.preparing("Checking saved songs");
    int version = generation, revision = stopRevision;
    java.util.function.BooleanSupplier cancelled =
        () -> version != generation || revision != stopRevision;
    submit(
        c -> {
          Path zip = temporaryArchive("incoming-");
          try {
            boolean keepSongs = settings.keepDownloadedSongs;
            byte[] savedAudio = keepSongs ? audioCache.read(invite.audioSha256) : null;
            String fingerprint = savedAudio == null ? "" : invite.audioSha256;
            c.download(
                invite.id,
                zip,
                fingerprint,
                (done, total) -> {
                  if (!cancelled.getAsBoolean())
                    transfer =
                        new EditTransfer(
                            savedAudio == null
                                ? "Downloading shared edit"
                                : "Song saved · downloading scene and settings",
                            done,
                            total);
                });
            if (cancelled.getAsBoolean()) throw new CancellationException("Shared edit stopped");
            if (version == generation)
              transfer = EditTransfer.preparing("Checking song and preparing playback");
            var received = SharedEditBundle.read(zip, savedAudio);
            var m = received.manifest();
            if (!Objects.equals(m.scene, invite.scene)
                || !ConfigStore.GSON
                    .toJson(m.remote)
                    .equals(ConfigStore.GSON.toJson(invite.remote)))
              throw new java.io.IOException("Shared subject differs from the accepted invitation");
            var pose =
                m.remote == null
                    ? m.scene
                    : m.remote.type.equals("minecraft:player") ? m.remote.pose : null;
            var profile = pose == null ? null : resolve(pose, false);
            RemoteScenes.Prepared remote = null;
            if (m.remote != null) {
              var terrain = TerrainSnapshot.decode(received.terrain());
              remote =
                  mc.submit(
                          () -> {
                            try {
                              if (cancelled.getAsBoolean())
                                throw new CancellationException("Shared edit stopped");
                              return remoteScenes.prepare(
                                  m.remote, terrain, profile, () -> !cancelled.getAsBoolean());
                            } catch (Exception e) {
                              throw new RuntimeException(e);
                            }
                          })
                      .get()
                      .get();
            }
            var preparedScene = remote;
            Path audioFolder = app.store.sounds().resolve(".shared/session");
            Files.createDirectories(audioFolder);
            Path audio = Files.createTempFile(audioFolder, "edit-", ".mp3");
            String audioName = app.store.sounds().relativize(audio).toString().replace('\\', '/'),
                imageName = ".shared/" + invite.id + ".png";
            Path image = app.celebrations.images().resolve(imageName);
            try {
              Files.createDirectories(image.getParent());
              Files.write(audio, received.audio());
              if (received.image() != null) Files.write(image, received.image());
              m.settings.sound = audioName;
              m.settings.image = received.image() == null ? "" : imageName;
              var actual = app.sounds.automaticSong(m.settings).get();
              if (Math.abs(actual.durationMs() - m.durationMs) > 1)
                throw new java.io.IOException("Shared song length mismatch");
              var analysis = actual.withHits(m.hits);
              if (cancelled.getAsBoolean()) throw new CancellationException("Shared edit stopped");
              synchronized (audioCache) {
                if (keepSongs && settings.keepDownloadedSongs && !cancelled.getAsBoolean())
                  audioCache.save(m.audioSha256, received.audio());
              }
              c.post("/v1/invites/" + invite.id + "/ready", Map.of());
              return new Discardable() {
                public void discard() {
                  try { Files.deleteIfExists(image); } catch (Exception ignored) { }
                  try { Files.deleteIfExists(audio); } catch (Exception ignored) { }
                }
                public void run() {
                if (cancelled.getAsBoolean() || resetPending || !pendingCancel.isEmpty()) {
                  discard();
                  return;
                }
                manifest = m;
                preparedRemote = preparedScene;
                sceneProfile = profile;
                song = analysis;
                cachedAudio = audio;
                cachedImage = image;
                prepared = true;
                transfer = null;
                status = "Ready · waiting for both players";
                }
              };
            } catch (Exception e) {
              Files.deleteIfExists(image);
              Files.deleteIfExists(audio);
              throw e;
            }
          } finally {
            Files.deleteIfExists(zip);
          }
        },
        error -> {
          if (cancelled.getAsBoolean() || invitation == null || !invite.id.equals(invitation.id))
            return;
          cancel();
          invitation = null;
          message("Edit cancelled: " + reason(error));
        });
  }

  public void blockSender() {
    if (invitation == null) return;
    String id = invitation.senderUuid, name = invitation.senderName;
    if (id.equals(ownUuid)) return;
    settings.blocked.put(id, name);
    save();
    updatePreferences();
  }

  public void updatePreferences() {
    save();
    preferencesDirty = true;
    if (invitation != null
        && (settings.blockAll || settings.blocked.containsKey(invitation.senderUuid))) cancel();
  }

  public void cancel() {
    if (invitation == null) {
      stopAll();
      return;
    }
    stopRevision++;
    rememberEnded(invitation.id);
    pendingCancel = invitation.id;
    stopLocal();
    nextPoll = 0;
  }

  public void stopAll() {
    localRevision++;
    localStarting = false;
    if (app.celebrations.localActive()) app.celebrations.stop();
    stopRevision++;
    if (invitation != null) rememberEnded(invitation.id);
    resetPending = true;
    pendingCancel = pendingFinish = "";
    stopLocal();
    invitation = null;
    consented.clear();
    nextPoll = 0;
    message(authenticated ? "Stopping edits and clearing uploads..." : "Shared edits stopped");
  }

  private void stopLocal() {
    if (playing) app.celebrations.stop();
    playing = false;
    prepared = false;
    cleanup();
  }

  private void cleanup() {
    transfer = null;
    app.sounds.forgetSharedAudio();
    for (var p : Arrays.asList(cachedAudio, cachedImage))
      if (p != null)
        try {
          Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    cachedAudio = cachedImage = null;
    manifest = null;
    preparedRemote = null;
    remoteScenes.cancel();
    sceneProfile = null;
    song = null;
  }

  public void disconnect() {
    generation++;
    busy = false;
    stopLocal();
    invitation = null;
    authenticated = false;
    world = "";
    ownUuid = "";
    pendingCancel = "";
    pendingFinish = "";
    resetPending = false;
    consented.clear();
    var old = relay;
    relay = null;
    if (old != null) old.stopThenClose();
    status = settings.enabled ? "Disconnected" : "Sharing is off";
  }

  public boolean stopFromEscape() {
    if (localStarting || app.celebrations.localActive()) {
      localRevision++;
      localStarting = false;
      app.celebrations.stop();
      return true;
    }
    if (!playing) return false;
    cancel();
    return true;
  }

  @Override
  public void close() {
    localRevision++;
    disconnect();
    worker.shutdownNow();
  }

  private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
    return LiteralArgumentBuilder.literal(name);
  }

  private static RequiredArgumentBuilder<FabricClientCommandSource, String> argument(
      String name, StringArgumentType type) {
    return RequiredArgumentBuilder.argument(name, type);
  }

  private void register() {
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registry) -> {
          dispatcher.register(
              literal("edits")
                  .executes(
                      ctx -> {
                        openRequested = true;
                        return 1;
                      }));
          dispatcher.register(
              literal("stopedit")
                  .executes(
                      ctx -> {
                        stopAll();
                        return 1;
                      }));
          dispatcher.register(
              literal("stopedits")
                  .executes(
                      ctx -> {
                        stopAll();
                        return 1;
                      }));
          dispatcher.register(
              literal("playedit")
                  .executes(
                      ctx -> {
                        watch(false, app.store.config.jobs.celebration, null);
                        return 1;
                      })
                  .then(
                      literal("target")
                          .executes(
                              ctx -> {
                                watch(true, app.store.config.jobs.celebration, null);
                                return 1;
                              })));
          dispatcher.register(
              literal("sendedit")
                  .then(
                      argument("player", StringArgumentType.word())
                          .executes(
                              ctx -> {
                                send(StringArgumentType.getString(ctx, "player"), false);
                                return 1;
                              })
                          .then(
                              literal("target")
                                  .executes(
                                      ctx -> {
                                        send(StringArgumentType.getString(ctx, "player"), true);
                                        return 1;
                                      }))));
        });
  }
}
