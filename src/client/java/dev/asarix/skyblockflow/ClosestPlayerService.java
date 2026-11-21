package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.*;

/** One command at a time; only reads responses correlated with this measurement. */
public final class ClosestPlayerService {
  public record Result(String name, int rank, JobXp.Reading reading, long at, boolean first) {}

  private enum Phase {
    IDLE,
    PAGE_DELAY,
    PAGE_WAIT,
    STATS_DELAY,
    STATS_WAIT,
    DRAIN
  }

  private final SkyblockFlowClient app;
  private final Map<String, Result> results = new HashMap<>();
  private final JobStatsReplyFilter statsReplies = new JobStatsReplyFilter();
  public final IslandMeasurements islands = new IslandMeasurements(this);
  public RankHistory history = new RankHistory();
  private Path historyFile;
  private final Deque<String> pending = new ArrayDeque<>();
  private Object level;
  private boolean first, contiguous;
  private int selfPosition;
  private final Deque<JobMessages.Rank> statsQueue = new ArrayDeque<>();
  private final Map<Integer, JobXp.Reading> readings = new HashMap<>();
  private final Map<String, String> leads = new HashMap<>();
  private int total, completed;
  private final Map<String, String> outcomes = new HashMap<>();
  private Phase phase = Phase.IDLE;
  private String job = "",
      self = "",
      target = "",
      lastBoard = "",
      stableBoard = "",
      hiddenBoard = "";
  private long due, sentAt, lastCommand, stableAt, quietUntil, scanStarted;
  private int page, pageCount, requestedPage, targetRank;
  private boolean sending, header;
  private String passiveTarget = "", passiveJob = "", passiveFingerprint = "", passiveStable = "";
  private boolean passiveHeader, quietPersonal;
  private long passiveUntil, passiveStableAt;
  private final TreeMap<Integer, JobMessages.Rank> ranks = new TreeMap<>();
  public String status = "Waiting for a job session";

  public ClosestPlayerService(SkyblockFlowClient app) {
    this.app = app;
  }

  private long now() {
    return System.nanoTime() / 1000000;
  }

  public ClosestSettings settings() {
    return app.store.config.jobs.closest;
  }


  public String diagnostics() {
    var objective = sidebar();
    return "Phase "
        + phase
        + " · "
        + status
        + " · job="
        + job
        + " · self="
        + self
        + " · page="
        + page
        + " · target="
        + target
        + " · ranks="
        + ranks
        + " · sidebar="
        + fingerprint(objective);
  }

  public Result result(String job) {
    var neighbor = neighbor(job);
    if (neighbor != null) {
      if (neighbor.reading() == null || !freshXp(neighbor.xpAt())) {
        var measured = results.get(job);
        return measured != null && measured.first() && measured.reading() == null && freshXp(measured.at()) ? measured : null;
      }
      var rank = history.boards.get(job);
      return new Result(neighbor.name(), neighbor.rank(), neighbor.reading(), neighbor.xpAt(), rank != null && rank.position() == 1);
    }
    var cached = results.get(job);
    return cached != null && freshXp(cached.at()) ? cached : null;
  }

  private boolean freshXp(long at) {
    long age = System.currentTimeMillis() - at;
    return at > 0 && age >= 0 && age <= settings().maxAgeMs;
  }

