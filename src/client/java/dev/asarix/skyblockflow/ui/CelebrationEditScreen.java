package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.screens.Screen;

/** Nested edit controls persist when closed. */
public final class CelebrationEditScreen extends FormScreen {
  private final CelebrationSettings target;
  private final CelebrationSettings previewSettings;
  private CelebrationEditSettings draft;
  private SongAnalysis analysis;
  private CompletableFuture<SongAnalysis> analysisTask;
  private String analysisKey = "",
      wantedKey = "",
      analysisStatus = "Choose an MP3 to analyze its hits.";
  private long changedAt;
  private boolean applyAfterAnalysis;
  private String recommendationKey = "";

  private int analysisRate() {
    float rate = Float.parseFloat(value("precision"));
    if (rate != 50 && rate != 100 && rate != 200)
      throw new IllegalArgumentException("Choose 50, 100 or 200 measurements per second");
    return (int) rate;
  }

  private CelebrationEditSettings analysisSettings() {
    var settings = ConfigStore.copy(draft, CelebrationEditSettings.class);
    settings.analysisHz = analysisRate();
    settings.bassLowHz = Float.parseFloat(value("low"));
    settings.bassHighHz = Float.parseFloat(value("high"));
    settings.bassThreshold = Float.parseFloat(value("threshold"));
    settings.beatCooldownMs = Math.round(Float.parseFloat(value("cooldown")) * 1000);
    ConfigStore.validateCelebrationEdit(settings);
    return settings;
  }

  private void requestAnalysis(boolean force) {
    if (target.soundKind != SoundKind.MP3) return;
    try {
      var settings = analysisSettings();
      String key =
          settings.bassDetectionMode
              + "|"
              + settings.analysisHz
              + "|"
              + settings.bassLowHz
              + "|"
              + settings.bassHighHz
              + "|"
              + settings.bassThreshold
              + "|"
              + settings.beatCooldownMs;
      long now = System.currentTimeMillis();
      if (!key.equals(wantedKey)) {
        if (!recommendationKey.isEmpty() && !recommendationKey.equals(key))
          applyAfterAnalysis = false;
        wantedKey = key;
        changedAt = now;
        analysis = null;
        analysisStatus = "Waiting for your bass settings...";
      }
      if (key.equals(analysisKey) || !force && now - changedAt < 500) return;
      analysisKey = key;
      analysis = null;
      analysisStatus = "Analyzing song in the background...";
      analysisTask = app.sounds.analyzeSong(target, settings);
      analysisTask.whenCompleteAsync(
          (result, error) ->
              minecraft.execute(
                  () -> {
                    if (!key.equals(analysisKey) || !key.equals(wantedKey)) return;
                    analysis = result;
                    if (error == null && applyAfterAnalysis && key.equals(recommendationKey)) {
                      applyAfterAnalysis = false;
                      applyValues();
                    }
                    analysisStatus =
                        error == null
                            ? result.hits().size()
                                + " hits · "
                                + SongAnalysisScreen.time(
                                    Math.round(result.durationMs() / target.pitch))
                                + " · longest gap "
                                + SongAnalysisScreen.time(
                                    Math.round(result.longestGapMs() / target.pitch))
                            : "Analysis failed: "
                                + (error.getCause() == null
                                    ? error.getMessage()
                                    : error.getCause().getMessage());
                    if (minecraft.gui.screen() == this) rebuildWidgets();
                  }));
    } catch (RuntimeException invalid) {
      analysisKey = "";
      analysis = null;
      analysisStatus = "Enter valid bass settings to update the analysis.";
    }
  }

  @Override
  public void tick() {
    super.tick();
    requestAnalysis(false);
  }

  @Override
  public void extractRenderState(
      net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float dt) {
    super.extractRenderState(g, mx, my, dt);
    if (onPage("Song")) {
      int y = formTop() - scroll;
      if (y >= formTop() && y < height - 42) {
        Ui.text(g, Ui.trim(analysisStatus, bodyW), bodyX, y, MUTED);
        if (inside(mx, my, bodyX, y, bodyW, 12))
          g.setTooltipForNextFrame(Ui.label(analysisStatus), mx, my);
      }
    }
  }

  @Override
  protected SongRecommendations.Value recommendation(String key) {
    long offset = draft.syncOffsetMs;
    try {
      offset = Math.round(Float.parseFloat(value("sync")));
    } catch (Exception ignored) {
    }
    return SongRecommendations.forKey(key, analysis, target.pitch, offset);
  }

  private void applyValues() {
    values.put("sync", "0");
    for (String key : java.util.List.copyOf(values.keySet())) {
      if (java.util.Set.of("low", "high", "threshold", "precision", "cooldown").contains(key))
        continue;
      var recommended = recommendation(key);
      if (recommended != null) values.put(key, recommended.text());
    }
  }

