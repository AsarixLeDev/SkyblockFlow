package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.ConfigStore;
import dev.asarix.skyblockflow.core.Models.CoordinateAlert;
import net.minecraft.client.gui.screens.Screen;

public final class CoordinateAlertEditorScreen extends FormScreen {
  private final CoordinateAlert original, draft;

  public CoordinateAlertEditorScreen(
      SkyblockFlowClient app, Screen parent, CoordinateAlert original) {
    super(app, parent, original == null ? "New coordinate sound" : "Edit coordinate sound");
    this.original = original;
    draft =
        original == null
            ? new CoordinateAlert()
            : ConfigStore.copy(original, CoordinateAlert.class);
    if (original == null) {
      var mc = net.minecraft.client.Minecraft.getInstance();
      if (mc.level != null) draft.dimension = mc.level.dimension().identifier().toString();
      if (mc.player != null) {
        var pos = mc.player.blockPosition();
        draft.region.x1 = draft.region.x2 = pos.getX();
        draft.region.y1 = draft.region.y2 = pos.getY();
        draft.region.z1 = draft.region.z2 = pos.getZ();
      }
    }
  }

  @Override
  protected void buildForm() {
    field("name", "Sound name", draft.name, 0);
    action(
        "Enabled: " + (draft.enabled ? "ON" : "OFF"),
        0,
        46,
        bodyW,
        () -> {
          draft.enabled = !draft.enabled;
          rebuildWidgets();
        });
    regionFields(draft.region, 82);
    field("dimension", "Dimension ID (blank = any dimension)", draft.dimension, 230);
    action(
        "Use current dimension",
        0,
        276,
        bodyW,
        () -> {
          if (minecraft.level == null) throw new IllegalArgumentException("Join a world first");
          values.put("dimension", minecraft.level.dimension().identifier().toString());
          rebuildWidgets();
        });
    action(
        "Sound: " + draft.soundKind + " · Choose...",
        0,
        316,
        bodyW - 83,
        () -> {
          read();
          minecraft.gui.setScreen(new SoundPickerScreen(app, this, draft));
        });
    action(
        "Preview",
        bodyW - 77,
        316,
        77,
        () -> {
          read();
          app.sounds.play(draft);
        });
    label(draft.sound, 0, 347);
    int w = (bodyW - 8) / 2;
    field("volume", "Volume 0–1", "" + draft.volume, 0, 370, w);
    field("pitch", "Pitch .5–2 (vanilla)", "" + draft.pitch, w + 8, 370, w);
    label("Sounds once when your feet enter the block or box.", 0, 419);
    label("Leave the area to rearm. Triggers once per entry.", 0, 440);
  }

  private void read() {
    draft.name = value("name").strip();
    draft.region = readRegion();
    draft.dimension = value("dimension").trim();
    draft.volume = decimal("volume", 0, 1);
    draft.pitch = decimal("pitch", .5f, 2);
    ConfigStore.validateCoordinateAlert(draft);
  }

  @Override
  protected void commit() {
    read();
    if (original == null) app.store.config.coordinateAlerts.add(draft);
    else {
      int i = app.store.config.coordinateAlerts.indexOf(original);
      if (i >= 0) app.store.config.coordinateAlerts.set(i, draft);
    }
    app.coordinates.forget(draft.id);
    if (app.save()) closeAfterSave();
    else notice = app.lastError;
  }
}
