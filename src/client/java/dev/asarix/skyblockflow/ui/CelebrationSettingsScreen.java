package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

public final class CelebrationSettingsScreen extends FormScreen {
  private final CelebrationSettings target;
  private final EditPresetStore presetStore;
  private final boolean presetOnly;
  private String presetName = "", baseline;

  private CelebrationSettings draft;
  private SongAnalysis song;
  private String songKey = "";
  private String songStatus = "";

  private void analyzeSelectedSong() {
    String key =
        draft.soundKind
            + "|"
            + draft.sound
            + "|"
            + draft.edit.bassDetectionMode
            + "|"
            + draft.edit.analysisHz
            + "|"
            + draft.edit.bassLowHz
            + "|"
            + draft.edit.bassHighHz
            + "|"
            + draft.edit.bassThreshold
            + "|"
            + draft.edit.beatCooldownMs;
    if (key.equals(songKey)) return;
    songKey = key;
    song = null;
    if (draft.soundKind != SoundKind.MP3) {
      songStatus = "Select an MP3 to use its exact duration.";
      return;
    }
    songStatus = "Analyzing selected song...";
    app.sounds
        .analyzeSong(draft, draft.edit)
        .whenCompleteAsync(
            (result, error) ->
                minecraft.execute(
                    () -> {
                      if (!key.equals(songKey)) return;
                      song = result;
                      songStatus =
                          error == null
                              ? "Song length: "
                                  + SongAnalysisScreen.time(result.durationMs())
                                  + " at original speed"
                              : "Cannot analyze song: "
                                  + (error.getCause() == null
                                      ? error.getMessage()
                                      : error.getCause().getMessage());
                      if (minecraft.gui.screen() == this) rebuildWidgets();
                    }));
  }

  @Override
  protected SongRecommendations.Value recommendation(String key) {
    float pitch = draft.pitch;
    try {
      pitch = Float.parseFloat(value("pitch"));
    } catch (Exception ignored) {
    }
    if (!Float.isFinite(pitch) || pitch < .5 || pitch > 2) pitch = 1;
    if (key.equals("text"))
      return new SongRecommendations.Value(
          "LEVEL {level} · {job}", "Default title with level and job variables.");
    if (key.equals("sub"))
      return new SongRecommendations.Value(
          "One level closer to the stars!", "Default celebration subtitle.");
    if (key.equals("duration") && song == null)
      return new SongRecommendations.Value(
          "6", "Fallback: 6 seconds. Analyze the selected MP3 to use its exact length.");
    return SongRecommendations.forKey(
        key.equals("volume") ? "soundVolume" : key, song, pitch, draft.edit.syncOffsetMs);
  }

  private void useSongDuration() {
    if (song == null) {
      notice = songStatus;
      return;
    }
    var recommended = recommendation("duration");
    values.put("duration", recommended.text());
    notice = "Duration set to the selected song at the chosen pitch.";
    rebuildWidgets();
  }

  public CelebrationSettingsScreen(
      SkyblockFlowClient app, Screen parent, CelebrationSettings target) {
    this(app, parent, target, false);
  }

  public static CelebrationSettingsScreen preset(
      SkyblockFlowClient app, Screen parent, EditPresetStore.Preset preset) {
    return new CelebrationSettingsScreen(app, parent, EditPresetStore.edit(preset), true);
  }

  private CelebrationSettingsScreen(
      SkyblockFlowClient app, Screen parent, CelebrationSettings target, boolean presetOnly) {
    super(app, parent, "Level-up celebration");
    this.target = target;
    this.presetOnly = presetOnly;
    presetStore = new EditPresetStore(app.store.directory());
    draft = ConfigStore.copy(target, CelebrationSettings.class);
    if (draft.presetId == null) draft.presetId = "";
    try {
      if (draft.presetId.isEmpty()) {
        var match = presetStore.matching(draft);
        if (match.isPresent()) draft.presetId = match.get().id();
      }
      if (!draft.presetId.isEmpty()) presetName = presetStore.get(draft.presetId).name();
    } catch (Exception e) {
      presetName = "Unavailable preset";
      notice = e.getMessage();
    }
    baseline = ConfigStore.GSON.toJson(draft);
    selectPage("Celebration");
  }

  @Override
  protected String heading() {
    return draft.presetId.isEmpty() ? "Level-up celebration" : "Edit · " + presetName;
  }

