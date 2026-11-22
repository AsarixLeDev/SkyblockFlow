package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;

/** Opt-in presence and signed appearance, independent of edit sharing. */
public final class PlayerNames {
  public static final String RELAY = "https://flow-edit-relay-production.up.railway.app";
  private final SkyblockFlowClient app;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
    var thread = new Thread(task, "SkyblockFlow player names"); thread.setDaemon(true); return thread;
  });
  private record Update(NameAppearance appearance, Set<UUID> players, Set<UUID> badges, Set<UUID> styles, long fetched) {}
  private CompletableFuture<Update> pending;
  private EditRelayClient connection;
  private Object world;
  private record Prepared(NameAppearance appearance, long fetched, boolean failed) {}
  private static final class Preparation {
    volatile NameAppearance cached;
    volatile EditRelayClient client;
    volatile boolean cancelled;
    volatile String phase = "Loading saved appearance…";
    CompletableFuture<Prepared> result;
  }
  private Preparation preparation;
  private volatile String connectionPhase = "Connecting community names…";
  private boolean authenticated, enabled;
  private boolean showChatBadges = true, showTabBadges = true, shareBadge = true, allowCustomStyle = true, preferencesChanged;
  private NameAppearance appearance;
  private Set<UUID> players = Set.of();
  private Set<UUID> badges = Set.of(), styles = Set.of();
  private long nextPoll, lastPresence, lastManifest;
  public String status = "Disabled";

  public PlayerNames(SkyblockFlowClient app) {
    this.app = app;
    reloadPreferences();
  }

  void pauseForConfigReload() {
    cancelPreparation();
    disconnect();
  }

  void reloadPreferences() {
    enabled = false;
    showChatBadges = showTabBadges = shareBadge = allowCustomStyle = true;
    preferencesChanged = false;
    appearance = null;
    lastManifest = 0;
    Path consent = consentFile();
    try {
      if (Files.isRegularFile(consent) && Files.size(consent) <= 2048) {
        var doc = com.google.gson.JsonParser.parseString(Files.readString(consent)).getAsJsonObject();
        enabled = doc.get("version").getAsInt() == 1 && RELAY.equals(doc.get("relay").getAsString()) && doc.get("enabled").getAsBoolean();
        boolean previous = !doc.has("showBadges") || doc.get("showBadges").getAsBoolean();
        showChatBadges = doc.has("showChatBadges") ? doc.get("showChatBadges").getAsBoolean() : previous;
        showTabBadges = doc.has("showTabBadges") ? doc.get("showTabBadges").getAsBoolean() : previous;
        shareBadge = !doc.has("shareBadge") || doc.get("shareBadge").getAsBoolean();
        allowCustomStyle = !doc.has("allowCustomStyle") || doc.get("allowCustomStyle").getAsBoolean();
      }
    } catch (Exception ignored) { enabled = false; }
    status = enabled ? "Connect to a world" : "Disabled";
    if (enabled) prepareAppearance();
  }

  private void prepareAppearance() {
    cancelPreparation();
    var task = new Preparation();
    preparation = task;
    Path cache = app.store.directory().resolve("appearance-signed.json");
    task.result = CompletableFuture.supplyAsync(() -> {
      try {
        if (task.cancelled) return new Prepared(null, 0, false);
        var key = NameAppearance.publisherKey();
        if (Files.isRegularFile(cache) && Files.size(cache) <= 262144) {
          try { task.cached = NameAppearance.verify(Files.readString(cache), key, 0); }
          catch (Exception ignored) { /* Only a verified cache can supply a saved style. */ }
        }
        task.phase = "Updating name appearance…";
        if (task.cancelled) return new Prepared(null, 0, false);
        try (var client = new EditRelayClient(RELAY, java.time.Duration.ofSeconds(12))) {
          task.client = client;
          if (task.cancelled) return new Prepared(null, 0, false);
          String envelope = ConfigStore.GSON.toJson(client.get("/v1/appearance"));
          var settings = NameAppearance.verify(envelope, key, task.cached == null ? 0 : task.cached.revision());
          synchronized (task) {
            if (task.cancelled) return new Prepared(null, 0, false);
            Path temporary = cache.resolveSibling("appearance-startup.tmp");
            Files.writeString(temporary, envelope);
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
          }
          return new Prepared(settings, System.currentTimeMillis(), false);
        }
      } catch (Exception error) { return new Prepared(task.cached, 0, true); }
    }, worker);
  }

  private void cancelPreparation() {
    var old = preparation; preparation = null;
    if (old != null) {
      synchronized (old) {
        old.cancelled = true;
      }
      if (old.client != null) old.client.close();
      if (old.result != null) old.result.cancel(true);
    }
  }

  private void pollPreparation() {
    var task = preparation;
    if (task == null) return;
    if (appearance == null && task.cached != null) { appearance = task.cached; refreshChat(); }
    if (!task.result.isDone()) return;
    var result = task.result.join();
    preparation = null;
    if (result.appearance() != null) {
      appearance = result.appearance(); lastManifest = result.fetched(); refreshChat();
    }
    status = result.failed() ? "Appearance unavailable · retrying when you join" : "Ready to join";
  }

  public boolean loading() {
    return enabled && (preparation != null || pending != null && !authenticated);
  }
  public String statusText() {
    if (!enabled) return "Disabled";
    if (preparation != null) return preparation.phase;
    if (pending != null && !authenticated) return connectionPhase;
    return status;
  }
  private Path consentFile() { return app.store.directory().resolve("community-names.json"); }
  public boolean enabled() { return enabled; }
  public boolean showChatBadges() { return showChatBadges; }
  public boolean showTabBadges() { return showTabBadges; }
  public boolean shareBadge() { return shareBadge; }
  public boolean allowCustomStyle() { return allowCustomStyle; }
  private void savePreferences(boolean active, boolean chat, boolean tab, boolean share, boolean custom) throws java.io.IOException {
    Path temporary = consentFile().resolveSibling("community-names.tmp");
    Files.writeString(temporary, ConfigStore.GSON.toJson(Map.of("version", 1, "relay", RELAY, "enabled", active,
        "showChatBadges", chat, "showTabBadges", tab, "shareBadge", share, "allowCustomStyle", custom)));
    Files.move(temporary, consentFile(), StandardCopyOption.REPLACE_EXISTING);
  }
  public void setPreferences(boolean chat, boolean tab, boolean share, boolean custom) {
    try {
      savePreferences(enabled, chat, tab, share, custom);
      boolean changed = share != shareBadge || custom != allowCustomStyle;
      showChatBadges = chat; showTabBadges = tab; shareBadge = share; allowCustomStyle = custom;
      if (changed) { preferencesChanged = true; nextPoll = 0; }
      refreshChat();
    } catch (Exception error) { app.error("Could not save community name preferences"); }
  }
  public void setEnabled(boolean value) {
    try {
      savePreferences(value, showChatBadges, showTabBadges, shareBadge, allowCustomStyle);
      enabled = value; disconnect();
      if (value) prepareAppearance();
      else { cancelPreparation(); appearance = null; lastManifest = 0; }
    } catch (Exception error) { app.error("Could not save community name preferences"); }
  }
  public void close() { cancelPreparation(); disconnect(); worker.shutdownNow(); }


  public void disconnect() {
    var old = connection; connection = null; pending = null; authenticated = false;
    players = badges = styles = Set.of(); lastPresence = nextPoll = 0;
    if (old != null) old.leaveAppearanceThenClose();
    status = enabled ? "Connect to a world" : "Disabled";
    refreshChat();
  }

  public void tick(Minecraft mc) {
    if (enabled) pollPreparation();
    if (!enabled || mc.level == null || mc.player == null) {
      if (connection != null) disconnect();
      world = null;
      return;
    }
    if (world != mc.getConnection()) { disconnect(); world = mc.getConnection(); }
    if (preparation != null) return;
    long now = System.currentTimeMillis();
    if (pending != null) {
      if (!pending.isDone()) return;
      try {
        var result = pending.join();
        boolean changed = appearance == null || appearance.revision() != result.appearance().revision()
            || !players.equals(result.players()) || !badges.equals(result.badges()) || !styles.equals(result.styles())
            || now - lastPresence > 120000;
        appearance = result.appearance(); players = result.players(); badges = result.badges(); styles = result.styles(); lastManifest = result.fetched();
        lastPresence = now; authenticated = true; status = "Connected";
        if (changed) refreshChat();
        nextPoll = preferencesChanged ? 0 : now + 30000;
      } catch (Exception error) {
        if (connection != null) connection.close();
        connection = null; authenticated = false; players = badges = styles = Set.of();
        refreshChat();
        status = "Connection unavailable · retrying shortly"; nextPoll = now + 60000;
      }
      pending = null;
    }
    if (now < nextPoll || pending != null) return;
    nextPoll = now + 30000;
    if (connection == null) connection = new EditRelayClient(RELAY, java.time.Duration.ofSeconds(12));
    var client = connection; var user = mc.getUser(); var sessionService = mc.services().sessionService();
    boolean needsAuth = !authenticated;
    boolean publishBadge = shareBadge, publishStyle = allowCustomStyle;
    preferencesChanged = false;
    var previous = appearance; long previousFetch = lastManifest;
    Path cache = app.store.directory().resolve("appearance-signed.json");
    connectionPhase = "Connecting community names…";
    status = authenticated ? status : connectionPhase;
    pending = CompletableFuture.supplyAsync(() -> {
      try {
        var key = NameAppearance.publisherKey(); var settings = previous; long fetched = previousFetch;
        if (settings == null && Files.isRegularFile(cache) && Files.size(cache) <= 262144) {
          try { settings = NameAppearance.verify(Files.readString(cache), key, 0); }
          catch (Exception ignored) { /* An invalid cache never supplies a style. */ }
        }
        if (settings == null || now - fetched >= 300000) {
          if (connection == client) connectionPhase = "Updating name appearance…";
          String envelope = ConfigStore.GSON.toJson(client.get("/v1/appearance"));
          settings = NameAppearance.verify(envelope, key, settings == null ? 0 : settings.revision());
          Path temporary = cache.resolveSibling("appearance-signed.tmp");
          Files.writeString(temporary, envelope); Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
          fetched = now;
        }
        if (needsAuth) {
          if (connection == client) connectionPhase = "Connecting community names…";
          byte[] random = new byte[20]; new java.security.SecureRandom().nextBytes(random);
          String proof = HexFormat.of().formatHex(random);
          var challenge = client.post("/v2/challenge", Map.of("name", user.getName(), "serverId", proof, "purpose", "appearance"));
          if (challenge.get("privacyProtocol").getAsInt() != 2 || !proof.equals(challenge.get("serverId").getAsString()))
            throw new java.io.IOException("Invalid authentication challenge");
          if (connection == client) connectionPhase = "Verifying your account…";
          sessionService.joinServer(user.getProfileId(), user.getAccessToken(), proof);
          var auth = client.post("/v1/authenticate", Map.of("challengeId", challenge.get("challengeId").getAsString()));
          if (!user.getProfileId().toString().equals(auth.get("uuid").getAsString())) throw new java.io.IOException("Identity mismatch");
          client.token(auth.get("token").getAsString());
        }
        if (connection == client) connectionPhase = "Loading community players…";
        var presence = client.post("/v1/appearance/presence", Map.of("badge", publishBadge, "customStyle", publishStyle));
        var ids = presenceIds(presence, "players");
        var visibleBadges = presenceIds(presence, "badges");
        var customStyles = presenceIds(presence, "styles");
        if (!ids.containsAll(visibleBadges) || !ids.containsAll(customStyles)) throw new java.io.IOException("Invalid presence preferences");
        return new Update(settings, ids, visibleBadges, customStyles, fetched);
      } catch (Exception error) { throw new CompletionException(error); }
    }, worker);
  }

  private static Set<UUID> presenceIds(com.google.gson.JsonObject doc, String field) throws java.io.IOException {
    var values = doc.getAsJsonArray(field);
    if (values == null || values.size() > 1000) throw new java.io.IOException("Invalid presence list");
    var ids = new HashSet<UUID>();
    for (var entry : values) ids.add(UUID.fromString(entry.getAsString()));
    return Set.copyOf(ids);
  }

  private void refreshChat() {
    var mc = Minecraft.getInstance();
    if (mc.gui != null && mc.gui.hud != null)
      ((PlayerNamesChatRefresh) mc.gui.hud.getChat()).refreshCommunityNames();
  }

  /** Server chat keeps its original content; only the wrapped display is decorated. */
  public Component styleChat(Component original) {
    if (!enabled || appearance == null) return original;
    var sender = ChatNameHeader.find(original.getString());
    if (sender == null) return original;
    var mc = Minecraft.getInstance();
    var connection = mc.getConnection();
    var info = connection == null ? null : connection.getPlayerInfo(sender.name());
    if (info == null && connection != null)
      for (var candidate : connection.getOnlinePlayers())
        if (candidate.getProfile().name().equalsIgnoreCase(sender.name())) { info = candidate; break; }
    UUID id = info == null ? null : info.getProfile().id();
    if (id == null && sender.name().equalsIgnoreCase(mc.getUser().getName())) id = mc.getUser().getProfileId();
    if (id == null || !eligible(id)) return original;
    return decorateRange(original, sender.start(), sender.end(), look(id), showChatBadges && badge(id));
  }

  private boolean eligible(UUID id) {
    if (!enabled || appearance == null) return false;
    if (id.equals(Minecraft.getInstance().getUser().getProfileId())) return true;
    return players.contains(id) && System.currentTimeMillis() - lastPresence <= 120000;
  }
  private NameAppearance.Look look(UUID id) {
    boolean mine = id.equals(Minecraft.getInstance().getUser().getProfileId());
    return (mine ? allowCustomStyle : styles.contains(id)) ? appearance.forPlayer(id) : NameAppearance.Look.server();
  }
  private boolean badge(UUID id) {
    boolean mine = id.equals(Minecraft.getInstance().getUser().getProfileId());
    return mine ? shareBadge : badges.contains(id);
  }

  public Component style(UUID id, String accountName, Component original) {
    if (!eligible(id)) return original;
    return decorateName(original, accountName, look(id), showTabBadges && badge(id));
  }

  /** Style only an exact account-name segment, preserving ranks, links and message contents. */
  public static Component styleName(Component original, String accountName, NameAppearance.Look look) {
    return decorateName(original, accountName, look, false);
  }

  public static Component decorateName(Component original, String accountName, NameAppearance.Look look, boolean badge) {
    if (!badge && look.keepsColor() && look.keepsFont()) return original;
    String full = original.getString();
    int start = full.indexOf(accountName), end = start + accountName.length();
    if (accountName.isEmpty() || start < 0 || full.indexOf(accountName, end) >= 0
        || (start > 0 && nameChar(full.charAt(start - 1))) || (end < full.length() && nameChar(full.charAt(end)))) return original;
    return decorateRange(original, start, end, look, badge);
  }

  private static Component decorateRange(Component original, int start, int end, NameAppearance.Look look, boolean badge) {
    if (!badge && look.keepsColor() && look.keepsFont()) return original;
    var output = Component.empty(); int[] offset = {0};
    var font = look.keepsFont() ? null : new FontDescription.Resource(look.font().equals("smooth")
        ? Identifier.fromNamespaceAndPath("skyblockflow", "ui")
        : Identifier.fromNamespaceAndPath("minecraft", look.font()));
    original.visit((style, text) -> {
      int from = offset[0], to = from + text.length(); offset[0] = to;
      int a = Math.max(from, start), b = Math.min(to, end);
      if (a >= b) output.append(Component.literal(text).setStyle(style));
      else {
        if (a > from) output.append(Component.literal(text.substring(0, a - from)).setStyle(style));
        for (int at = a; at < b; at++) {
          var namedStyle = style;
          if (!look.keepsColor()) namedStyle = namedStyle.withColor(look.colorAt((at - start) / (double)Math.max(1, end - start - 1)));
          if (font != null) namedStyle = namedStyle.withFont(font);
          output.append(Component.literal(text.substring(at - from, at - from + 1))
              .setStyle(namedStyle));
        }
        if (badge && b == end) output.append(badgeComponent());
        if (b < to) output.append(Component.literal(text.substring(b - from)).setStyle(style));
      }
      return Optional.empty();
    }, Style.EMPTY);
    return output;
  }

  private static Component badgeComponent() {
    return Component.literal(" ").setStyle(Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("minecraft", "default"))))
        .append(Component.literal("\uEFC0").setStyle(Style.EMPTY.withColor(0xFFFFFF)
            .withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("skyblockflow", "sf_badge")))));
  }

  private static boolean nameChar(char c) { return c == '_' || Character.isLetterOrDigit(c); }
}
