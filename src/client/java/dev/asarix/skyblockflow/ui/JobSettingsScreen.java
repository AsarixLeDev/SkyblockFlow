package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;
import net.minecraft.client.gui.screens.Screen;

public final class JobSettingsScreen extends FormScreen {
  private final JobSettings draft;
  private boolean enabled;

  private final boolean panelOnly;

  public JobSettingsScreen(SkyblockFlowClient app, Screen parent) {
    this(app, parent, false);
  }

  public JobSettingsScreen(SkyblockFlowClient app, Screen parent, boolean panelOnly) {
    super(app, parent, "Job tracker");
    draft = ConfigStore.copy(app.store.config.jobs, JobSettings.class);
    this.panelOnly = panelOnly;
    enabled = app.store.config.enabled(Feature.JOBS);
  }

  @Override
  protected String description() {
    return "Automatic XP sessions, your pace and estimates to level 200.";
  }

  @Override
  protected void buildForm() {
    if (panelOnly) {
      buildPanel();
      return;
    }
    page("Tracking");
    toggle("Track job XP", enabled, 0, () -> enabled = !enabled);
    field(
        "window",
        "XP average window (minutes)",
        draft.rateWindowMs == 0 ? "" : "" + draft.rateWindowMs / 60000d,
        38);
    label("Leave empty for the whole session. Default: the last 5 minutes.", 0, 80);
    int half = (bodyW - 8) / 2;
    action("5 minutes (default)", 0, 100, half, () -> setWindow("5"));
    action("Whole session", half + 8, 100, half, () -> setWindow(""));
    label("Pace and ETAs refresh on XP gains and stay steady while idle.", 0, 132);
    field("timeout", "End session after no XP (minutes)", "" + draft.timeoutMs / 60000.0, 160);
    var job = app.jobs.tracker.selected(draft.pinnedJob);
    action(
        "Reset session" + (job == null ? "" : " · " + JobXp.name(job.id)),
        0,
        206,
        bodyW,
        () -> {
          app.jobs.resetSession(job == null ? "" : job.id);
          notice = job == null ? "No recorded job" : "Session reset · " + JobXp.name(job.id);
          rebuildWidgets();
        });
    label(
        "Resets XP and timer immediately. Keeps level progress and last completed session.",
        0,
        238);
    action(
        "View recorded jobs",
        0,
        272,
        bodyW,
        () -> minecraft.gui.setScreen(new JobStatsScreen(app, this)));
    label("/starfallah → time to 200 at the last completed session's pace.", 0, 307);
    label("Status: " + app.jobs.status, 0, 332);
    action(
        "Detection details",
        0,
        362,
        bodyW,
        () -> minecraft.gui.setScreen(new JobDetectionScreen(app, this)));
    toggle(
        "Fast XP updates (action bar)",
        draft.experimentalXp,
        400,
        () -> draft.experimentalXp = !draft.experimentalXp);
    toggle("Hide server XP boss bar", draft.hideXpBossBar, 436, () -> draft.hideXpBossBar = !draft.hideXpBossBar);
    toggle("Hide server jobs strip", draft.hideJobsHeader, 472, () -> draft.hideJobsHeader = !draft.hideJobsHeader);

    action(
        "Leaderboards  ›",
        0,
        514,
        bodyW,
        () -> minecraft.gui.setScreen(new LeaderboardsScreen(app, this)));
    action(
        "Level-up celebration",
        0,
        548,
        bodyW,
        () -> minecraft.gui.setScreen(new CelebrationSettingsScreen(app, this, draft.celebration)));
    toggle("Use vanilla /jobs stats", draft.vanillaJobsStats, 586, () -> draft.vanillaJobsStats = !draft.vanillaJobsStats);
    action("Public ranking refresh", 0, 622, bodyW,
        () -> minecraft.gui.setScreen(new PublicDataScreen(app, this)));
    page("Display");
    action(
        "Overlay settings  ›",
        0,
        0,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new HudOptions(app, this, dev.asarix.skyblockflow.core.HudSettings.Panel.JOBS)));
  }

  private void setWindow(String minutes) {
    values.put("window", minutes);
    rebuildWidgets();
  }

  private void buildPanel() {
    page("Overlay");
    toggle("Show job overlay", draft.showOverlay, 0, () -> draft.showOverlay = !draft.showOverlay);
    toggle(
        "Only while a session is active",
        draft.onlyActive,
        30,
        () -> draft.onlyActive = !draft.onlyActive);
    action(
        "When idle: " + (draft.pinnedJob.isEmpty() ? "Most recent job" : JobXp.name(draft.pinnedJob)),
        0,
        60,
        bodyW,
        () -> {
          List<ChoiceScreen.Choice<String>> choices = new ArrayList<>();
          choices.add(new ChoiceScreen.Choice<>("Most recent job", ""));
          JobXp.NAMES.forEach((id, name) -> choices.add(new ChoiceScreen.Choice<>(name, id)));
          minecraft.gui.setScreen(
              new ChoiceScreen<>(
                  app,
                  this,
                  "Overlay job",
                  choices,
                  id -> {
                    draft.pinnedJob = id;
                    minecraft.gui.setScreen(this);
                  }));
        });
    field(
        "resume",
        "Hidden overlay: show after this idle gap (seconds)",
        "" + draft.unhideAfterIdleMs / 1000,
        112);
    page("Fields");
    toggle(
        "Level progress & XP bar",
        draft.showProgress,
        0,
        () -> draft.showProgress = !draft.showProgress);
    toggle(
        "Session XP & elapsed time",
        draft.showSession,
        30,
        () -> draft.showSession = !draft.showSession);
    toggle(
        "XP per minute", draft.showPerMinute, 60, () -> draft.showPerMinute = !draft.showPerMinute);
    toggle("XP per hour", draft.showPerHour, 90, () -> draft.showPerHour = !draft.showPerHour);
    toggle(
        "Time to next level",
        draft.showNextLevel,
        120,
        () -> draft.showNextLevel = !draft.showNextLevel);
    toggle(
        "Time to next milestone (5 levels)",
        draft.showMilestone,
        150,
        () -> draft.showMilestone = !draft.showMilestone);
    toggle(
        "Time to level 200",
        draft.showLevel200,
        180,
        () -> draft.showLevel200 = !draft.showLevel200);
    toggle(
        "XP to closest player",
        draft.showClosestXp,
        214,
        () -> draft.showClosestXp = !draft.showClosestXp);
    toggle(
        "Time to closest player",
        draft.showClosestTime,
        248,
        () -> draft.showClosestTime = !draft.showClosestTime);
    toggle("Show idle timer after half the timeout", draft.showIdleTimer, 284, () -> draft.showIdleTimer = !draft.showIdleTimer);
    label("Times use hh:mm:ss, with hours allowed above 24.", 0, 320);
    label("Excalia job & Pêcheur curves. Estimates assume the same pace.", 0, 340);
  }

  @Override
  protected void commit() {
    var live = app.store.config.jobs;
    if (panelOnly) {
      draft.unhideAfterIdleMs = number("resume", 1, 3600) * 1000;
      live.showIdleTimer = draft.showIdleTimer;
      live.showOverlay = draft.showOverlay;
      live.onlyActive = draft.onlyActive;
      live.pinnedJob = draft.pinnedJob;
      live.showProgress = draft.showProgress;
      live.showSession = draft.showSession;
      live.showPerMinute = draft.showPerMinute;
      live.showPerHour = draft.showPerHour;
      live.showNextLevel = draft.showNextLevel;
      live.showMilestone = draft.showMilestone;
      live.showLevel200 = draft.showLevel200;
      live.showClosestXp = draft.showClosestXp;
      live.showClosestTime = draft.showClosestTime;
      live.unhideAfterIdleMs = draft.unhideAfterIdleMs;
      if (app.save()) closeAfterSave();
      return;
    }
    draft.timeoutMs = Math.round(decimal("timeout", 1f / 60, 1440) * 60000d);

    draft.rateWindowMs =
        value("window").isBlank() ? 0 : Math.round(decimal("window", 1f / 60, 60) * 60000d);

    ConfigStore.validateJobs(draft);
    live.timeoutMs = draft.timeoutMs;
    live.rateWindowMs = draft.rateWindowMs;
    live.experimentalXp = draft.experimentalXp;
    live.hideXpBossBar = draft.hideXpBossBar;
    live.hideJobsHeader = draft.hideJobsHeader;
    live.vanillaJobsStats = draft.vanillaJobsStats;
    // The celebration editor persists its own draft when applied.
    app.store.config.feature(Feature.JOBS).enabled = enabled;
    app.jobs.tick();
    app.jobs.flush();
    if (app.save()) closeAfterSave();
    else notice = app.lastError;
  }
}