  private void applyRecommendations() {
    if (target.soundKind == SoundKind.MP3) {
      for (String key : java.util.List.of("low", "high", "threshold", "precision", "cooldown"))
        values.put(key, SongRecommendations.forKey(key, null, target.pitch, 0).text());
      analysisKey = "";
      requestAnalysis(true);
      recommendationKey = analysisKey;
      applyAfterAnalysis = true;
      notice = "Analyzing recommended detection settings before suggesting effect timings.";
    } else {
      applyValues();
      notice = "Starting values applied to this draft.";
    }
    rebuildWidgets();
  }

  public CelebrationEditScreen(SkyblockFlowClient app, Screen parent, CelebrationSettings target) {
    super(app, parent, "Celebration · edit effects");
    this.target = target;
    previewSettings = ConfigStore.copy(target, CelebrationSettings.class);
    draft = ConfigStore.copy(target.edit, CelebrationEditSettings.class);
  }

  @Override
  protected String saveLabel() {
    return "Apply effects";
  }

  @Override
  protected String savedNotice() {
    return "Effects saved";
  }

  @Override
  protected String description() {
    return "Hover * for a suggested value and its reason. Song analysis updates after bass edits.";
  }

  private void pair(String key, String caption, float value, int column, int y) {
    int w = (bodyW - 12) / 2;
    field(key, caption, "" + value, column * (w + 12), y, w);
  }

  private void seconds(String key, String label, long value, int column, int y) {
    pair(key, label, value / 1000f, column, y);
  }

