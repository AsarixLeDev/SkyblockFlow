package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.Feature;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;

public final class JobService implements ClientboundBossEventPacket.Handler {
  private final SkyblockFlowClient app;
  public JobTracker tracker = new JobTracker();
  private JobStore store;
  private JobTracker.Job cachedOriginal, cachedDisplay;
  private double cachedBaseXp, cachedPending;
  private long cachedGain;
  public final ActionBarXp actionXp = new ActionBarXp();
  public final JobBossVisibility visibility;
  private Object actionWorld;
  private long lastSave;
  private boolean dirty, wasEnabled = true;
  private long bossTitles, actionBars, matches;
  private final Deque<String> samples = new ArrayDeque<>();
  private String previousSample = "";
  private String hiddenJob = "";
  private JobTracker.Session hiddenSession;
  public String status = "Waiting for a job boss bar";

  public JobService(SkyblockFlowClient app) {
    this.app = app;
    visibility = new JobBossVisibility(this::hideBoss, packet -> {
      var connection = Minecraft.getInstance().getConnection();
      if (connection != null) connection.handleBossUpdate(packet);
    });
  }

  public void connect() {
    flush();
    bossTitles = actionBars = matches = 0;
    samples.clear();
    previousSample = "";
    store =
        new JobStore(
            app.store.directory(),
            app.serverKey(),
            Minecraft.getInstance().getUser().getProfileId().toString());
    try {
      tracker = new JobTracker(store.load());
    } catch (java.io.IOException e) {
      app.error("Job tracker: " + e.getMessage());
      tracker = new JobTracker();
    }
    tracker.newConnection();
    actionXp.reset();
    actionWorld = Minecraft.getInstance().level;
    hiddenJob = "";
    hiddenSession = null;
    dirty = tracker.expire(System.currentTimeMillis(), app.store.config.jobs.timeoutMs);
    wasEnabled = app.store.config.enabled(Feature.JOBS);
    status = "Waiting for a job boss bar";
  }

  public void disconnect() {
    visibility.reset();
    flush();
    store = null;
    actionWorld = null;
    tracker.newConnection();
    actionXp.reset();
    hiddenJob = "";
    hiddenSession = null;
  }

  private void syncActionWorld() {
    var world = Minecraft.getInstance().level;
    if (actionWorld != world) { actionWorld = world; actionXp.reset(); }
  }

  public void tick() {
    syncActionWorld();
    visibility.sync();
    long now = System.currentTimeMillis();
    actionXp.petMultiplier(app.pets.tracker.jobMultiplier(actionXp.job(), now));
    actionXp.tick(now);
    boolean enabled = app.store.config.enabled(Feature.JOBS);
    if (wasEnabled && !enabled) {
      tracker.finishAll(now);
      actionXp.reset();
      dirty = true;
    }
    if (!wasEnabled && enabled) tracker.newConnection();
    wasEnabled = enabled;
    dirty |= tracker.expire(now, app.store.config.jobs.timeoutMs);
    if (tracker.activeSessions() > 1) actionXp.reset();
    if (dirty && now - lastSave >= 5000) flush();
  }

  public void flush() {
    if (store == null || !dirty) return;
    try {
      store.save(tracker.data);
      dirty = false;
    } catch (java.io.IOException e) {
      app.error("Could not save job history: " + e.getMessage());
    }
    lastSave = System.currentTimeMillis();
  }

  public void hideUntilNextSession() {
    var job = tracker.activeJob();
    if (job == null) job = tracker.selected(app.store.config.jobs.pinnedJob);
    hiddenJob = job == null ? "*" : job.id;
    hiddenSession = job == null ? null : job.active;
  }

  public boolean hidden(JobTracker.Job job) {
    if (job == null || !job.id.equals(hiddenJob) && !hiddenJob.equals("*")) return false;
    if (job.active != null && job.active != hiddenSession) {
      hiddenJob = "";
      hiddenSession = null;
      return false;
    }
    return true;
  }

