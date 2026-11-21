package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import dev.asarix.skyblockflow.ui.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkyblockFlowClient implements ClientModInitializer {
  public static SkyblockFlowClient INSTANCE;
  public static final Logger LOG = LoggerFactory.getLogger("SkyblockFlow");
  public ConfigStore store;
  public SoundService sounds;
  public JobService jobs;
  public PetService pets;
  public ClosestPlayerService closest;
  public TimerService timers;
  public CelebrationService celebrations;
  public SharedEdits shared;
  public PlayerNames playerNames;
  public final DiscordVoice voice = new DiscordVoice();
  final DiscordPortraits portraits = new DiscordPortraits();
  public final DesktopMedia media = new DesktopMedia();
  public Waypoints waypoints;
  public SpawnerRanges spawners;
  public GameplayTools tools;
  final RocketMonitor rocket = new RocketMonitor(this);
  public final Deque<String> recentCommands = new ArrayDeque<>();
  public long inventoryRevision;
  public double motionX, motionY, motionZ;
  private double sampledX, sampledY, sampledZ;
  private Object motionLevel;
  public final ConnectionGrace grace = new ConnectionGrace();
  public KeyMapping editorKey;
  public KeyMapping measureKey, measureAllKey, advanceKey;
  public final CoordinateMonitor coordinates = new CoordinateMonitor();
  public String lastError = "";
  public final List<String> history = new ArrayList<>();
  private final Map<String, AlertPattern> patterns = new HashMap<>();
  private final Map<String, Long> lastAlert = new HashMap<>();
  private Object coordinateLevel;
  private int ticks;
  private static final Pattern FORMATTED =
      Pattern.compile(
          "^(?:\\[[^\\]]+\\]\\s*)*(?:<([A-Za-z0-9_]{1,16})>|([A-Za-z0-9_]{1,16}):)\\s*(.*)$");

  public RecipeHelper recipes;

  @Override
  public void onInitializeClient() {
    INSTANCE = this;
    store = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("skyblockflow"));
    try {
      store.load();
    } catch (Exception e) {
      store.config = Config.defaults();
      error("Config could not be loaded: " + e.getMessage());
    }
    voice.load(store.directory());
    sounds = new SoundService(this);
    jobs = new JobService(this);
    pets = new PetService(this);
    closest = new ClosestPlayerService(this);
    timers = new TimerService(this);
    celebrations = new CelebrationService(this);
    shared = new SharedEdits(this);
    playerNames = new PlayerNames(this);
    waypoints = new Waypoints(this);
    spawners = new SpawnerRanges(this);
    recipes = new RecipeHelper(this);
    tools = new GameplayTools(this);
    net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registry) ->
            dispatcher.register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal(
                        "skyblockflow")
                    .executes(
                        context -> {
                          Minecraft.getInstance()
                              .schedule(
                                  () ->
                                      Minecraft.getInstance()
                                          .gui
                                          .setScreen(new FeatureBrowserScreen(this)));
                          return 1;
                        })));
    net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
        (player, level, hand, entity, hit) -> {
          if (level.isClientSide() && player == Minecraft.getInstance().player)
            rocket.interacted(entity);
          return net.minecraft.world.InteractionResult.PASS;
        });
    ClientTickEvents.END_CLIENT_TICK.register(
        mc -> {
          if (mc.level != null && (store.config.hud.music.enabled || store.config.hud.discord))
            media.poll(store.config.hud.music.enabled, store.config.hud.discord);
          else media.idle();
          voice.tick(
              store.config.hud.voice
                  && (mc.level != null || mc.gui.screen() instanceof DiscordConnectionScreen));
          if (voice.call == null) portraits.clear();
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        mc -> {
          media.close();
          voice.close();
          portraits.close();
        });
    RecipeHelper.addBackgroundHud(
        Identifier.fromNamespaceAndPath("skyblockflow", "desktop_media"),
        (g, dt) -> {
          var mc = Minecraft.getInstance();
          if (mc.player != null && !(mc.gui.screen() instanceof OverlayEditorScreen)) {
            SpotifyOverlay.render(this, g, store.config.hud.music, false, true);
            if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen))
              DiscordBadge.render(this, g, store.config.hud.discordLayout, false, true);
          }
        });
    OverlayActions.register(this);
    net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registry) ->
            dispatcher.register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal(
                        "starfallah")
                    .executes(
                        context -> {
                          for (String line : jobs.report())
                            context.getSource().sendFeedback(dev.asarix.skyblockflow.ui.DisplayText.literal(line));
                          return 1;
                        })));
    KeyMapping.Category category =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skyblockflow", "controls"));
    editorKey =
        KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.skyblockflow.editor", GLFW.GLFW_KEY_F7, category));
    measureKey =
        KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.skyblockflow.measure", -1, category));
    measureAllKey =
        KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.skyblockflow.measure_all", -1, category));
    advanceKey =
        KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.skyblockflow.advance", -1, category));
    RecipeHelper.addBackgroundHud(
        net.minecraft.resources.Identifier.fromNamespaceAndPath("skyblockflow", "edit_transfer"),
        (g, dt) -> {
          if (net.minecraft.client.Minecraft.getInstance().gui.screen() == null)
            EditTransferOverlay.render(g, shared.transfer, false);
        });
    RecipeHelper.addBackgroundHud(
        Identifier.fromNamespaceAndPath("skyblockflow", "jobs"),
        (g, dt) -> {
          if (!(Minecraft.getInstance().gui.screen()
                  instanceof net.minecraft.client.gui.screens.ChatScreen)
              && !(Minecraft.getInstance().gui.screen() instanceof OverlayEditorScreen))
            JobOverlay.render(this, g, store.config.jobs, false);
        });
    RecipeHelper.addBackgroundHud(
        Identifier.fromNamespaceAndPath("skyblockflow", "pets"),
        (g, dt) -> {
          if (!(Minecraft.getInstance().gui.screen()
                  instanceof net.minecraft.client.gui.screens.ChatScreen)
              && !(Minecraft.getInstance().gui.screen() instanceof OverlayEditorScreen))
            PetOverlay.render(this, g, store.config.pets, false);
        });
    if (!store.loadWarning.isBlank()) {
      error(store.loadWarning);
      event(store.loadWarning);
    }
    reloadPatterns();
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> {
          grace.joined(System.nanoTime() / 1000000);
          if (store.config.autoServerProfiles) loadServerProfile();
          jobs.connect();
          pets.connect();
          closest.connect();
          event(
              "Connected · alerts quiet for " + store.config.connectionGraceMs / 1000 + " seconds");
        });
    ClientReceiveMessageEvents.CHAT.register(
        (text, signed, profile, type, time) -> {
          String body = signed == null ? text.getString() : signed.signedContent();
          receive(body, text.getString(), profile == null ? null : profile.name());
        });
    ClientReceiveMessageEvents.ALLOW_GAME.register(
        (text, overlay) -> overlay || !closest.capture(text));
    ClientReceiveMessageEvents.GAME.register(
        (text, overlay) -> {
          if (overlay) {
            jobs.accept(text, "Action bar");
            return;
          }
          String full = text.getString();
          Matcher m = FORMATTED.matcher(full);
          if (m.matches()) receive(m.group(3), full, m.group(1) != null ? m.group(1) : m.group(2));
          else receive(full, full, null);
        });
    ClientTickEvents.START_CLIENT_TICK.register(this::tick);
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          motionLevel = null;
          jobs.disconnect();
          pets.disconnect();
          closest.reset();
          timers.reset();
          celebrations.reset();
          shared.disconnect();
          playerNames.disconnect();
          waypoints.disconnect();
          coordinates.reset();
          coordinateLevel = null;
          rocket.reset();
          grace.disconnected();
          sounds.stop();
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> {
          shared.close();
          playerNames.close();
          waypoints.close();
          sounds.close();
          jobs.flush();
          closest.closeHistory();
          pets.disconnect();
          save();
        });
    runtimeEvent(
        "SkyblockFlow "
            + FabricLoader.getInstance()
                .getModContainer("skyblockflow")
                .orElseThrow()
                .getMetadata()
                .getVersion()
                .getFriendlyString()
            + " ready for Minecraft 26.2");
  }

  public void error(String message) {
    lastError = message;
    LOG.warn(message);
  }

  public boolean save() {
    try {
      store.save();
      if (store.config.autoServerProfiles
          && !store.config.profileName.equals("default")
          && store.config.profileName.matches("[a-f0-9]{16}")) {
        var file =
            store.directory().resolve("profiles").resolve(store.config.profileName + ".json");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, ConfigStore.GSON.toJson(store.config));
      }
      lastError = "";
      return true;
    } catch (Exception e) {
      error("Could not save: " + e.getMessage());
      return false;
    }
  }

  public void reloadPatterns() {
    patterns.clear();
    lastAlert.clear();
    for (AlertRule rule : store.config.alerts)
      try {
        patterns.put(rule.id, new AlertPattern(rule));
      } catch (Exception e) {
        error(rule.name + ": " + e.getMessage());
      }
  }

  public void receive(String body, String full, String sender) {
    long now = System.nanoTime() / 1000000;
    if (sender == null) {
      rocket.denial(body);
      pets.message(body);
      celebrations.message(body);
    }
    if (!alertsAllowed() || !store.config.enabled(Feature.CHAT)) return;
    for (AlertRule rule : store.config.alerts) {
      if (!rule.enabled || !patterns.containsKey(rule.id)) continue;
      try {
        Map<String, String> variables =
            patterns.get(rule.id).match(rule.matchFullChat ? full : body, sender);
        if (variables == null) continue;
        if (now - lastAlert.getOrDefault(rule.id, Long.MIN_VALUE / 2) < rule.cooldownMs) continue;
        lastAlert.put(rule.id, now);
        notifyAlert(rule, variables);
      } catch (Exception e) {
        patterns.remove(rule.id);
        rule.enabled = false;
        error("Disabled " + rule.name + ": " + e.getMessage());
      }
    }
  }

  public void notifyAlert(AlertRule rule, Map<String, String> vars) {
    String title = AlertPattern.render(rule.title, vars);
    String subtitle = AlertPattern.render(rule.subtitle, vars);
    if (!presentAlarm(AlarmKind.CHAT, title, subtitle, rule)) return;
    event(rule.name + " · " + vars.get("sender") + " · " + title);
  }

  public boolean alertsAllowed() {
    return grace.allows(System.nanoTime() / 1000000, store.config.connectionGraceMs);
  }

  public void event(String message) {
    history.addFirst(java.time.LocalTime.now().withNano(0) + " · " + message);
    while (history.size() > 200) history.removeLast();
  }

  /** Keep transition reasons available even when Lunar filters mod loggers. */
  public void runtimeEvent(String message) {
    event(message);
    LOG.info("{}", message);
    try {
      var file = store.directory().resolve("runtime.log");
      Files.createDirectories(store.directory());
      if (Files.exists(file) && Files.size(file) > 65536)
        Files.move(
            file, file.resolveSibling("runtime-previous.log"), StandardCopyOption.REPLACE_EXISTING);
      Files.writeString(
          file,
          java.time.LocalDateTime.now() + " · " + message + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      LOG.warn("Could not write SkyblockFlow runtime log", e);
    }
  }

  public void notifyGeneral(String source, String title, String subtitle, SoundSettings sound) {
    if (!source.equals("Timer") && !source.equals("Daily claims") && !alertsAllowed()) return;
    AlarmKind kind = alarmKind(source);
    Feature feature = kind.feature;
    if (!store.config.enabled(feature)) return;
    if (!presentAlarm(kind, title, subtitle, sound)) return;
    event(source + " · " + title + " · " + subtitle);
  }

  private boolean presentAlarm(
      AlarmKind kind, String title, String subtitle, SoundSettings original) {
    var style = store.config.alarmStyles.get(kind);
    if (style != null && !style.enabled) return false;
    String key = AlarmPresentation.key(original);
    if (!key.isEmpty()) style = store.config.namedAlarmStyles.getOrDefault(key, style);
    if (style != null && !style.enabled) return false;
    var hud = Minecraft.getInstance().gui.hud;
    if (store.config.visible(kind.feature)) {
      String t = style == null ? title : style.render(style.title, title, subtitle);
      String sub = style == null ? subtitle : style.render(style.subtitle, title, subtitle);
      if (!t.isBlank() || !sub.isBlank()) {
        hud.setTimes(5, 60, 15);
        hud.setSubtitle(dev.asarix.skyblockflow.ui.DisplayText.literal(sub));
        hud.setTitle(dev.asarix.skyblockflow.ui.DisplayText.literal(t));
      }
      if (style != null && !style.actionBar.isBlank())
        hud.setOverlayMessage(
            dev.asarix.skyblockflow.ui.DisplayText.literal(style.render(style.actionBar, title, subtitle)), false);
    }
    sounds.playAlert(style != null && style.customSound ? style : original, kind);
    return true;
  }

  public static AlarmKind alarmKind(String source) {
    return switch (source) {
      case "Pets" -> AlarmKind.PETS;
      case "Leaderboard" -> AlarmKind.RANK;
      case "Rocket oxygen" -> AlarmKind.ROCKET;
      case "Ancient debris" -> AlarmKind.ANCIENT_DEBRIS;
      case "Timer" -> AlarmKind.TIMER;
      case "Daily claims" -> AlarmKind.DAILY_CLAIMS;
      default -> AlarmKind.COORDINATES;
    };
  }

  public void commandSent(String command) {
    if (closest != null && closest.internalCommand()) return;
    String recent = "/" + command.replaceFirst("^/", "");
    recentCommands.remove(recent);
    recentCommands.addFirst(recent);
    while (recentCommands.size() > 5) recentCommands.removeLast();
    if (closest != null) closest.commandSent(command);
  }

  public String serverKey() {
    var mc = Minecraft.getInstance();
    return mc.getCurrentServer() != null
        ? mc.getCurrentServer().ip
        : mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null
            ? "local:" + mc.getSingleplayerServer().getWorldData().getLevelName()
            : "local";
  }

  public void loadServerProfile() {
    try {
      String key =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(serverKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              .substring(0, 16);
      var path = store.directory().resolve("profiles").resolve(key + ".json");
      java.nio.file.Files.createDirectories(path.getParent());
      if (java.nio.file.Files.exists(path))
        store.config = store.decode(java.nio.file.Files.readString(path));
      else java.nio.file.Files.writeString(path, ConfigStore.GSON.toJson(store.config));
      store.config.autoServerProfiles = true;
      store.config.profileName = key;
      reloadPatterns();
      event("Loaded server profile " + key);
    } catch (Exception e) {
      error("Profile: " + e.getMessage());
    }
  }

  public void openEditor() {
    Minecraft.getInstance().gui.setScreen(new FeatureBrowserScreen(this));
  }

  public boolean isControlKey(int key) {
    if (key == GLFW.GLFW_KEY_F11) return true;
    for (KeyMapping control : new KeyMapping[] {editorKey, measureKey, measureAllKey, advanceKey})
      if (control != null
          && ((dev.asarix.skyblockflow.mixin.KeyMappingAccess) control)
              .skyblockflow$key()
              .equals(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(key)))
        return true;
    return false;
  }

  public boolean secretKey(long window, int key, boolean initialPress) {
    Minecraft mc = Minecraft.getInstance();
    if (key != GLFW.GLFW_KEY_F11 || store == null || window != mc.getWindow().handle())
      return false;
    if (initialPress) {
      store.config.hideFeatures = !store.config.hideFeatures;
      save();
      if (mc.gui.screen() instanceof FeatureBrowserScreen
          || mc.gui.screen() instanceof FeatureSettingsScreen)
        mc.gui.setScreen(new FeatureBrowserScreen(this));
      if (mc.gui.screen()
          instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsScreen screen) {
        var parent =
            ((dev.asarix.skyblockflow.mixin.OptionsScreenAccess) screen).skyblockflow$parent();
        mc.gui.setScreen(
            new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(
                parent, mc.options));
      }
    }
    return true;
  }

  public boolean commandKey(long window, int key, boolean initialPress) {
    Minecraft mc = Minecraft.getInstance();
    if (store == null
        || mc.gui.screen() != null
        || mc.player == null
        || mc.level == null
        || !mc.player.isAlive()
        || mc.isPaused()
        || !mc.isWindowActive()
        || window != mc.getWindow().handle()) return false;
    if (isControlKey(key) || !store.config.enabled(Feature.COMMANDS)) return false;
    for (CommandBinding binding : store.config.commandBindings) {
      if (!binding.enabled || binding.keyCode != key) continue;
      if (initialPress) {
        try {
          mc.player.connection.sendCommand(ConfigStore.commandText(binding.command));
        } catch (Exception e) {
          error("Keybind " + binding.name + ": " + e.getMessage());
        }
      }
      return true;
    }
    return false;
  }

  public boolean visibleKey(KeyMapping key) {
    if (key == measureKey || key == measureAllKey || key == advanceKey)
      return store.config.visible(Feature.LEADERBOARDS);
    if (key == editorKey) return !store.config.hideFeatures;
    return true;
  }

  public List<String> keyConflicts() {
    Map<Integer, List<String>> keys = new TreeMap<>();
    java.util.function.BiConsumer<Integer, String> add =
        (code, name) -> {
          if (code >= 0) keys.computeIfAbsent(code, k -> new ArrayList<>()).add(name);
        };
    for (var mapping : Minecraft.getInstance().options.keyMappings) {
      var key = ((dev.asarix.skyblockflow.mixin.KeyMappingAccess) mapping).skyblockflow$key();
      if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM)
        add.accept(key.getValue(), Component.translatable(mapping.getName()).getString());
    }
    add.accept(GLFW.GLFW_KEY_F11, "SkyblockFlow visibility (fixed)");
    if (store.config.enabled(Feature.COMMANDS))
      for (var binding : store.config.commandBindings)
        if (binding.enabled) add.accept(binding.keyCode, "Command: " + binding.name);
    return keys.entrySet().stream()
        .filter(e -> e.getValue().size() > 1)
        .map(
            e ->
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                        .getOrCreate(e.getKey())
                        .getDisplayName()
                        .getString()
                    + ": "
                    + String.join(" / ", e.getValue()))
        .toList();
  }

  private void tick(Minecraft mc) {
    if (mc.player != null && mc.level == motionLevel) {
      motionX = mc.player.getX() - sampledX;
      motionY = mc.player.getY() - sampledY;
      motionZ = mc.player.getZ() - sampledZ;
    } else {
      motionX = motionY = motionZ = 0;
      motionLevel = mc.level;
    }
    if (mc.player != null) {
      sampledX = mc.player.getX();
      sampledY = mc.player.getY();
      sampledZ = mc.player.getZ();
    }
    rocket.tick(mc);
    while (editorKey.consumeClick()) openEditor();
    while (measureKey.consumeClick()) if (mc.gui.screen() == null) closest.measureCurrent();
    while (measureAllKey.consumeClick()) if (mc.gui.screen() == null) closest.measureAll();
    while (advanceKey.consumeClick()) if (mc.gui.screen() == null) closest.advance();
    jobs.tick();
    pets.tick();
    closest.tick();
    timers.tick();
    celebrations.tick();
    shared.tick();
    playerNames.tick(mc);
    if (coordinateLevel != mc.level || mc.player == null || !mc.player.isAlive()) {
      coordinates.reset();
      coordinateLevel = mc.level;
    }
    if (mc.player != null && mc.level != null && mc.player.isAlive() && !mc.isPaused())
      coordinates.tick(
          store.config.coordinateAlerts,
          mc.level.dimension().identifier().toString(),
          mc.player.getX(),
          mc.player.getY(),
          mc.player.getZ(),
          alert -> {
            if (store.config.enabled(Feature.COORDINATES) && alertsAllowed())
              notifyGeneral("Coordinates", alert.name, "Entered configured area", alert);
          });
  }
}