  @Override
  protected void buildForm() {
    page("Motion");
    recommendedToggle(
        "Music-video edit mode",
        draft.enabled,
        0,
        () -> draft.enabled = !draft.enabled,
        true,
        () -> draft.enabled = true);
    pair("volume", "Song volume multiplier (0–4)", draft.volumeMultiplier, 0, 44);
    pair("zoom", "Default zoom (1–3)", draft.zoom, 1, 44);
    pair("shake", "Pitch/yaw shake (degrees, 0–5)", draft.shakeDegrees, 0, 96);
    pair("roll", "Roll shake (degrees, 0–5)", draft.rollDegrees, 1, 96);
    pair("speed", "Shake speed (Hz, 0.1–20)", draft.shakeHz, 0, 148);
    pair("boost", "Bass motion multiplier (1–4)", draft.motionMultiplier, 1, 148);
    recommendedToggle(
        "Zoom pulses with bass",
        draft.beatZoom,
        202,
        () -> draft.beatZoom = !draft.beatZoom,
        true,
        () -> draft.beatZoom = true);
    label("Bass multiplies shake and the zoom above 1×. HUD stays still.", 0, 238);
    action("Preview in game", 0, 266, bodyW, this::preview);
    action(
        "Restore edit defaults",
        0,
        300,
        bodyW,
        () -> {
          boolean enabled = draft.enabled;
          draft = dev.asarix.skyblockflow.core.FactoryDefaults.settings().jobs.celebration.edit;
          draft.enabled = enabled;
          values.clear();
          rebuildWidgets();
        });
    page("Bass");
    recommendedToggle(
        "React to bass hits",
        draft.bassReactive,
        0,
        () -> draft.bassReactive = !draft.bassReactive,
        true,
        () -> draft.bassReactive = true);
    pair("low", "Bass band start (Hz, 10–250)", draft.bassLowHz, 0, 44);
    pair("high", "Bass band end (Hz, 30–500)", draft.bassHighHz, 1, 44);
    pair("threshold", "Detection threshold (0.001–1)", draft.bassThreshold, 0, 96);
    seconds("cooldown", "Minimum hit spacing (seconds, .02–5)", draft.beatCooldownMs, 1, 96);
    seconds("attack", "Rise to peak (seconds, 0–10)", draft.attackMs, 0, 148);
    seconds("release", "Fall to default (seconds, 0–7200)", draft.releaseMs, 1, 148);
    recommendedToggle(
        "Short punch on every hit",
        draft.punchEnabled,
        202,
        () -> draft.punchEnabled = !draft.punchEnabled,
        true,
        () -> draft.punchEnabled = true);
    seconds("punchTime", "Punch duration (seconds, .02–2)", draft.punchMs, 0, 244);
    pair("punchStrength", "Punch strength (0–1)", draft.punchStrength, 1, 244);
    action(
        "Detection · " + draft.bassDetectionMode.label,
        0,
        298,
        bodyW,
        () -> {
          draft.bassDetectionMode =
              draft.bassDetectionMode == BassDetectionMode.ALL_BASS
                  ? BassDetectionMode.KICK
                  : BassDetectionMode.ALL_BASS;
          analysisKey = "";
          rebuildWidgets();
        });
    label("Lower threshold finds weaker hits; short punch separates fast hits.", 0, 322);
    action("Preview in game", 0, 354, bodyW, this::preview);
    page("Look");
    recommendedToggle(
        "Color tint grade",
        draft.colorGrade,
        0,
        () -> draft.colorGrade = !draft.colorGrade,
        true,
        () -> draft.colorGrade = true);
    action(
        "Tint color · " + String.format("#%06X", draft.tintColor),
        0,
        34,
        bodyW - 28,
        () ->
            minecraft.gui.setScreen(
                new ColorPickerScreen(
                    app, this, "Edit tint", draft.tintColor, color -> draft.tintColor = color)));
    recommendButton(
        bodyW - 22,
        34,
        () ->
            new SongRecommendations.Value(
                "#9BB6FF", "Subtle cool tint; color is a visual preference."),
        () -> {
          draft.tintColor = 0x9bb6ff;
          rebuildWidgets();
        });
    pair("tint", "Tint strength (0–0.5)", draft.tintStrength, 0, 78);
    pair("vignette", "Vignette strength (0–0.8)", draft.vignetteStrength, 1, 78);
    recommendedToggle(
        "Soft vignette",
        draft.vignette,
        132,
        () -> draft.vignette = !draft.vignette,
        true,
        () -> draft.vignette = true);
    recommendedToggle(
        "Soft light pulse on bass",
        draft.lightPulse,
        166,
        () -> draft.lightPulse = !draft.lightPulse,
        false,
        () -> draft.lightPulse = false);
    pair("pulse", "Light pulse strength (0–0.3)", draft.pulseStrength, 0, 210);
    pair("bars", "Bar height per edge (0–0.25)", draft.letterboxHeight, 1, 210);
    recommendedToggle(
        "Cinematic letterbox",
        draft.letterbox,
        264,
        () -> draft.letterbox = !draft.letterbox,
        false,
        () -> draft.letterbox = false);
    action("Preview in game", 0, 298, bodyW, this::preview);
    page("Camera");
    recommendedToggle(
        "New player-centered POV on every bass hit",
        draft.cameraCuts,
        0,
        () -> draft.cameraCuts = !draft.cameraCuts,
        true,
        () -> draft.cameraCuts = true);
    pair("camMin", "Nearest distance (blocks, 2–16)", draft.cameraMinDistance, 0, 44);
    pair("camMax", "Farthest distance (blocks, 2–16)", draft.cameraMaxDistance, 1, 44);
    pair("camLow", "Lowest angle (degrees, -20–75)", draft.cameraMinElevation, 0, 96);
    pair("camHigh", "Highest angle (degrees, -20–75)", draft.cameraMaxElevation, 1, 96);
    pair("camSpeed", "Orbit speed (degrees/sec, ±60)", draft.cameraOrbitSpeed, 0, 148);
    seconds("camBlend", "Cut blend (seconds, 0–2)", draft.cameraBlendMs, 1, 148);
    label("Zero blend makes a hard cut. Distance pulls in near walls.", 0, 204);
    label("Your body stays centered; your aim and F5 choice stay yours.", 0, 226);
    recommendedToggle(
        "Return into avatar after final hit",
        draft.cameraReturn,
        256,
        () -> draft.cameraReturn = !draft.cameraReturn,
        true,
        () -> draft.cameraReturn = true);
    label("The final hit starts a smooth return through the song outro.", 0, 292);
    recommendedToggle(
        "Distant camera intro",
        draft.cameraIntro,
        328,
        () -> draft.cameraIntro = !draft.cameraIntro,
        false,
        () -> draft.cameraIntro = false);
    seconds("introTime", "Approach duration (seconds, 0.2–30)", draft.cameraIntroMs, 0, 370);
    pair("introDistance", "Starting distance (blocks, 8–128)", draft.cameraIntroDistance, 1, 370);
    pair("introCurve", "Exponential curve (1–8)", draft.cameraIntroCurve, 0, 424);
    label("Higher curve: stronger speed change. Walls limit distance.", 0, 480);
    label("Intro enables the orbit and finishes before the outro begins.", 0, 504);
    recommendedToggle(
        "Accelerate toward avatar",
        draft.cameraIntroAccelerates,
        534,
        () -> draft.cameraIntroAccelerates = !draft.cameraIntroAccelerates,
        true,
        () -> draft.cameraIntroAccelerates = true);
    label("Off: approach fast, then slow down. Both settle smoothly.", 0, 570);
    action("Preview in game", 0, 602, bodyW, this::preview);
    page("Timing");
    seconds("fadeIn", "Effect fade in (seconds, 0–7200)", draft.fadeInMs, 0, 12);
    seconds("fadeOut", "Effect fade out (seconds, 0–7200)", draft.fadeOutMs, 1, 12);
    label("Duration and the song are set in the parent Celebration tab.", 0, 74);
    label("Entry and exit use smooth easing; zero makes them instant.", 0, 96);
    label("Preview uses these unsaved values. Back / Esc stops the preview.", 0, 118);
    action("Preview in game", 0, 154, bodyW, this::preview);
    page("Song");
    label("", 0, 0);
    pair("precision", "Measurements per second (50, 100, 200)", draft.analysisHz, 0, 36);
    pair("sync", "Visual sync offset (ms, -2000–2000)", draft.syncOffsetMs, 1, 36);
    label("Positive offset delays the effects; negative moves them earlier.", 0, 88);
    action(
        "View hit timeline",
        0,
        120,
        bodyW,
        () -> {
          read();
          if (analysis == null) {
            requestAnalysis(true);
            notice = "Wait for song analysis, then open the timeline.";
            return;
          }
          previewSettings.edit = ConfigStore.copy(draft, CelebrationEditSettings.class);
          minecraft.gui.setScreen(
              new SongAnalysisScreen(
                  app,
                  this,
                  previewSettings,
                  analysis,
                  () -> {
                    analysisKey = "";
                  }));
        });
    action("Apply recommended values", 0, 154, bodyW, this::applyRecommendations);
    action("Preview in game", 0, 188, bodyW, this::preview);
    label("Timeline edits override detection for this MP3 until you reset them.", 0, 230);
    label("Too many? Raise threshold or minimum spacing; inspect the timeline.", 0, 252);
    label("Vanilla sound variants use live detection; song analysis uses MP3s.", 0, 274);
    label("For song-wide timings, choose Use song duration in Celebration.", 0, 296);
    requestAnalysis(true);
  }