  public void resetSession(String id) {
    if (store == null)
      throw new IllegalStateException("Join a world or server to reset a session.");
    if (!tracker.resetSession(id, System.currentTimeMillis()))
      throw new IllegalStateException("No job recorded yet. Earn job XP first.");
    dirty = true;
    status = "Session reset · " + JobXp.name(id);
    flush();
    if (dirty)
      throw new IllegalStateException(
          "Session reset, but history could not be saved. Retrying soon.");
  }

  public void accept(Component text) {
    accept(text, "Boss bar");
  }

  public void accept(Component text, String source) {
    if (store == null && Minecraft.getInstance().player != null) connect();
    syncActionWorld();
    String raw = text.getString();
    String normalized = JobXp.normalize(raw.length() > 4096 ? raw.substring(0, 4096) : raw);
    var reading = JobXp.parse(raw);
    if (source.equals("Boss bar")) bossTitles++;
    else actionBars++;
    if (reading != null) matches++;
    String sample =
        source
            + " · "
            + (reading == null ? "No job/level/XP match" : "Matched " + JobXp.name(reading.job()))
            + "\nRaw: "
            + escaped(raw)
            + "\nDecoded: "
            + escaped(normalized);
    if (!sample.equals(previousSample)) {
      samples.addFirst(sample);
      while (samples.size() > 8) samples.removeLast();
      previousSample = sample;
    }
    if (reading != null) app.pets.jobReading(reading);
    if (!app.store.config.enabled(Feature.JOBS)) return;
    long now = System.currentTimeMillis();
    dirty |= tracker.expire(now, app.store.config.jobs.timeoutMs);
    if (source.equals("Action bar") && tracker.activeSessions() > 1) {
      actionXp.reset();
      return;
    }
    if (reading == null) {
      if (source.equals("Action bar")) actionXp.petMultiplier(app.pets.tracker.jobMultiplier(actionXp.job(), now));
      if (source.equals("Action bar")
          && app.store.config.jobs.experimentalXp
          && actionXp.sample(raw, now))
        status = "Tracking " + JobXp.name(actionXp.job());
      return;
    }
    var before = tracker.data.jobs.get(reading.job());
    double gain = before == null ? 0 : JobMessages.gap(before, reading);
    long priorGain = before == null || before.active == null ? 0 : before.active.lastGain;
    if (gain > .0001
        && (hiddenJob.equals(reading.job()) || hiddenJob.equals("*"))
        && now - priorGain >= app.store.config.jobs.unhideAfterIdleMs) {
      hiddenJob = "";
      hiddenSession = null;
    }
    if (gain > .0001 && reading.job().equals("mineur") && app.tools != null) app.tools.miningXp();
    dirty |= tracker.observe(reading, now, app.store.config.jobs.timeoutMs);
    if (tracker.activeSessions() > 1) actionXp.reset();
    else actionXp.authoritative(reading.job(), gain, now);
    status =
        "Tracking " + JobXp.name(reading.job()) + " · level " + reading.level() + " · " + source;
  }

  public boolean hideBoss(String title) {
    if (!app.store.config.enabled(Feature.JOBS)) return false;
    var settings = app.store.config.jobs;
    return settings.hideXpBossBar && JobXp.parse(title) != null
        || settings.hideJobsHeader && jobsHeader(title);
  }
  public static boolean jobsHeader(String title) {
    String s = JobXp.normalize(title);
    return JobXp.NAMES.keySet().stream().filter(s::contains).count() >= 4;
  }
  public void personalStats(JobXp.Reading reading) {
    var j = tracker.data.jobs.computeIfAbsent(reading.job(), id -> { var n = new JobTracker.Job(); n.id = id; return n; });
    j.level = reading.level(); j.xp = reading.xp(); j.required = reading.required(); j.updated = System.currentTimeMillis();
    dirty = true;
  }