  public RankHistory.Player neighbor(String job) { return history.neighbor(job, Minecraft.getInstance().getUser().getName()); }
  /** Called only by the player's stats command or Refresh button. */
  public void requestPersonalStats() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || busy()) return;
    quietPersonal = true;
    passiveTarget = mc.getUser().getName(); passiveJob = ""; passiveHeader = false;
    passiveUntil = now() + settings().responseTimeoutMs;
    send("jobs stats " + mc.getUser().getName());
  }

  public String nextCommand() {
    if (phase == Phase.PAGE_DELAY) return "jobs top " + job + " " + page;
    if (phase == Phase.STATS_DELAY) return "jobs stats " + target;
    if (phase == Phase.IDLE && !pending.isEmpty()) return "jobs top " + pending.peekFirst() + " 1";
    if (!busy() && islands.needsOwner()) return "is info";
    return "";
  }

  /** Each invocation corresponds to a click on this exact displayed command. */
  public void executeNext(String displayed) {
    if (displayed.isBlank() || !displayed.equals(nextCommand())) return;
    if (Minecraft.getInstance().player == null) return;
    if (now() < Math.max(quietUntil, lastCommand + settings().commandGapMs)) {
      status = "Please wait " + Math.max(1, (Math.max(quietUntil, lastCommand + settings().commandGapMs) - now() + 999) / 1000) + " seconds";
      return;
    }
    if (displayed.equals("is info")) {
      islands.ownerRequested(); send(displayed); return;
    }
    if (phase == Phase.IDLE) beginNext();
    scanStarted = now();
    if (phase == Phase.PAGE_DELAY) {
      lastBoard = fingerprint(sidebar()); requestedPage = 0; phase = Phase.PAGE_WAIT;
      status = "Finding your rank · page " + page;
    } else if (phase == Phase.STATS_DELAY) {
      phase = Phase.STATS_WAIT; header = false;
      status = "Reading " + target + " · " + JobXp.name(job);
    } else return;
    send(displayed);
  }

  public void advance() {
    var mc = Minecraft.getInstance();
    mc.gui.setScreen(new dev.asarix.skyblockflow.ui.LeaderboardsScreen(app, mc.gui.screen()));
  }

  public boolean internalCommand() {
    return sending;
  }

  public boolean busy() {
    return phase != Phase.IDLE || !pending.isEmpty();
  }


  public void reset() {
    flushHistory();
    statsReplies.reset();
    phase = Phase.IDLE;
    results.clear();
    outcomes.clear();
    leads.clear();
    statsQueue.clear();
    readings.clear();
    ranks.clear();
    pending.clear();
    due = sentAt = lastCommand = quietUntil = 0;
    total = completed = 0;
    hiddenBoard = stableBoard = lastBoard = "";
    job = target = self = "";
    header = first = false;
    level = null;
    historyFile = null;
    history = new RankHistory();
    passiveTarget = passiveJob = passiveFingerprint = passiveStable = "";
    passiveHeader = quietPersonal = false; passiveUntil = passiveStableAt = 0;
    islands.reset();
    status = "Ready";
  }

  public void connect() {
    reset();
    var mc = Minecraft.getInstance();
    level = mc.level;
    history = new RankHistory();
    historyFile = null;
    var file =
        RankHistory.path(
            app.store.directory(), app.serverKey(), mc.getUser().getProfileId().toString());
    try {
      var queued = historyWriter.latest(file);
      history = queued == null ? RankHistory.load(file) : queued;
      historyFile = file;
    } catch (IOException e) {
      app.error(e.getMessage());
    }
  }

  private final RankHistoryWriter historyWriter = new RankHistoryWriter();
  private long historySaveDue;

  void saveHistory() {
    if (historyFile != null && historySaveDue == 0) historySaveDue = now() + 750;
  }

  private void flushHistory() {
    if (historySaveDue == 0 || historyFile == null) return;
    historySaveDue = 0;
    historyWriter.save(historyFile, history.snapshot(), message ->
        Minecraft.getInstance().schedule(() -> app.error(message)));
  }

  public void closeHistory() {
    flushHistory();
    historyWriter.close();
  }

  void chat(String message) {
    var player = Minecraft.getInstance().player;
    if (player != null)
      Minecraft.getInstance()
          .gui
          .hud
          .getChat()
          .addClientSystemMessage(dev.asarix.skyblockflow.ui.DisplayText.literal("SkyblockFlow · " + message));
  }

  void confirm(String id, String label, int rank, long at, boolean unambiguous) {
    String change = history.observe(id, rank, at, unambiguous);
    if (!change.isEmpty()) {
      chat(label + " · " + change);
      var style = app.store.config.alarmStyles.get(AlarmKind.RANK);
      if (style != null && style.enabled)
        app.notifyGeneral("Leaderboard", label, change, new SoundSettings());
    }
  }

  public String outcome(String id) {
    if (phase != Phase.IDLE && job.equals(id)) return status;
    if (pending.contains(id)) return "Queued";
    return outcomes.getOrDefault(id, "");
  }

  public String lead(String id) {
    var position = history.boards.get(id);
    if (position == null || position.position() != 1) return "";
    var r = result(id);
    var own = app.jobs.tracker.data.jobs.get(id);
    if (r == null || r.reading == null || own == null) return "Open leaderboard to read #2";
    double gap = -JobMessages.gap(own, r.reading);
    return Math.abs(gap) < .005 ? "Tied" : gap < 0 ? "Refresh ranking" : JobXp.amount(gap) + " XP · " + r.name;
  }

  public String progress() {
    return total == 0
        ? ""
        : completed
            + "/"
            + total
            + " complete"
            + (pending.isEmpty() ? "" : " · remaining: " + String.join(", ", pending));
  }

  public void measureCurrent() {
    var selected = app.jobs.tracker.selected("");
    if (selected == null) {
      status = "No current job · choose a job or Measure all";
      chat(status);
    } else measure(selected.id);
  }

  public void measureAll() {
    start(JobXp.NAMES.keySet());
  }

  public void measure(String id) {
    if (JobXp.NAMES.containsKey(id)) start(List.of(id));
  }

  private void start(Collection<String> jobs) {
    if (busy()) return;
    if (Minecraft.getInstance().player == null || !app.store.config.enabled(Feature.LEADERBOARDS)) {
      status = "Join a world and enable Leaderboards";
      return;
    }
    total = jobs.size();
    completed = 0;
    pending.addAll(jobs);
    beginNext();
  }

  private void beginNext() {
    scanStarted = now();
    selfPosition = 0;
    statsQueue.clear();
    readings.clear();
    job = pending.removeFirst();
    self = Minecraft.getInstance().player.getGameProfile().name();
    target = "";
    ranks.clear();
    page = 1;
    pageCount = settings().maxPages;
    requestedPage = 0;
    header = first = false;
    status = "Measuring " + JobXp.name(job);
    queuePage();
  }

  public boolean awaitingStep() { return !nextCommand().isEmpty(); }

  private void queuePage() {
    phase = Phase.PAGE_DELAY;
    due =
        Math.max(
            quietUntil,
            lastCommand + settings().commandGapMs);
  }

  private void finish(String message) {
    app.runtimeEvent("Job ranking · " + JobXp.name(job) + " · " + phase + " · page " + page
        + (target.isEmpty() ? "" : " · " + target) + " · " + message);
    chat(JobXp.name(job) + " · " + message);
    outcomes.put(job, message);
    completed++;
    status = message;
    phase = Phase.IDLE;
  }

  public void cancel() {
    cancel("Measurement cancelled");
    islands.cancelReminder();
  }

  private void cancel(String reason) {
    if (phase == Phase.PAGE_WAIT || phase == Phase.STATS_WAIT)
      quietUntil = sentAt + settings().responseTimeoutMs;
    statsQueue.clear();
    readings.clear();
    if (busy()) {
      status = reason;
      outcomes.put(job, reason);
    }
    pending.clear();
    phase = Phase.IDLE;
    hiddenBoard = "";
  }

  public void commandSent(String command) {
    if (sending) return;
    statsReplies.reset();
    passiveTarget = passiveJob = ""; passiveHeader = quietPersonal = false;
    cancel("Measurement cancelled by your command");
    lastCommand = now();
    String clean = command.strip().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
    var parts = clean.split("\\s+");
    if (parts.length >= 2 && (parts[0].equals("jobs") || parts[0].equals("job")) && (parts[1].equals("stats") || parts[1].equals("top"))) {
      passiveUntil = lastCommand + settings().responseTimeoutMs;
      passiveTarget = parts.length > 2 && !JobXp.NAMES.containsKey(JobXp.normalize(parts[2])) ? parts[2] : Minecraft.getInstance().getUser().getName();
      passiveJob = parts.length > 2 && JobXp.NAMES.containsKey(JobXp.normalize(parts[2])) ? JobXp.normalize(parts[2]) : "";
      passiveFingerprint = "";
    }
    if (command.strip().toLowerCase(Locale.ROOT).matches("/?jobs?\\s+(top|stats)(?:\\s.*)?"))
      quietUntil = lastCommand + settings().responseTimeoutMs;
    islands.command(
        command.strip().toLowerCase(Locale.ROOT).replaceFirst("^/", ""),
        System.currentTimeMillis());
  }

  private void send(String command) {
    if (phase == Phase.STATS_WAIT && settings().hideOutput)
      statsReplies.expect(target, now(), settings().responseTimeoutMs);
    sending = true;
    try {
      Minecraft.getInstance().player.connection.sendCommand(command);
    } finally {
      sending = false;
    }
    sentAt = lastCommand = now();
    stableAt = 0;
    stableBoard = "";
  }

  public static Objective sidebar() {
    var mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) return null;
    var board = mc.level.getScoreboard();
    var team = board.getPlayersTeam(mc.player.getScoreboardName());
    Objective objective =
        team == null || team.getColor().isEmpty()
            ? null
            : board.getDisplayObjective(team.getColor().get().displaySlot());
    return objective != null ? objective : board.getDisplayObjective(DisplaySlot.SIDEBAR);
  }

  public static List<String> lines(Objective objective) {
    if (objective == null) return List.of();
    var board = objective.getScoreboard();
    return board.listPlayerScores(objective).stream()
        .filter(e -> !e.isHidden())
        .sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed())
        .map(
            e ->
                PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.owner()), e.ownerName())
                    .getString())
        .toList();
  }

  private String fingerprint(Objective objective) {
    return objective == null
        ? ""
        : objective.getDisplayName().getString() + "\n" + String.join("\n", lines(objective));
  }

  public boolean hideSidebar() {
    if (!app.store.config.enabled(Feature.LEADERBOARDS) || !settings().hideOutput) return false;
    var objective = sidebar();
    if (objective == null) return false;
    String text = JobXp.normalize(objective.getDisplayName().getString());
    boolean expected = text.contains("top") && text.contains(job);
    if (phase != Phase.IDLE && expected) return true;
    return !hiddenBoard.isEmpty() && hiddenBoard.equals(fingerprint(objective));
  }

  public void tick() {
    if (historySaveDue != 0 && now() >= historySaveDue) flushHistory();
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) {
      if (level != null) reset();
      return;
    }
    if (level != mc.level) {
      connect();
      return;
    }
    if (!app.store.config.enabled(Feature.LEADERBOARDS)) {
      if (busy()) cancel("Leaderboards disabled");
      hiddenBoard = "";
      islands.reset();
      return;
    }
    islands.tick(mc, System.currentTimeMillis(), app.inventoryRevision);
    if (!busy()) {
      observePassiveBoard();
    }
    if (mc.gui.screen() != null && !(mc.gui.screen() instanceof dev.asarix.skyblockflow.ui.FlowScreen)
        || app.celebrations.active()) {
      if (busy()) cancel("Measurement cancelled by a screen or scene change");
      return;
    }
    long now = now();
    if (phase == Phase.IDLE) return;
    if ((phase == Phase.PAGE_WAIT || phase == Phase.STATS_WAIT)
        && now - sentAt > settings().responseTimeoutMs) {
      if (!first) results.remove(job);
      finish(phase == Phase.PAGE_WAIT
          ? "No leaderboard page " + page + " recognized · last confirmed rank retained"
          : "No " + JobXp.name(job) + " XP recognized for " + target + " · last confirmed rank retained");
      return;
    }
    if (phase == Phase.PAGE_WAIT) {
      var objective = sidebar();
      String fingerprint = fingerprint(objective);
      String title =
          objective == null ? "" : JobXp.normalize(objective.getDisplayName().getString());
      if (title.contains("top")
          && title.contains(job)
          && (requestedPage == page || !fingerprint.equals(lastBoard))) {
        if (!fingerprint.equals(stableBoard)) {
          stableBoard = fingerprint;
          stableAt = now;
        }
        if (now - stableAt >= 400 && now - sentAt >= 600) acceptPage(lines(objective), fingerprint);
      }
    } else if (phase == Phase.DRAIN && now >= due) finish("Measured " + results.get(job).name());
  }

  private void acceptPage(List<String> lines, String fingerprint) {
    List<JobMessages.Rank> entries =
        lines.stream()
            .map(JobMessages::rank)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(JobMessages.Rank::rank))
            .toList();
    if (entries.isEmpty()
        || entries.getFirst().rank() <= (page - 1) * 15
        || entries.getLast().rank() > page * 15) return;
    // A still-visible old page must never advance the scan.
    if (page > 1 && ranks.containsKey(entries.getFirst().rank())) return;
    for (var entry : entries) {
      if (ranks.containsKey(entry.rank())
          || ranks.values().stream().anyMatch(r -> r.name().equalsIgnoreCase(entry.name()))) {
        results.remove(job);
        finish("Conflicting or tied positions · try again");
        return;
      }
      ranks.put(entry.rank(), entry);
    }
    for (var entry : entries) history.cacheRank(job, entry, System.currentTimeMillis());
    hiddenBoard = fingerprint;
    for (var entry : entries)
      if (entry.name().equalsIgnoreCase(self)) {
        leads.remove(job);
        first = entry.rank() == 1;
        selfPosition = entry.rank();
        contiguous = ranks.headMap(selfPosition, true).size() == selfPosition;
        confirm(
            job,
            JobXp.name(job),
            selfPosition,
            System.currentTimeMillis(),
            contiguous && !history.boards.containsKey(job));
        saveHistory();
        var neighbor = ranks.get(first ? 2 : entry.rank() - 1);
        results.remove(job);
        outcomes.remove(job);
        if (first) results.put(job, new Result("", 1, null, System.currentTimeMillis(), true));
        if (neighbor == null) {
          boolean alone = first && requestedPage == 1 && pageCount == 1 && lines.size() == 1;
          if (alone) {
            history.players.get(job).replaceAll((name, p) -> p.name().equalsIgnoreCase(self) ? p
                : new RankHistory.Player(0, p.name(), p.level(), p.reading(), p.at(), p.xpAt()));
            confirm(job, JobXp.name(job), 1, System.currentTimeMillis(), true);
            saveHistory();
          }
          finish(alone ? "No second place listed" : "Adjacent rank missing from response");
          return;
        }
        targetRank = neighbor.rank();
        statsQueue.add(neighbor);
        var own = app.jobs.tracker.data.jobs.get(job);
        if (own != null
            && own.level == entry.level()
            && System.currentTimeMillis() - own.updated <= settings().maxAgeMs) {
          readings.put(selfPosition, new JobXp.Reading(job, own.level, own.xp, own.required, 0));
        } else if (first || history.baseline(job) != selfPosition) statsQueue.add(entry);
        // On a gain, the player just passed is below us. Check that side too when the levels tie.
        var below = ranks.get(selfPosition + 1);
        if (!first
            && history.baseline(job) > selfPosition
            && below != null
            && below.level() == entry.level()) statsQueue.add(below);
        queueStats();
        return;
      }
    if (page >= Math.min(pageCount, settings().maxPages)) {
      finish("Name not found within page limit");
      return;
    }
    page++;
    queuePage();
  }

  /** Called only for unsigned game messages, before display. Never consumes player chat. */
  public boolean capture(Component text) {
    boolean hidden = settings().hideOutput && statsReplies.capture(text.getString(), now());
    return captureObserved(text) || hidden;
  }

  private boolean captureObserved(Component text) {
    if ((!app.store.config.enabled(Feature.LEADERBOARDS) && !app.store.config.enabled(Feature.JOBS))
        || Minecraft.getInstance().level != level)
      return false;
    var mc = Minecraft.getInstance();
    if (mc.player == null) return false;
    if (app.store.config.enabled(Feature.LEADERBOARDS)) islands.capture(text.getString(), System.currentTimeMillis());
    if (!busy()) return passiveStats(text.getString());
    if (!busy()
        || (mc.gui.screen() != null
            && !(mc.gui.screen() instanceof dev.asarix.skyblockflow.ui.FlowScreen))
        || app.celebrations.active()) return false;
    if (now() - scanStarted > settings().maxAgeMs
        || now() - sentAt > settings().responseTimeoutMs) return false;
    if (phase == Phase.PAGE_WAIT) {
      int[] pagination = JobMessages.page(text.getString());
      if (pagination == null || pagination[0] != page) return false;
      requestedPage = page;
      pageCount = pagination[1];
      return settings().hideOutput;
    }
    if (phase != Phase.STATS_WAIT && phase != Phase.DRAIN) return false;
    boolean allRecognized = true, any = false;
    for (String raw : text.getString().split("\\n")) {
      String plain = raw.replaceAll("§[0-9a-fk-orxA-FK-ORX]", "").strip();
      // Recognize the exact player header before accepting job XP.
      if (JobMessages.statsHeader(plain, target)) {
        header = true;
        any = true;
        continue;
      }
      if (phase == Phase.STATS_DELAY) {
        allRecognized = false;
        continue;
      }
      var reading = JobMessages.stats(raw);
      if (header && reading != null) {
        any = true;
        history.cacheStats(target, reading, System.currentTimeMillis());
        if (target.equalsIgnoreCase(mc.getUser().getName())) app.jobs.personalStats(reading);
        saveHistory();
        if (reading.job().equals(job) && phase == Phase.STATS_WAIT) {
          var expected = statsQueue.removeFirst();
          if (reading.level() != expected.level()) {
            finish("Level changed during measurement · measure again");
            return false;
          }
          readings.put(expected.rank(), reading);
          if (!statsQueue.isEmpty()) queueStats();
          else {
            saveComparison();
            phase = Phase.DRAIN;
            due = now() + 1500;
          }
        }
      } else if (plain.matches(".*[A-Za-z0-9_].*") && reading == null) {
        header = false;
        allRecognized = false;
      } else if (header && (plain.isEmpty() || plain.matches("[─━═_\\-= ]+"))) {
        any = true;
      } else allRecognized = false;
    }
    return settings().hideOutput && any && allRecognized;
  }

  private void observePassiveBoard() {
    if (now() > passiveUntil) return;
    var board = sidebar();
    if (board == null) return;
    String title = JobXp.normalize(board.getDisplayName().getString());
    String id = JobXp.NAMES.keySet().stream().filter(title::contains).findFirst().orElse("");
    if (id.isEmpty() || !title.contains("top")) return;
    String fp = fingerprint(board);
    if (fp.equals(passiveFingerprint)) return;
    if (!fp.equals(passiveStable)) { passiveStable = fp; passiveStableAt = now(); return; }
    if (now() - passiveStableAt < 400) return;
    var entries = lines(board).stream().map(JobMessages::rank).filter(Objects::nonNull).toList();
    if (entries.isEmpty()) return;
    long at = System.currentTimeMillis();
    for (var rank : entries) {
      history.cacheRank(id, rank, at);
      if (rank.name().equalsIgnoreCase(Minecraft.getInstance().getUser().getName()))
        confirm(id, JobXp.name(id), rank.rank(), at, false);
    }
    passiveFingerprint = fp; saveHistory();
  }

  private boolean passiveStats(String raw) {
    if (passiveTarget.isEmpty() || now() > passiveUntil) { quietPersonal = false; return false; }
    boolean changed = false, any = false, allRecognized = true;
    for (String line : raw.split("\\n")) {
      String plain = line.replaceAll("§[0-9a-fk-orxA-FK-ORX]", "").strip();
      if (JobMessages.statsHeader(plain, passiveTarget)) {
        passiveHeader = true; any = true; continue;
      }
      var reading = JobMessages.stats(line);
      if (passiveHeader && reading != null) {
        history.cacheStats(passiveTarget, reading, System.currentTimeMillis());
        if (passiveTarget.equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) app.jobs.personalStats(reading);
        changed = any = true;
      } else if (passiveHeader && (plain.isEmpty() || plain.matches("[─━═_\\-= ]+"))) {
        any = true;
      } else {
        allRecognized = false;
        if (plain.matches(".*[A-Za-z0-9_].*")) passiveHeader = false;
      }
    }
    if (changed) { saveHistory(); app.jobs.flush(); }
    // Only our own dashboard lookup is silent; manual lookups and unrelated chat stay visible.
    return quietPersonal && any && allRecognized;
  }

  private void queueStats() {
    target = statsQueue.getFirst().name();
    header = false;
    phase = Phase.STATS_DELAY;
    due = lastCommand + settings().commandGapMs;
    status = "Next · " + target + " stats";
  }

  private void saveComparison() {
    long at = System.currentTimeMillis();
    var own = readings.get(selfPosition);
    var other = readings.get(targetRank);
    String rival = ranks.get(targetRank).name();
    double gap =
        own == null
            ? Double.NaN
            : JobMessages.gap(job, own.level(), own.xp(), own.required(), other);
    boolean distinct = first ? gap < 0 : gap > 0;
    if (own == null) distinct = ranks.get(selfPosition).level() < other.level();
    if (!first && history.baseline(job) > selfPosition) {
      var below = ranks.get(selfPosition + 1);
      var lowerXp = readings.get(selfPosition + 1);
      distinct &=
          below != null
              && (below.level() < ranks.get(selfPosition).level()
                  || own != null
                      && lowerXp != null
                      && JobMessages.gap(job, own.level(), own.xp(), own.required(), lowerXp) < 0);
    }
    confirm(job, JobXp.name(job), selfPosition, at, contiguous && distinct);
    saveHistory();
    results.put(job, new Result(rival, targetRank, other, at, first));
    if (first && own != null) {
      String lead =
          gap == 0
              ? "Tied"
              : gap > 0
                  ? "Inconsistent XP · remeasure"
                  : (Math.abs(own.level() - other.level()) > 1 ? "~" : "")
                      + JobXp.amount(-gap)
                      + " XP · "
                      + rival;
      leads.put(job, lead);
    }
  }

  public List<JobOverlay.Row> rows(JobTracker.Job selfJob, JobSettings settings) {
    var r = result(selfJob.id);
    String gap = "Measure to update", eta = "—";
    if (r != null) {
      if (r.first) {
        gap = "You are #1";
        eta = "00:00:00";
      } else {
        double distance = JobMessages.gap(selfJob, r.reading);
        boolean estimated = Math.abs(selfJob.level - r.reading.level()) > 1;
        gap = (estimated ? "~" : "") + JobXp.amount(Math.max(0, distance)) + " · " + r.name;
        var session = selfJob.displayed();
        eta =
            distance <= 0
                ? "Remeasure rank"
                : JobXp.eta(
                    distance,
                    session == null
                        ? 0
                        : session.rate(System.currentTimeMillis(), settings.rateWindowMs));
      }
    }
    List<JobOverlay.Row> rows = new ArrayList<>();
    if (settings.showClosestXp) rows.add(new JobOverlay.Row("XP to closest player", gap));
    if (settings.showClosestTime) rows.add(new JobOverlay.Row("Time to closest player", eta));
    if (r != null && r.first) {
      String lead = lead(selfJob.id);
      rows.add(
          new JobOverlay.Row(
              "XP ahead of second place", lead.isEmpty() ? "Open leaderboard to read #2" : lead));
    }
    return rows;
  }
}
