package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import net.minecraft.client.gui.screens.Screen;

public final class JobSessionScreen extends FormScreen {
  private int ticks;

  public JobSessionScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Recorded jobs");
  }

  @Override
  protected boolean readOnly() {
    return true;
  }

  @Override
  protected String description() {
    return "This server & account · current or most recent session for each job.";
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
    int y = 0;
    long now = System.currentTimeMillis();
    for (String id : JobXp.NAMES.keySet()) {
      var j = app.jobs.tracker.data.jobs.get(id);
      if (j == null) continue;
      section(
          JobXp.name(id)
              + " · Level "
              + j.level
              + " · "
              + (j.active == null ? "Last session" : "Live"),
          y);
      y += 24;
      label(
          JobXp.amount(j.xp)
              + " / "
              + JobXp.amount(j.required)
              + " XP · "
              + j.sessions
              + " sessions recorded",
          0,
          y);
      y += 22;
      for (var row : JobOverlay.rows(j, app.store.config.jobs, now)) {
        label(row.label() + ": " + row.value(), 0, y);
        y += 18;
      }
      for (var row : app.closest.rows(j, app.store.config.jobs)) {
        label(row.label() + ": " + row.value(), 0, y);
        y += 18;
      }
      var measured = app.closest.result(id);
      if (measured != null) {
        label("Measured " + JobXp.duration(now - measured.at()) + " ago", 0, y);
        y += 18;
      }
      action(
          "Measure closest player now",
          0,
          y + 4,
          bodyW,
          () -> {
            app.closest.measure(id);
            notice = app.closest.status;
          });
      y += 38;
      label(
          app.closest.busy()
              ? app.closest.status
              : "Measure when you want an update",
          0,
          y);
      y += 22;
      if (j.last != null) {
        label(
            "/starfallah pace: " + JobXp.amount(j.last.rate(now)) + " XP/min (last completed)",
            0,
            y);
        y += 18;
      }
      action(
          "Reset session · " + JobXp.name(id),
          0,
          y + 4,
          bodyW,
          () -> {
            app.jobs.resetSession(id);
            notice = "Session reset · " + JobXp.name(id);
            rebuildWidgets();
          });
      y += 44;
    }
    if (y == 0) {
      section("Your first session starts when you gain job XP", 0);
      label("Enable Job tracker, join Excalia and earn XP in any job.", 0, 30);
    }
  }
}
