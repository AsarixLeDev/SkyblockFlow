package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.screens.Screen;

public final class DiagnosticsScreen extends FormScreen {
  public DiagnosticsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Activity and diagnostics");
  }

  @Override
  protected boolean readOnly() {
    return true;
  }

  @Override
  protected String description() {
    return "Recent events, key conflicts and feature status.";
  }

  @Override
  protected void buildForm() {
    page("Status");
    int y = 0;
    label(
        "Configuration: " + (app.store.loadWarning.isBlank() ? "OK" : app.store.loadWarning), 0, y);
    y += 24;
    label(
        "Quiet period remaining: "
            + app.grace.remaining(System.nanoTime() / 1000000, app.store.config.connectionGraceMs)
                / 1000
            + " s",
        0,
        y);
    y += 24;
    label("Hotkey conflicts", 0, y);
    y += 22;
    for (String line : app.keyConflicts()) {
      label(line, 0, y);
      y += 18;
    }
    page("Events");
    y = 0;
    label("Recent events", 0, y);
    y += 22;
    for (String line : app.history) {
      label(line, 0, y);
      y += 18;
    }
  }

  @Override
  protected void commit() {
    closeAfterSave();
  }
}