  private long ms(String key, float min, float max) {
    return Math.round(decimal(key, min, max) * 1000);
  }

  private void read() {
    draft.analysisHz = analysisRate();
    draft.syncOffsetMs = Math.round(decimal("sync", -2000, 2000));
    draft.punchMs = ms("punchTime", .02f, 2);
    draft.punchStrength = decimal("punchStrength", 0, 1);
    draft.cameraMinDistance = decimal("camMin", 2, 16);
    draft.cameraMaxDistance = decimal("camMax", 2, 16);
    draft.cameraMinElevation = decimal("camLow", -20, 75);
    draft.cameraMaxElevation = decimal("camHigh", -20, 75);
    draft.cameraOrbitSpeed = decimal("camSpeed", -60, 60);
    draft.cameraBlendMs = ms("camBlend", 0, 2);
    draft.cameraIntroMs = ms("introTime", .2f, 30);
    draft.cameraIntroDistance = decimal("introDistance", 8, 128);
    draft.cameraIntroCurve = decimal("introCurve", 1, 8);
    draft.volumeMultiplier = decimal("volume", 0, 4);
    draft.zoom = decimal("zoom", 1, 3);
    draft.shakeDegrees = decimal("shake", 0, 5);
    draft.rollDegrees = decimal("roll", 0, 5);
    draft.shakeHz = decimal("speed", .1f, 20);
    draft.motionMultiplier = decimal("boost", 1, 4);
    draft.bassLowHz = decimal("low", 10, 250);
    draft.bassHighHz = decimal("high", 30, 500);
    draft.bassThreshold = decimal("threshold", .001f, 1);
    draft.beatCooldownMs = ms("cooldown", .02f, 5);
    draft.attackMs = ms("attack", 0, 10);
    draft.releaseMs = ms("release", 0, 7200);
    draft.fadeInMs = ms("fadeIn", 0, 7200);
    draft.fadeOutMs = ms("fadeOut", 0, 7200);
    draft.tintStrength = decimal("tint", 0, .5f);
    draft.vignetteStrength = decimal("vignette", 0, .8f);
    draft.pulseStrength = decimal("pulse", 0, .3f);
    draft.letterboxHeight = decimal("bars", 0, .25f);
    ConfigStore.validateCelebrationEdit(draft);
  }

  private void preview() {
    read();
    previewSettings.edit = ConfigStore.copy(draft, CelebrationEditSettings.class);
    app.celebrations.show(previewSettings, "chasseur", 200, true);
    minecraft.gui.setScreen(new CelebrationSettingsScreen.Preview(app, this, previewSettings));
  }

  @Override
  protected void commit() {
    read();
    target.edit = ConfigStore.copy(draft, CelebrationEditSettings.class);
    CelebrationSettingsScreen.copyPosition(previewSettings, target);
    onClose();
  }
}
