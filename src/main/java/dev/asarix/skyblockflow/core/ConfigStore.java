package dev.asarix.skyblockflow.core;

import static dev.asarix.skyblockflow.core.Models.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ConfigStore {
  public static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private final Path directory;
  private final Path file;
  public Config config;
  public String loadWarning = "";

  public ConfigStore(Path directory) {
    this.directory = directory;
    this.file = directory.resolve("skyblockflow.json");
  }

  public Path sounds() {
    return directory.resolve("sounds");
  }

  public Path directory() {
    return directory;
  }

  public static void validateSound(SoundSettings s) {
    if (s == null
        || s.soundKind == null
        || s.sound == null
        || s.sound.isBlank()
        || !Float.isFinite(s.volume)
        || s.volume < 0
        || s.volume > 1
        || !Float.isFinite(s.pitch)
        || s.pitch < .5
        || s.pitch > 2) throw new IllegalArgumentException("Invalid sound, volume or pitch");
  }

  public static void validateTimer(TimerSettings s) {
    validateSound(s);
    if (s.id == null
        || s.name == null
        || s.name.isBlank()
        || s.name.length() > 100
        || s.durationMs < 1000
        || s.durationMs > 604800000)
      throw new IllegalArgumentException("Timer must last 1 second to 7 days");
  }

  public static void validateClosest(ClosestSettings s) {
    if (s == null
        || s.maxAgeMs < 10000 || s.maxAgeMs > 86400000
        || s.commandGapMs < 500 || s.commandGapMs > 60000
        || s.responseTimeoutMs < 2000
        || s.responseTimeoutMs > 60000
        || s.maxPages < 1
        || s.maxPages > 100)
      throw new IllegalArgumentException("Invalid command gap, response timeout or page limit");
  }

  public static void validateCelebration(CelebrationSettings s) {
    validateSound(s);
    validateCelebrationEdit(s.edit);
    if (!Double.isFinite(s.cardX)
        || !Double.isFinite(s.cardY)
        || s.cardX < 0
        || s.cardX > 1
        || s.cardY < 0
        || s.cardY > 1)
      throw new IllegalArgumentException("Celebration card position must stay on screen");
    if (s.text == null
        || s.subtitle == null
        || s.image == null
        || s.text.length() > 200
        || s.subtitle.length() > 200
        || s.durationMs < 1000
        || s.durationMs > 7200000)
      throw new IllegalArgumentException("Celebration duration must be 1–7200 seconds");
  }

  public static void validateCelebrationEdit(CelebrationEditSettings s) {
    if (s == null) throw new IllegalArgumentException("Missing celebration edit settings");
    if (s.bassDetectionMode == null) s.bassDetectionMode = BassDetectionMode.ALL_BASS;
    if (s.analysisHz != 50 && s.analysisHz != 100 && s.analysisHz != 200
        || s.syncOffsetMs < -2000
        || s.syncOffsetMs > 2000
        || s.punchMs < 20
        || s.punchMs > 2000)
      throw new IllegalArgumentException(
          "Analysis rate: 50, 100 or 200; sync: -2000–2000 ms; punch: 20–2000 ms");
    editRange(s.punchStrength, 0, 1, "Hit punch strength");
    editRange(s.cameraMinDistance, 2, 16, "Minimum camera distance");
    editRange(s.cameraMaxDistance, 2, 16, "Maximum camera distance");
    editRange(s.cameraMinElevation, -20, 75, "Minimum camera elevation");
    editRange(s.cameraMaxElevation, -20, 75, "Maximum camera elevation");
    editRange(s.cameraOrbitSpeed, -60, 60, "Camera orbit speed");
    editRange(s.cameraIntroDistance, 8, 128, "Intro distance");
    editRange(s.cameraIntroCurve, 1, 8, "Intro exponential curve");
    if (s.cameraIntroMs < 200 || s.cameraIntroMs > 30000)
      throw new IllegalArgumentException("Intro duration must be 0.2–30 seconds");
    if (s.cameraMinDistance > s.cameraMaxDistance
        || s.cameraMinElevation > s.cameraMaxElevation
        || s.cameraBlendMs < 0
        || s.cameraBlendMs > 2000)
      throw new IllegalArgumentException("Invalid camera range or transition time");
    editRange(s.volumeMultiplier, 0, 4, "Edit volume multiplier");
    editRange(s.zoom, 1, 3, "Edit zoom");
    editRange(s.shakeDegrees, 0, 5, "Shake degrees");
    editRange(s.rollDegrees, 0, 5, "Roll degrees");
    editRange(s.shakeHz, .1f, 20, "Shake speed");
    editRange(s.bassLowHz, 10, 250, "Bass low frequency");
    editRange(s.bassHighHz, 30, 500, "Bass high frequency");
    editRange(s.bassThreshold, .001f, 1, "Bass delta threshold");
    editRange(s.motionMultiplier, 1, 4, "Motion multiplier");
    editRange(s.tintStrength, 0, .5f, "Tint strength");
    editRange(s.vignetteStrength, 0, .8f, "Vignette strength");
    editRange(s.pulseStrength, 0, .3f, "Light pulse strength");
    editRange(s.letterboxHeight, 0, .25f, "Letterbox height");
    if (s.bassLowHz >= s.bassHighHz
        || s.beatCooldownMs < 20
        || s.beatCooldownMs > 5000
        || s.attackMs < 0
        || s.attackMs > 10000
        || s.releaseMs < 0
        || s.releaseMs > 7200000
        || s.fadeInMs < 0
        || s.fadeInMs > 7200000
        || s.fadeOutMs < 0
        || s.fadeOutMs > 7200000
        || s.tintColor < 0
        || s.tintColor > 0xffffff)
      throw new IllegalArgumentException("Invalid edit frequency band, timing or color");
  }

  private static void editRange(float value, float min, float max, String label) {
    if (!Float.isFinite(value) || value < min || value > max)
      throw new IllegalArgumentException(label + ": enter a number from " + min + " to " + max);
  }

  public static void validateJobs(JobSettings s) {
    if (s == null
        || s.timeoutMs < 1000
        || s.timeoutMs > 86400000
        || s.rateWindowMs != 0 && s.rateWindowMs < 1000
        || s.rateWindowMs > 3600000
        || s.colors == null
        || s.pinnedJob == null
        || !s.pinnedJob.isEmpty() && !JobXp.NAMES.containsKey(s.pinnedJob)
        || !Double.isFinite(s.x)
        || !Double.isFinite(s.y)
        || s.x < 0
        || s.x > 1
        || s.y < 0
        || s.y > 1
        || !Float.isFinite(s.scale)
        || s.scale < .5
        || s.scale > 3
        || !Float.isFinite(s.opacity)
        || s.opacity < 0
        || s.opacity > 1) throw new IllegalArgumentException("Invalid job tracker settings");
    if (s.unhideAfterIdleMs < 1000 || s.unhideAfterIdleMs > 3600000)
      throw new IllegalArgumentException("Overlay resume gap must be 1–3600 seconds");
    validateClosest(s.closest);
    validateCelebration(s.celebration);
  }

  public static void validatePets(PetSettings s) {
    if (s == null
        || s.colors == null
        || s.warningXp == null
        || s.warningXp.size() > 20
        || !Double.isFinite(s.x + s.y)
        || s.x < 0
        || s.x > 1
        || s.y < 0
        || s.y > 1
        || !Float.isFinite(s.scale)
        || s.scale < .5
        || s.scale > 3
        || !Float.isFinite(s.opacity)
        || s.opacity < 0
        || s.opacity > 1
        || s.warningCooldownMs < 1000
        || s.warningCooldownMs > 86400000)
      throw new IllegalArgumentException("Invalid pet tracker settings");
    for (var e : s.warningXp.entrySet())
      if (!JobXp.NAMES.containsKey(e.getKey())
          || e.getValue() == null
          || !Double.isFinite(e.getValue())
          || e.getValue() < 0
          || e.getValue() > 1e12)
        throw new IllegalArgumentException("Invalid pet warning threshold");
  }

  public void load() throws IOException {
    Files.createDirectories(sounds());
    loadWarning = "";
    if (!Files.exists(file)) {
      config = FactoryDefaults.load(this);
      save();
      return;
    }
    String json = Files.readString(file, StandardCharsets.UTF_8);
    try {
      config = decode(json);
    } catch (RuntimeException e) {
      loadWarning = "Configuration could not be read: " + e.getMessage();
      config = Config.defaults();
    }
    if (!loadWarning.isEmpty()
        || JsonParser.parseString(GSON.toJson(config)).getAsJsonObject().get("version").getAsInt()
            != versionOf(json)) {
      Path backup =
          directory.resolve(
              (loadWarning.isEmpty() ? "skyblockflow-before-upgrade-" : "skyblockflow-invalid-")
                  + System.currentTimeMillis()
                  + ".json");
      Files.copy(file, backup);
      loadWarning +=
          (loadWarning.isEmpty() ? "" : " · ") + "Original saved as " + backup.getFileName();
    }
    save();
  }

  private static int versionOf(String json) {
    try {
      return JsonParser.parseString(json).getAsJsonObject().get("version").getAsInt();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  public Config decode(String json) {
    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
    int version = root.has("version") ? root.get("version").getAsInt() : 1;
    if (version < 1 || version > 2)
      throw new IllegalArgumentException("Unsupported configuration version " + version);
    Config parsed = new Config();
    // Decode each section independently so a broken preset cannot discard other settings.
    for (var field : Config.class.getFields()) {
      if (!root.has(field.getName()) || field.getName().equals("version")) continue;
      if (List.class.isAssignableFrom(field.getType())) continue;
      try {
        Object value = GSON.fromJson(root.get(field.getName()), field.getGenericType());
        if (value == null) throw new IllegalArgumentException("empty section");
        field.set(parsed, value);
      } catch (Exception e) {
        warn("Reset " + field.getName());
      }
    }
    parsed.alerts =
        entries(
            root,
            "alerts",
            AlertRule.class,
            a -> {
              if (a.id == null
                  || a.name == null
                  || a.title == null
                  || a.subtitle == null
                  || a.soundKind == null
                  || a.sound == null
                  || !Float.isFinite(a.volume)
                  || a.volume < 0
                  || a.volume > 1
                  || !Float.isFinite(a.pitch)
                  || a.pitch < .5
                  || a.pitch > 2) throw new IllegalArgumentException();
              new AlertPattern(a);
            });
    parsed.commandBindings =
        entries(
            root,
            "commandBindings",
            CommandBinding.class,
            a -> validateCommandBinding(a, List.of(a)));
    parsed.timers = entries(root, "timers", TimerSettings.class, ConfigStore::validateTimer);
    parsed.coordinateAlerts =
        entries(
            root, "coordinateAlerts", CoordinateAlert.class, ConfigStore::validateCoordinateAlert);
    if (parsed.features == null) parsed.features = new java.util.LinkedHashMap<>();
    parsed.features.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
    for (Feature f : Feature.values()) parsed.feature(f);
    if (parsed.hud == null) parsed.hud = new HudSettings();
    parsed.hud.normalize();
    if (parsed.tools == null) parsed.tools = new ToolSettings();
    parsed.tools.normalize();
    if (parsed.spawners == null) parsed.spawners = new SpawnerSettings();
    parsed.spawners.normalize();
    parsed.connectionGraceMs = Math.clamp(parsed.connectionGraceMs, 0, 3600000);
    try {
      validateJobs(parsed.jobs);
    } catch (Exception e) {
      parsed.jobs = new JobSettings();
      warn("Reset job tracker settings");
    }
    try {
      validatePets(parsed.pets);
    } catch (Exception e) {
      parsed.pets = new PetSettings();
      warn("Reset pet tracker settings");
    }
    if (parsed.profileName == null) parsed.profileName = "default";
    if (!root.has("hud")) {
      parsed.hud.inheritOld(HudSettings.Panel.JOBS, parsed.jobs.colors, parsed.jobs.opacity, .82f);
      parsed.hud.inheritOld(HudSettings.Panel.PETS, parsed.pets.colors, parsed.pets.opacity, .82f);
    }
    if (parsed.alarmStyles == null) parsed.alarmStyles = new java.util.LinkedHashMap<>();
    if (parsed.namedAlarmStyles == null) parsed.namedAlarmStyles = new java.util.LinkedHashMap<>();
    parsed.alarmStyles.entrySet().removeIf(e -> e.getKey() == null || invalidAlarm(e.getValue()));
    parsed
        .namedAlarmStyles
        .entrySet()
        .removeIf(e -> e.getKey() == null || invalidAlarm(e.getValue()));
    // Version 1 standalone profiles used top-level toggles rather than the feature map.
    if (root.has("enabled"))
      parsed.feature(Feature.JOBS).enabled = root.get("enabled").getAsBoolean();
    if (root.has("petsEnabled"))
      parsed.feature(Feature.PETS).enabled = root.get("petsEnabled").getAsBoolean();
    if (root.has("overlaysVisible"))
      parsed.hideFeatures = !root.get("overlaysVisible").getAsBoolean();
    DefaultChatAlerts.upgrade(parsed);
    parsed.version = 2;
    return parsed;
  }

  private void warn(String text) {
    loadWarning += (loadWarning.isEmpty() ? "" : "; ") + text;
  }

  private static boolean invalidAlarm(AlarmPresentation a) {

    if (a == null || a.title == null || a.subtitle == null || a.actionBar == null) return true;
    try {
      validateSound(a);
      return false;
    } catch (RuntimeException ignored) {
      return true;
    }
  }

  private <T> List<T> entries(JsonObject root, String name, Class<T> type, Consumer<T> validate) {
    List<T> values = new ArrayList<>();
    if (!root.has(name)) return values;
    try {
      for (JsonElement item : root.getAsJsonArray(name)) {
        try {
          T value = GSON.fromJson(item, type);
          validate.accept(value);
          values.add(value);
        } catch (Exception e) {
          warn("Skipped invalid " + name + " entry");
        }
      }
    } catch (Exception e) {
      warn("Could not read " + name);
    }
    return values;
  }

  public Path backup() throws IOException {
    Path folder = directory.resolve("backups");
    Files.createDirectories(folder);
    Path path = folder.resolve("skyblockflow-" + System.currentTimeMillis() + ".json");
    Files.writeString(path, GSON.toJson(config));
    return path;
  }

  public void restore(Path source) throws IOException {
    String warning = loadWarning;
    loadWarning = "";
    Config restored = decode(Files.readString(source));
    if (!loadWarning.isEmpty()) {
      String error = loadWarning;
      loadWarning = warning;
      throw new IOException("Import rejected: " + error);
    }
    backup();
    config = restored;
    save();
  }

  public void save() throws IOException {
    Files.createDirectories(directory);
    Path previous = directory.resolve("skyblockflow.previous.json");
    if (Files.exists(file)
        && (!Files.exists(previous)
            || System.currentTimeMillis() - Files.getLastModifiedTime(previous).toMillis()
                > 300000)) Files.copy(file, previous, StandardCopyOption.REPLACE_EXISTING);
    Path tmp = directory.resolve("skyblockflow.json.tmp");
    Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
    try {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public static void validateCommandBinding(
      CommandBinding binding, java.util.List<CommandBinding> all) {
    if (binding == null || binding.id == null || binding.name == null || binding.name.isBlank())
      throw new IllegalArgumentException("Enter a keybind name");
    if (binding.keyCode < 32 || binding.keyCode > 348)
      throw new IllegalArgumentException("Choose a keyboard key");
    commandText(binding.command);
    for (CommandBinding other : all) {
      if (other == null) throw new IllegalArgumentException("Incomplete keybind");
      if (other != binding
          && !binding.id.equals(other.id)
          && binding.enabled
          && other.enabled
          && other.keyCode == binding.keyCode)
        throw new IllegalArgumentException("This key already has an enabled command keybind");
    }
  }

  public static void validateCoordinateAlert(CoordinateAlert alert) {
    if (alert == null
        || alert.id == null
        || alert.name == null
        || alert.name.isBlank()
        || alert.region == null
        || alert.dimension == null)
      throw new IllegalArgumentException("Incomplete coordinate sound");
    if (!alert.dimension.isEmpty() && !alert.dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
      throw new IllegalArgumentException(
          "Dimension must be an ID such as minecraft:overworld, or blank");
    Region r = alert.region;
    for (int coordinate : new int[] {r.x1, r.y1, r.z1, r.x2, r.y2, r.z2})
      if (coordinate < -30000000 || coordinate > 30000000)
        throw new IllegalArgumentException("Coordinates must be between -30000000 and 30000000");
    if (alert.soundKind == null
        || alert.sound == null
        || alert.sound.isBlank()
        || !Float.isFinite(alert.volume)
        || alert.volume < 0
        || alert.volume > 1
        || !Float.isFinite(alert.pitch)
        || alert.pitch < .5f
        || alert.pitch > 2)
      throw new IllegalArgumentException("Choose a sound, volume 0–1 and pitch .5–2");
  }

  public static String commandText(String text) {
    if (text == null) throw new IllegalArgumentException("Enter a command");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 32 || c == 127 || c == '§')
        throw new IllegalArgumentException(
            "Command must be one line without control or formatting characters");
    }
    String command = text.trim();
    if (command.startsWith("/")) command = command.substring(1).trim();
    if (command.isEmpty()) throw new IllegalArgumentException("Enter a command");
    if (command.length() > 256)
      throw new IllegalArgumentException(
          "Command must be at most 256 characters, excluding the first /");
    return command;
  }

  public static <T> T copy(T value, Class<T> type) {
    return GSON.fromJson(GSON.toJson(value), type);
  }
}
