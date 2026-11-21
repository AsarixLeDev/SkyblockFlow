package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.screens.Screen;

/** Local, bounded HUD samples only; ordinary chat messages are not collected. */
public final class JobDetectionScreen extends FormScreen {
  private int ticks;

  public JobDetectionScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Job detection");
  }

  @Override
  protected boolean readOnly() {
    return true;
  }

  @Override
  protected String description() {
    return "Shows the HUD data Minecraft actually receives. Hover clipped lines to read them.";
  }

  @Override
  protected void commit() {
    onClose();
  }

  @Override
  public void tick() {
    if (++ticks % 20 == 0) rebuildWidgets();
  }

  @Override
  protected void buildForm() {
    action(
        "Save detection report",
        0,
        0,
        bodyW,
        () -> {
          try {
            app.jobs.exportDiagnostics();
            notice = "Saved config/skyblockflow/job-detection.txt";
          } catch (java.io.IOException e) {
            notice = "Could not save report: " + e.getMessage();
          }
        });
    int y = 40;
    for (String line : app.jobs.diagnostics()) {
      label(line, 0, y);
      y += 20;
    }
  }
}