  @Override
  protected String saveLabel() {
    return draft.presetId.isEmpty() ? "Save changes" : "Save preset";
  }

  @Override
  protected String savedNotice() {
    return draft.presetId.isEmpty() ? "Celebration saved" : "Preset saved · " + presetName;
  }

  @Override
  protected String description() {
    if (draft.presetId.isEmpty())
      return "Editing your current celebration · Save as new to keep a named preset.";
    return "Editing preset: "
        + presetName
        + (presetOnly
            ? " · Save updates this preset."
            : " · Save updates this preset and your level-up celebration.");
  }

  private void openLibrary() {
    read();
    minecraft.gui.setScreen(new EditPresetsScreen(app, this, draft.presetId));
  }

  void openPreset(EditPresetStore.Preset preset) {
    continueAfterChanges(
        () -> {
          try {
            var fresh = presetStore.get(preset.id());
            draft = EditPresetStore.edit(fresh);
            presetName = fresh.name();
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
          }
          values.clear();
          songKey = "";
          baseline = ConfigStore.GSON.toJson(draft);
          selectPage("Celebration");
          notice = "Editing " + presetName + " · Save preset keeps your changes";
          minecraft.gui.setScreen(this);
        });
  }

  void saveAsNew() {
    read();
    minecraft.gui.setScreen(
        new EditPresetsScreen.Name(
            app,
            minecraft.gui.screen(),
            "Save as new preset",
            "",
            name -> {
              var saved = presetStore.save(null, name, draft);
              draft.presetId = saved.id();
              presetName = saved.name();
              // The file is saved now; selecting it for level-ups still uses the explicit Save
              // button.
              baseline = ConfigStore.GSON.toJson(draft);
              notice = "Created " + presetName + " · You are now editing this preset";
              minecraft.gui.setScreen(this);
            }));
  }

  void presetDeleted(String id) {
    if (id.equals(draft.presetId)) {
      draft.presetId = "";
      presetName = "";
      var original = ConfigStore.GSON.fromJson(baseline, CelebrationSettings.class);
      original.presetId = "";
      baseline = ConfigStore.GSON.toJson(original);
    }
    boolean activeDeleted = id.equals(app.store.config.jobs.celebration.presetId);
    if (id.equals(target.presetId)) target.presetId = "";
    if (activeDeleted) {
      app.store.config.jobs.celebration.presetId = "";
      if (!app.save()) throw new IllegalStateException(app.lastError);
    }
  }

  private boolean hasChanges() {
    try {
      read();
    } catch (RuntimeException invalid) {
      return true;
    }
    return !baseline.equals(ConfigStore.GSON.toJson(draft));
  }

  void continueAfterChanges(Runnable next) {
    if (!hasChanges()) {
      next.run();
      return;
    }
    try {
      saveEdit();
      next.run();
    } catch (RuntimeException invalid) {
      notice = invalid.getMessage();
    }
  }

  @Override
  public void onClose() {
    continueAfterChanges(() -> minecraft.gui.setScreen(parent));
  }

  private static final class Unsaved extends FormScreen {
    private final CelebrationSettingsScreen editor;
    private final Runnable next;

    Unsaved(SkyblockFlowClient app, Screen parent, CelebrationSettingsScreen editor, Runnable next) {
      super(app, parent, "Unsaved edit changes");
      this.editor = editor;
      this.next = next;
    }

    @Override
    protected boolean readOnly() {
      return true;
    }

    @Override
    protected String description() {
      return "Keep or discard the changes before leaving this edit.";
    }

    @Override
    protected void buildForm() {
      section(editor.heading(), 0);
      label("Your song, effects and display changes have not been saved.", 0, 32);
      action(
          "Save and continue",
          0,
          68,
          bodyW,
          () -> {
            editor.saveEdit();
            next.run();
          });
      action("Discard changes", 0, 106, bodyW, next);
      action("Keep editing", 0, 144, bodyW, this::onClose);
    }

    @Override
    protected void commit() {
      onClose();
    }

    @Override
    public void onClose() {
      closeAfterSave();
    }
  }

