package dev.asarix.skyblockflow.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Plain data objects, also used for the human-readable JSON configuration. */
public final class Models {
  private Models() {}

  public enum MatchMode {
    TEMPLATE,
    REGEX
  }

  public enum SoundKind {
    VANILLA,
    MP3,
    SILENT
  }

  public enum Feature {
    CHAT,
    COORDINATES,
    COMMANDS,
    OVERLAY,
    JOBS,
    PETS,
    TIMERS,
    SHARED_EDITS,
    WAYPOINTS,
    SPAWNERS,
    LEADERBOARDS,
    CALCULATOR,
    SHOP_VALUES,
    CLOCK,
    MINERALS,
    ARTISAN,
    ENERGY,
    ESPORTS,
    ALARMS,
    COMMUNITY_NAMES,
    ISLAND_FLIGHT,
    RECIPES
  }

  public static class FeatureOptions {
    public boolean enabled = true;
    public boolean hideWithF11 = true;
  }

  public enum AlarmKind {
    CHAT("Chat alerts", Feature.CHAT),
    COORDINATES("Coordinates", Feature.COORDINATES),
    PETS("Hungry pet", Feature.PETS),
    TIMER("Timers", Feature.TIMERS),
    DAILY_CLAIMS("Daily claims", Feature.ALARMS),
    ISLAND_FLIGHT("Island flight setup", Feature.ISLAND_FLIGHT),
    RANK("Leaderboard rank change", Feature.LEADERBOARDS),
    LEVEL_UP("Level up", Feature.JOBS),
    ANCIENT_DEBRIS("Ancient debris", Feature.ALARMS),
    ROCKET("Rocket oxygen", Feature.ALARMS);
    public final String label;
    public final Feature feature;

    AlarmKind(String label, Feature feature) {
      this.label = label;
      this.feature = feature;
    }
  }

  public static class TimerSettings extends SoundSettings {
    public String id = UUID.randomUUID().toString(), name = "Timer";
    public long durationMs = 300000;
    public boolean loop = false;
  }

  public static class ClosestSettings {
    public boolean hideOutput = true;
    public boolean islandBrowseHint = true;
    public String islandPattern = IslandBoard.DEFAULT_PATTERN;
    public long maxAgeMs = 300000;
    public long commandGapMs = 2000;
    public long responseTimeoutMs = 10000;
    public int maxPages = 30;
  }

  /** Music-video effects, applied only during a celebration; normal play is unchanged. */
  public static class CelebrationEditSettings {
    public boolean enabled = false;
    public boolean cameraCuts = true;
    public boolean cameraReturn = true;
    public boolean cameraIntro = false, cameraIntroAccelerates = true;
    public long cameraIntroMs = 2500;
    public float cameraIntroDistance = 48, cameraIntroCurve = 3;
    public float cameraMinDistance = 4.5f, cameraMaxDistance = 6.5f;
    public float cameraMinElevation = 5, cameraMaxElevation = 35, cameraOrbitSpeed = 8;
    public long cameraBlendMs = 0;
    public float volumeMultiplier = 2, zoom = 1.5f;
    public float shakeDegrees = .35f, shakeHz = 7, rollDegrees = .2f;
    public boolean bassReactive = true;
    public BassDetectionMode bassDetectionMode = BassDetectionMode.ALL_BASS;
    public int analysisHz = 100;
    public long syncOffsetMs = 0, punchMs = 120;
    public boolean punchEnabled = true;
    public float punchStrength = .35f;
    public float bassLowHz = 35, bassHighHz = 180, bassThreshold = .035f;
    public long beatCooldownMs = 180, attackMs = 500, releaseMs = 3000;
    public float motionMultiplier = 2;
    public long fadeInMs = 300, fadeOutMs = 650;
    public boolean beatZoom = true, colorGrade = true, vignette = true;
    public boolean lightPulse = false, letterbox = false;
    public float tintStrength = .08f, vignetteStrength = .22f;
    public int tintColor = 0x9bb6ff;
    public float pulseStrength = .06f, letterboxHeight = .08f;
  }

  public static class CelebrationSettings extends SoundSettings {
    // Local editor identity; saved preset snapshots do not retain this reference.
    public String presetId = "";
    public boolean enabled = true, animated = true, fireworks = true, hideOverlays = true;
    public boolean hideMinecraftHud = true, showCard = true;
    public boolean customCardPosition = false;
    public double cardX = .5, cardY = .1;
    public long durationMs = 6000;
    public String text = "LEVEL {level} · {job}",
        subtitle = "One level closer to the stars!",
        image = "";
    public CelebrationEditSettings edit = new CelebrationEditSettings();

    public CelebrationSettings() {
      sound = "minecraft:ui.toast.challenge_complete";
    }
  }

  public static class JobSettings {
    public long timeoutMs = 300000, rateWindowMs = 300000;
    public boolean experimentalXp = true;
    public boolean hideXpBossBar, vanillaJobsStats;
    public boolean hideJobsHeader = true, showIdleTimer = true;
    public long unhideAfterIdleMs = 10000;
    public ClosestSettings closest = new ClosestSettings();
    public CelebrationSettings celebration = new CelebrationSettings();
    public boolean showClosestXp = true, showClosestTime = true;
    public OverlayColors colors = new OverlayColors();
    public boolean showOverlay = true, onlyActive = false;
    public boolean showProgress = true,
        showSession = true,
        showPerMinute = true,
        showPerHour = true,
        showNextLevel = true,
        showMilestone = true,
        showLevel200 = true;
    public String pinnedJob = "";
    public double x = .98, y = .18;
    public float scale = 1, opacity = .82f;
  }