  public JobTracker.Job displayJob(JobTracker.Job original) {
    if (!app.store.config.jobs.experimentalXp || tracker.activeSessions() > 1) return original;
    double pending = actionXp.pending(original.id, System.currentTimeMillis());
    if (pending <= 0 || original.active == null) return original;
    // The preview is detached. Unconfirmed XP never enters the persisted history.
    if (cachedOriginal == original
        && cachedBaseXp == original.xp
        && cachedPending == pending
        && cachedGain == original.active.lastGain) return cachedDisplay;
    cachedOriginal = original;
    cachedBaseXp = original.xp;
    cachedPending = pending;
    cachedGain = original.active.lastGain;
    var copy = ConfigStore.copy(original, JobTracker.Job.class);
    copy.xp = Math.min(copy.required, copy.xp + pending);
    copy.active.gain(pending, actionXp.sampledAt());
    copy.active.xp += pending;
    copy.active.lastGain = Math.max(copy.active.lastGain, actionXp.sampledAt());
    cachedDisplay = copy;
    return copy;
  }

  private static String escaped(String text) {
    StringBuilder out = new StringBuilder();
    text.codePoints()
        .limit(512)
        .forEach(
            cp -> {
              if (Character.getType(cp) == Character.PRIVATE_USE || Character.isISOControl(cp))
                out.append(String.format(java.util.Locale.ROOT, "<U+%04X>", cp));
              else out.appendCodePoint(cp);
            });
    if (text.codePointCount(0, text.length()) > 512) out.append("…");
    return out.toString();
  }

  public List<String> diagnostics() {
    List<String> lines = new ArrayList<>();
    lines.add("SkyblockFlow 1.3.0 · Job detection");
    lines.add("Tracking: " + (app.store.config.enabled(Feature.JOBS) ? "enabled" : "disabled"));
    lines.add(
        "Overlay: "
            + (app.store.config.jobs.showOverlay ? "on" : "off")
            + " · Visible with F11: "
            + app.store.config.visible(Feature.JOBS));
    lines.add("Boss titles received: " + bossTitles + " · Action-bar messages: " + actionBars);
    lines.add("Matching updates: " + matches + " · Recorded jobs: " + tracker.data.jobs.size());
    lines.add("Status: " + status);
    if (app.closest != null) lines.add("Ranking: " + app.closest.diagnostics());
    lines.add("Excalia image labels are decoded before reading level and XP.");
    if (samples.isEmpty())
      lines.add("No HUD messages received yet. Join the server and earn job XP.");
    for (String sample : samples) {
      lines.add("");
      lines.addAll(List.of(sample.split("\n")));
    }
    return lines;
  }

  public java.nio.file.Path exportDiagnostics() throws java.io.IOException {
    var file = app.store.directory().resolve("job-detection.txt");
    java.nio.file.Files.writeString(file, String.join("\n", diagnostics()));
    return file;
  }

  @Override
  public void add(
      UUID id,
      Component name,
      float progress,
      BossEvent.BossBarColor color,
      BossEvent.BossBarOverlay overlay,
      boolean dark,
      boolean music,
      boolean fog) {
    app.rocket.gauge(id, name.getString());
    accept(name);
  }

  @Override
  public void updateName(UUID id, Component name) {
    app.rocket.gauge(id, name.getString());
    accept(name);
  }

  @Override
  public void remove(UUID id) {
    app.rocket.removed(id);
  }

  public List<String> report() {
    tick();
    flush();
    List<String> lines = new ArrayList<>();
    lines.add("SkyblockFlow · Time to level 200 · last completed session pace");
    if (tracker.data.jobs.isEmpty()) {
      lines.add("No jobs recorded yet. Earn XP with Job tracker enabled in F7.");
      return lines;
    }
    long now = System.currentTimeMillis();
    for (String id : JobXp.NAMES.keySet()) {
      var j = tracker.data.jobs.get(id);
      if (j == null) continue;
      var session = j.last != null ? j.last : j.active;
      double rate =
          session == null
              ? 0
              : j.last != null
                  ? session.rate(now)
                  : session.rate(now, app.store.config.jobs.rateWindowMs);
      String estimate = j.level >= 200 ? "Level 200 reached!" : JobXp.eta(j.remaining(200), rate);
      lines.add(
          JobXp.name(id)
              + " · L"
              + j.level
              + " → 200: "
              + estimate
              + (rate > 0 ? " · " + JobXp.amount(rate) + " XP/min" : "")
              + (j.last == null && j.active != null ? " (current session)" : ""));
    }
    lines.add("F7 → Job tracker for details.");
    return lines;
  }
}