  @Override
  protected void buildForm() {
    if (!draft.presetId.isEmpty()) {
      try {
        presetName = presetStore.get(draft.presetId).name();
      } catch (Exception e) {
        notice = e.getMessage() + " · Save as new to keep this edit";
      }
    }
    analyzeSelectedSong();
    page("Presets");
    section(
        draft.presetId.isEmpty()
            ? "Current celebration · no preset selected"
            : "Editing · " + presetName,
        0);
    label("Song: " + draft.sound, 0, 32);
    label("Duration: " + SongAnalysisScreen.time(draft.durationMs), 0, 56);
    action(
        "Edit music & appearance",
        0,
        92,
        bodyW,
        () -> {
          selectPage("Celebration");
          rebuildWidgets();
        });
    action("Browse edit presets", 0, 130, bodyW, this::openLibrary);
    action("Save as new preset", 0, 168, bodyW, this::saveAsNew);
    label(
        draft.presetId.isEmpty()
            ? "Save as new creates a separate edit in your library."
            : "Save preset updates this exact preset, including its song and effects.",
        0,
        214);
    label("Apply effects returns to this editor. Save here to keep the edit.", 0, 240);
    label("Reviewed hit timelines stay attached to their MP3.", 0, 266);
    page("Celebration");
    recommendedToggle(
        "Celebrate job level-ups",
        draft.enabled,
        0,
        () -> draft.enabled = !draft.enabled,
        true,
        () -> draft.enabled = true);
    recommendedToggle(
        "Animate the constellation card",
        draft.animated,
        32,
        () -> draft.animated = !draft.animated,
        true,
        () -> draft.animated = true);
    recommendedToggle(
        "Client firework sparks around you",
        draft.fireworks,
        64,
        () -> draft.fireworks = !draft.fireworks,
        true,
        () -> draft.fireworks = true);
    field(
        "duration",
        "Celebration & fireworks duration (seconds, 1–7200)",
        "" + draft.durationMs / 1000.0,
        108);
    action("Use song duration", 0, 150, bodyW, this::useSongDuration);
    label(songStatus, 0, 181);
    field("text", "Title · {job} and {level} are replaced", draft.text, 200);
    field("sub", "Subtitle", draft.subtitle, 246);
    action(
        "Sound · " + draft.soundKind,
        0,
        296,
        bodyW,
        () -> minecraft.gui.setScreen(new SoundPickerScreen(app, this, draft)));
    field("volume", "Volume (0–1)", "" + draft.volume, 336);
    field("pitch", "Pitch (0.5–2)", "" + draft.pitch, 382);
    label("Image: " + (draft.image.isEmpty() ? "Default constellation" : draft.image), 0, 430);
    action(
        "Choose imported PNG",
        0,
        456,
        bodyW,
        () -> {
          try {
            Files.createDirectories(app.celebrations.images());
            List<ChoiceScreen.Choice<String>> choices = new ArrayList<>();
            choices.add(new ChoiceScreen.Choice<>("Default constellation", ""));
            try (var files = Files.list(app.celebrations.images())) {
              files
                  .filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                  .sorted()
                  .forEach(
                      f ->
                          choices.add(
                              new ChoiceScreen.Choice<>(
                                  f.getFileName().toString(), f.getFileName().toString())));
            }
            minecraft.gui.setScreen(
                new ChoiceScreen<>(
                    app,
                    this,
                    "Celebration image",
                    choices,
                    file -> {
                      draft.image = file;
                      minecraft.gui.setScreen(this);
                    }));
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
    action("Preview in game", 0, 492, bodyW, this::preview);
    action(
        "Restore default celebration",
        0,
        526,
        bodyW,
        () -> {
          var defaults = dev.asarix.skyblockflow.core.FactoryDefaults.settings().jobs.celebration;
          values.put("text", defaults.text);
          values.put("sub", defaults.subtitle);
          draft.image = defaults.image;
          draft.animated = defaults.animated;
          draft.fireworks = defaults.fireworks;
          rebuildWidgets();
        });
    page("Display");
    recommendedToggle(
        "Hide Minecraft HUD during celebration",
        draft.hideMinecraftHud,
        0,
        () -> draft.hideMinecraftHud = !draft.hideMinecraftHud,
        true,
        () -> draft.hideMinecraftHud = true);
    recommendedToggle(
        "Show level-up card",
        draft.showCard,
        32,
        () -> draft.showCard = !draft.showCard,
        true,
        () -> draft.showCard = true);
    recommendedToggle(
        "Hide SkyblockFlow overlays during celebration",
        draft.hideOverlays,
        64,
        () -> draft.hideOverlays = !draft.hideOverlays,
        true,
        () -> draft.hideOverlays = true);
    label("F1-style HUD hiding lasts only for the celebration.", 0, 106);
    label("Your F1 choice is preserved. Menus remain usable.", 0, 128);
    label("The card can stay visible even with Minecraft's HUD hidden.", 0, 150);
    label("Drag the card in Preview in game to set its position.", 0, 172);
    action("Preview in game", 0, 206, bodyW, this::preview);
    page("Edit mode");
    recommendedToggle(
        "Music-video edit mode",
        draft.edit.enabled,
        0,
        () -> draft.edit.enabled = !draft.edit.enabled,
        true,
        () -> draft.edit.enabled = true);
    label("Bass-reactive motion, cinematic camera cuts and color grading.", 0, 40);
    label("Tune volume, zoom and the rise/fall of each bass hit below.", 0, 62);
    action(
        "Configure edit effects",
        0,
        100,
        bodyW,
        () -> {
          read();
          minecraft.gui.setScreen(new CelebrationEditScreen(app, this, draft));
        });
    action("Preview in game", 0, 136, bodyW, this::preview);
    label("Choose your song on the Celebration tab. MP3 and vanilla work.", 0, 182);
    label("Edits fade in/out and stop with the celebration. Sleep alarms", 0, 204);
    label("keep their configured explicit-dismiss behavior.", 0, 224);
  }

  private void preview() {
    read();
    app.celebrations.show(draft, "chasseur", 200, true);
    minecraft.gui.setScreen(new Preview(app, this, draft));
  }

  static void copyPosition(CelebrationSettings from, CelebrationSettings to) {
    to.customCardPosition = from.customCardPosition;
    to.cardX = from.cardX;
    to.cardY = from.cardY;
  }

  static final class Preview extends FlowScreen {
    private final CelebrationSettings settings;
    private boolean dragging;
    private double startX, startY;
    private OverlayLayout.Rect start;

    Preview(SkyblockFlowClient app, Screen parent, CelebrationSettings settings) {
      super(app, parent, "Celebration preview");
      this.settings = settings;
    }

    @Override
    protected void init() {
      super.init();
      dragging = false;
      int w = Math.min(480, width - 24), x = (width - w) / 2, bw = (w - 8) / 2;
      button(
          x,
          height - 98,
          w,
          "Full preview",
          () -> minecraft.gui.setScreen(new FullPreview(app, this)));
      button(x, height - 64, bw, "Test bass hit", () -> app.celebrations.previewBeat());
      button(
          x + bw + 8,
          height - 64,
          bw,
          "Replay preview",
          () -> app.celebrations.show(settings, "chasseur", 200, true));
      button(
          x,
          height - 34,
          bw,
          "Reset card position",
          () -> {
            copyPosition(FactoryDefaults.settings().jobs.celebration, settings);
            app.celebrations.positionCard(settings);
          });
      button(x + bw + 8, height - 34, bw, "Back to celebration", this::onClose);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
      if (super.mouseClicked(e, twice)) return true;
      var bounds = app.celebrations.cardBounds(width, height);
      if (e.button() != 0 || bounds == null || !bounds.contains(e.x(), e.y())) return false;
      start = bounds;
      startX = e.x();
      startY = e.y();
      dragging = true;
      return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
      if (!dragging || e.button() != 0 || !app.celebrations.active()) return false;
      var moved = OverlayLayout.move(start, e.x() - startX, e.y() - startY, 1, width, height);
      settings.customCardPosition = true;
      settings.cardX = moved.x();
      settings.cardY = moved.y();
      app.celebrations.positionCard(settings);
      return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
      if (e.button() == 0 && dragging) {
        dragging = false;
        return true;
      }
      return super.mouseReleased(e);
    }

    @Override
    public void onClose() {
      app.celebrations.stop();
      app.sounds.stopPreview();
      super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
      return false;
    }

    @Override
    public void extractBackground(
        net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float delta) {}

    @Override
    public void extractRenderState(
        net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float delta) {
      var bounds = app.celebrations.cardBounds(width, height);
      if (bounds != null) {
        int left = (int) bounds.x(),
            top = (int) bounds.y(),
            right = (int) (bounds.x() + bounds.width()),
            bottom = (int) (bounds.y() + bounds.height());
        int color = dragging || bounds.contains(x, y) ? 0xffffda80 : 0xff79dfbe;
        g.fill(left, top, right, top + 1, color);
        g.fill(left, bottom - 1, right, bottom, color);
        g.fill(left, top, left + 1, bottom, color);
        g.fill(right - 1, top, right, bottom, color);
      }
      String hint = "Drag the card · Full preview hides controls · Esc returns";
      hint = Ui.trim(hint, width - 24);
      Ui.text(g, hint, (width - Ui.width(hint)) / 2, height - 132, 0xffe7edf5);
      String status =
          app.celebrations.active()
              ? String.format(
                  Locale.ROOT,
                  "BASS %.3f   ·   HITS %d   ·   ZOOM ×%.2f",
                  app.celebrations.bassEnergy(),
                  app.celebrations.bassHits(),
                  app.celebrations.editFrame().zoom())
              : "Preview finished · Replay to move the card again";
      status = Ui.trim(status, width - 24);
      Ui.text(g, status, (width - Ui.width(status)) / 2, height - 116, 0xffc3d8ed);
      renderChrome(g, x, y, delta);
    }
  }

  static final class FullPreview extends FlowScreen {
    FullPreview(SkyblockFlowClient app, Screen parent) {
      super(app, parent, "Full celebration preview");
    }

    @Override
    protected void init() {
      chrome.clear();
    }

    @Override
    public void extractBackground(
        net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float dt) {}

    @Override
    public void extractRenderState(
        net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float dt) {}

    @Override
    public boolean isPauseScreen() {
      return false;
    }

    @Override
    public void onClose() {
      closeAfterSave();
    }
  }

  @Override
  public void onFilesDrop(List<Path> files) {
    try {
      for (var file : files) draft.image = app.celebrations.importImage(file);
      notice = "Image imported";
      rebuildWidgets();
    } catch (Exception e) {
      notice = e.getMessage();
    }
  }

  private void read() {
    draft.durationMs = Math.round(decimal("duration", 1, 7200) * 1000);
    draft.text = value("text");
    draft.subtitle = value("sub");
    draft.volume = decimal("volume", 0, 1);
    draft.pitch = decimal("pitch", .5f, 2);
    ConfigStore.validateCelebration(draft);
  }

  private void saveEdit() {
    read();
    boolean savedPreset = false;
    if (!draft.presetId.isEmpty()) {
      try {
        var saved = presetStore.update(draft.presetId, draft);
        presetName = saved.name();
        savedPreset = true;
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e.getMessage(), e);
      }
    }
    // A top-level Save persists just the celebration, even inside the job settings draft.
    // Sharing can edit another preset without replacing the selected level-up celebration.
    var previous = app.store.config.jobs.celebration;
    if (!presetOnly || (!draft.presetId.isEmpty() && draft.presetId.equals(previous.presetId))) {
      app.store.config.jobs.celebration = ConfigStore.copy(draft, CelebrationSettings.class);
      if (!app.save()) {
        app.store.config.jobs.celebration = previous;
        throw new IllegalStateException((savedPreset ? "Preset saved; " : "") + app.lastError);
      }
    }
    copySettings(draft, target);
    baseline = ConfigStore.GSON.toJson(draft);
  }

  private static void copySettings(CelebrationSettings draft, CelebrationSettings target) {
    target.presetId = draft.presetId;
    target.enabled = draft.enabled;
    target.animated = draft.animated;
    target.fireworks = draft.fireworks;
    target.hideOverlays = draft.hideOverlays;
    target.hideMinecraftHud = draft.hideMinecraftHud;
    target.showCard = draft.showCard;
    copyPosition(draft, target);
    target.durationMs = draft.durationMs;
    target.text = draft.text;
    target.subtitle = draft.subtitle;
    target.image = draft.image;
    target.edit = ConfigStore.copy(draft.edit, CelebrationEditSettings.class);
    target.soundKind = draft.soundKind;
    target.sound = draft.sound;
    target.volume = draft.volume;
    target.pitch = draft.pitch;
  }

  @Override
  protected void commit() {
    saveEdit();
    closeAfterSave();
  }
}