  public static class OverlayColors {
    public int background = 0x111c29, accent = 0x79dfbe, text = 0xe7edf5, muted = 0x9baabd;
  }

  public static class PetSettings {
    public boolean showOverlay = true, hideNoPet = false, hideNoData = false, warnHungry = true;
    public double x = .98, y = .72;
    public float scale = 1, opacity = .82f;
    public OverlayColors colors = new OverlayColors();
    public long warningCooldownMs = 60000;
    public Map<String, Double> warningXp = new LinkedHashMap<>();

    public double threshold(String job) {
      return warningXp.getOrDefault(job, 1d);
    }
  }

  public static class Config {
    public boolean darkPlayerProfiles;
    public int version = 2;
    public int defaultAlertsVersion;
    public long connectionGraceMs = 60000;
    public Map<Feature, FeatureOptions> features = new LinkedHashMap<>();
    public JobSettings jobs = new JobSettings();
    public List<TimerSettings> timers = new ArrayList<>();
    public boolean copyChatOnClick = true, compactChat = true, chatTabs = true;
    public Map<String, AlarmPresentation> namedAlarmStyles = new LinkedHashMap<>();
    public Map<AlarmKind, AlarmPresentation> alarmStyles = new LinkedHashMap<>();
    public HudSettings hud = new HudSettings();
    public SpawnerSettings spawners = new SpawnerSettings();
    public ToolSettings tools = new ToolSettings();
    public PetSettings pets = new PetSettings();
    public boolean autoServerProfiles = false;
    public String profileName = "default";

    public FeatureOptions feature(Feature f) {
      return features.computeIfAbsent(f, k -> new FeatureOptions());
    }

    public boolean visible(Feature f) {
      return !hideFeatures || !feature(f).hideWithF11;
    }

    public boolean enabled(Feature f) {
      return feature(f).enabled;
    }

    public List<AlertRule> alerts = new ArrayList<>();
    public List<CommandBinding> commandBindings = new ArrayList<>();
    public List<CoordinateAlert> coordinateAlerts = new ArrayList<>();
    public boolean hideFeatures = false;

    public static Config defaults() {
      Config c = new Config();
      c.alerts = DefaultChatAlerts.create();
      c.defaultAlertsVersion = 1;
      return c;
    }
  }

  public static class AlarmPresentation extends SoundSettings {
    public boolean enabled = true, customSound;
    public String title = "{title}", subtitle = "{subtitle}", actionBar = "";
    private static final java.util.regex.Pattern VARIABLES =
        java.util.regex.Pattern.compile("\\{(title|subtitle)\\}");

    public static String key(SoundSettings s) {
      return switch (s) {
        case AlertRule a -> "chat/" + a.id;
        case CoordinateAlert a -> "area/" + a.id;
        case TimerSettings a -> "timer/" + a.id;
        default -> "";
      };
    }

    public String render(String template, String title, String subtitle) {
      return VARIABLES
          .matcher(template)
          .replaceAll(
              m ->
                  java.util.regex.Matcher.quoteReplacement(
                      m.group(1).equals("title") ? title : subtitle));
    }
  }

  public static class SoundSettings {
    public SoundKind soundKind = SoundKind.VANILLA;
    public String sound = "minecraft:block.note_block.pling";
    public float volume = 0.8f;
    public float pitch = 1.0f;
  }

  public static class CommandBinding {
    public String id = UUID.randomUUID().toString();
    public String name = "New keybind";
    public boolean enabled = true;
    public int keyCode = -1;
    public String command = "";
  }

  public static class CoordinateAlert extends SoundSettings {
    public String id = UUID.randomUUID().toString();
    public String name = "New coordinate sound";
    public boolean enabled = true;
    public Region region = new Region();
    public String dimension = "";
  }

  public static class AlertRule extends SoundSettings {
    public String id = UUID.randomUUID().toString();
    public String name = "New alert";
    public boolean enabled = true;
    public MatchMode mode = MatchMode.TEMPLATE;
    public String pattern = "Salut [player]";
    public boolean caseSensitive = false;
    public boolean matchFullChat = false;
    public String title = "[player] a été salué";
    public String subtitle = "From [sender]";
    public long cooldownMs = 1000;
  }

  public static class Region {
    public int x1, y1, z1, x2, y2, z2;

    public boolean contains(double x, double y, double z) {
      return Math.floor(x) >= Math.min(x1, x2)
          && Math.floor(x) <= Math.max(x1, x2)
          && Math.floor(y) >= Math.min(y1, y2)
          && Math.floor(y) <= Math.max(y1, y2)
          && Math.floor(z) >= Math.min(z1, z2)
          && Math.floor(z) <= Math.max(z1, z2);
    }

    public String summary() {
      if (x1 == x2 && y1 == y2 && z1 == z2) return x1 + ", " + y1 + ", " + z1;
      return x1 + "," + y1 + "," + z1 + " → " + x2 + "," + y2 + "," + z2;
    }
  }
}
