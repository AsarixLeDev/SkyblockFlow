package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.HudSettings.Panel;
import net.minecraft.client.gui.screens.Screen;

public final class OverlayHub extends FormScreen {
  public OverlayHub(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Overlays");
  }

  protected boolean readOnly() {
    return true;
  }

  protected String description() {
    return "Choose a shared look, or customize one overlay.";
  }

  protected void buildForm() {
    int half = (bodyW - 6) / 2;
    action("Default appearance", 0, 0, half, () -> open(null));
    action(
        "Edit layout",
        half + 6,
        0,
        half,
        () -> minecraft.gui.setScreen(new OverlayEditorScreen(app, this)));
    action("Community names · tab and chat", 0, 34, bodyW, () -> minecraft.gui.setScreen(new PlayerNamesScreen(app, this)));
    int y = 72;
    for (var p : Panel.values()) {
      action(p.title, 0, y, bodyW, () -> open(p));
      y += 28;
    }
  }

  private void open(Panel p) {
    minecraft.gui.setScreen(new HudOptions(app, this, p));
  }

  protected void commit() {
    onClose();
  }
}
