package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import dev.asarix.skyblockflow.ui.Ui;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class JobOverlay {
  public record Row(String label, String value) {}

  public record Bounds(float x, float y, float width, float height) {
    public boolean contains(double mx, double my) {
      return mx >= x && my >= y && mx < x + width && my < y + height;
    }
  }

  public static List<Row> rows(JobTracker.Job job, JobSettings settings, long now) {
    List<Row> rows = new ArrayList<>();
    var s = job.displayed();
    double rate = s == null ? 0 : s.rate(now, settings.rateWindowMs);
    if (settings.showSession) {
      rows.add(
          new Row(
              job.active == null ? "Last session XP" : "Session XP",
              s == null ? "—" : "+" + JobXp.amount(s.xp)));
      rows.add(
          new Row("Session time", s == null ? "00:00:00" : JobXp.duration(s.elapsed(s.lastGain))));
    }
    if (settings.showPerMinute)
      rows.add(new Row("XP / minute", rate > 0 ? JobXp.amount(rate) : "Collecting XP…"));
    if (settings.showPerHour)
      rows.add(new Row("XP / hour", rate > 0 ? JobXp.amount(rate * 60) : "Collecting XP…"));
    if (settings.showNextLevel)
      rows.add(
          new Row(
              "Next level · " + Math.min(200, job.level + 1),
              JobXp.eta(job.remaining(Math.min(200, job.level + 1)), rate)));
    if (settings.showMilestone)
      rows.add(
          new Row(
              "Milestone · " + JobXp.milestone(job.level),
              JobXp.eta(job.remaining(JobXp.milestone(job.level)), rate)));
    if (settings.showLevel200) rows.add(new Row("Level 200", JobXp.eta(job.remaining(200), rate)));
    return rows;
  }

  public static JobTracker.Job preview() {
    var j = new JobTracker.Job();
    j.id = "chasseur";
    j.level = 183;
    j.xp = 12953.81;
    j.required = 92006;
    j.active = new JobTracker.Session();
    j.active.started = System.currentTimeMillis() - 24 * 60000;
    j.active.lastGain = System.currentTimeMillis();
    j.active.xp = 19375.2;
    return j;
  }

  public static Bounds render(
      SkyblockFlowClient app, GuiGraphicsExtractor g, JobSettings settings, boolean preview) {
    return render(app, g, settings, preview, true);
  }

  public static Bounds render(
      SkyblockFlowClient app,
      GuiGraphicsExtractor g,
      JobSettings settings,
      boolean preview,
      boolean draw) {
    if (app.celebrations != null && app.celebrations.hideOverlays()) return null;
    var c = app.store.config;
    if (!preview
        && (Minecraft.getInstance().player == null
            || !settings.showOverlay
            || !c.enabled(Feature.JOBS)
            || !c.visible(Feature.JOBS))) return null;
    var job = app.jobs.tracker.activeJob();
    if (job == null) job = app.jobs.tracker.selected(settings.pinnedJob);
    if (job == null && preview) job = preview();
    boolean placeholder = job == null;
    if (job == null) {
      job = new JobTracker.Job();
    }
    if (!preview && (settings.onlyActive && job.active == null || app.jobs.hidden(job)))
      return null;
    long now = System.currentTimeMillis();
    if (!placeholder && !preview) job = app.jobs.displayJob(job);
    List<Row> rows =
        new ArrayList<>(
            placeholder
                ? List.of(new Row("Status", "Waiting for job XP"))
                : rows(job, settings, now));
    if (!placeholder) {
      String rank = app.excaliaApi.rank(job.id,Minecraft.getInstance().getUser().getName());
      rows.add(new Row("Job rank",rank));
      if (rank.startsWith("#")) rows.addAll(app.closest.rows(job, settings));
    }
    var hud = app.store.config.hud;
    var paint =
        new dev.asarix.skyblockflow.ui.HudPaint(
            hud.theme(dev.asarix.skyblockflow.core.HudSettings.Panel.JOBS), hud.legacy);
    int w = 250;
    for (var row : rows) w = Math.max(w, paint.width(row.label) + paint.width(row.value) + 40);
    w = Math.min(w, 380);
    int header = settings.showProgress ? 65 : 36;
    boolean idleVisible = !placeholder && settings.showIdleTimer && job.active != null
        && now - job.active.lastGain >= settings.timeoutMs / 2;
    int h = header + rows.size() * 16 + (idleVisible ? 22 : 6);
    float scale = OverlayLayout.fittedScale(g.guiWidth(), g.guiHeight(), w, h, settings.scale);
    var rect =
        OverlayLayout.bounds(
            g.guiWidth(), g.guiHeight(), w, h, settings.x, settings.y, settings.scale);
    float x = (float) rect.x(), y = (float) rect.y();
    if (!draw) return new Bounds(x, y, w * scale, h * scale);
    g.pose().pushMatrix();
    g.pose().translate(x, y);
    g.pose().scale(scale, scale);
    var colors = hud.legacy ? settings.colors : paint.theme.colors;
    int bg = ((int) (255 * settings.opacity) << 24) | (colors.background & 0xffffff);
    if (!hud.legacy) paint.panel(g, 0, 0, w, h);
    else {
      Ui.rounded(g, 0, 0, w, h, 7, bg);
      Ui.rounded(g, 0, 8, 3, h - 16, 1, (0xff000000 | colors.accent));
    }
    paint.text(
        g,
        placeholder ? "Job tracker" : JobXp.name(job.id) + "  ·  " + job.level,
        12,
        12,
        (0xff000000 | colors.accent));
    String state =
        placeholder
            ? "NO SESSION"
            : job.level >= 200 ? "MAX LEVEL" : job.active != null ? "LIVE" : "LAST SESSION";
    paint.text(g, state, w - paint.width(state) - 12, 13, (0xff000000 | colors.muted));
    if (settings.showProgress) {
      String progress =
          placeholder
              ? "Earn job XP to start a session"
              : job.level >= 200
                  ? "Level 200 reached"
                  : JobXp.amount(job.xp) + " / " + JobXp.amount(job.required) + " XP";
      paint.text(g, progress, 12, 33, (0xff000000 | colors.text));
      Ui.rounded(g, 12, 50, w - 24, 4, 2, 0x884f6074);
      Ui.rounded(
          g,
          12,
          50,
          (int)
              ((w - 24)
                  * (job.level >= 200 ? 1 : Math.clamp(job.xp / Math.max(1, job.required), 0, 1))),
          4,
          2,
          (0xff000000 | colors.accent));
    }
    int yRow = header;
    for (var row : rows) {
      String label = paint.trim(row.label, (w - 34) / 2);
      String value = paint.trim(row.value, w - 34 - paint.width(label));
      paint.text(g, label, 12, yRow, (0xff000000 | colors.muted));
      paint.text(g, value, w - paint.width(value) - 12, yRow, (0xff000000 | colors.text));
      yRow += 16;
    }
    String note =
        job.active != null
            ? "Idle "
                + JobXp.duration(now - job.active.lastGain)
                + " / "
                + JobXp.duration(settings.timeoutMs)
            : "Waiting for new XP";
    if (idleVisible) paint.text(g, paint.trim(note, w - 24), 12, h - 15, (0xff000000 | colors.muted));
    g.pose().popMatrix();
    return new Bounds(x, y, w * scale, h * scale);
  }
}
