package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.ExcaliaApiClient.Feed;
import dev.asarix.skyblockflow.core.ExcaliaApiData.*;
import dev.asarix.skyblockflow.core.Models.Feature;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.Minecraft;

/** Coordinates cached public data with the current connection; never sends a game command. */
public final class ExcaliaApiService {
  private final SkyblockFlowClient app;
  private final ExcaliaApiClient client;
  private final EnumMap<Feed, ExcaliaApiClient.Snapshot> applied = new EnumMap<>(Feed.class);
  private RankHistory history;
  private ExcaliaApiClient.Snapshot appliedDetail;
  private UUID islandId;
  private long nextTick;
  private record Pending(List<String> jobs, Consumer<List<String>> fallback, Object level, long deadline) {}
  private Pending pending;
  public String status = "Excalia Season 3 · public data";

  public ExcaliaApiService(SkyblockFlowClient app) {
    this.app = app;
    client = new ExcaliaApiClient(app.store.directory().resolve("public-data/s3"));
    ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> client.close());
  }
  public ExcaliaApiSettings settings() { return app.store.config.tools.publicData; }
  public boolean connected() { return app.recipes != null && app.recipes.onExcalia() && Minecraft.getInstance().player != null; }
  public boolean rankingsEnabled() { return connected() && settings().leaderboards && app.store.config.enabled(Feature.LEADERBOARDS); }
  public boolean pending() { return pending != null; }
  public void cancel() { pending = null; }

  public boolean measure(Collection<String> jobs, Consumer<List<String>> fallback) {
    if (!rankingsEnabled()) return false;
    pending = new Pending(List.copyOf(jobs), fallback, Minecraft.getInstance().level, System.currentTimeMillis() + 40_000);
    status = app.closest.status = "Refreshing public rankings…";
    refreshRankings(); finishMeasurement(); return true;
  }
  public void refreshRankings() {
    if (!rankingsEnabled()) return;
    client.request(Feed.JOBS); client.request(Feed.FISHING); client.request(Feed.ISLANDS);
  }
  public String refreshPrices() {
    if (!connected()) return "Join Excalia Season 3 to refresh prices";
    if (settings().market) client.request(Feed.MARKET);
    if (settings().shopPrices) client.request(Feed.ECONOMY);
    return "Refreshing prices when the next snapshot is available";
  }
  private ExcaliaApiClient.Snapshot usable(Feed feed) {
    var snapshot = client.snapshot(feed);
    if (snapshot == null) return null;
    long at = switch (snapshot.data()) {
      case Jobs j -> j.at(); case Islands i -> i.at(); case Market m -> m.at(); case Economy e -> e.at();
      default -> snapshot.receivedAt();
    };
    long age = System.currentTimeMillis() - at;
    long limit = feed == Feed.MARKET || feed == Feed.ECONOMY ? ExcaliaApiClient.MAX_QUOTE_AGE : feed.ttl * 2;
    return age >= 0 && age <= limit ? snapshot : null;
  }
  public List<SeasonThreeCatalog.ShopQuote> prices(String item) {
    if (!connected() || !settings().shopPrices) return List.of();
    var snapshot = usable(Feed.ECONOMY);
    return snapshot == null ? List.of() : ((Economy)snapshot.data()).prices().getOrDefault(item, List.of());
  }
  public String priceSource(String shop) {
    var snapshot = usable(Feed.ECONOMY);
    boolean cached = snapshot != null && System.currentTimeMillis() - snapshot.receivedAt() > Feed.ECONOMY.ttl * 2;
    return "S3 " + shop + (cached ? " (cached)" : "");
  }
  public List<String> market(String item, String name, int count) {
    if (!connected() || !settings().market) return List.of();
    var snapshot = usable(Feed.MARKET);
    if (snapshot == null) return List.of();
    var market = (Market)snapshot.data();
    var lines = market.lines(item, name, count);
    if (System.currentTimeMillis() - market.at() > 120_000)
      return lines.stream().map(s -> s + " · cached quote").toList();
    return lines;
  }
  public boolean personalStats() {
    if (!rankingsEnabled()) return false;
    refreshRankings(); applyRankings();
    // A single stats command is still needed if any job is outside the published tops.
    return JobXp.NAMES.keySet().stream().allMatch(this::covered);
  }
  private void tick() {
    var mc = Minecraft.getInstance(); long now = System.currentTimeMillis();
    if (!connected()) { pending = null; history = null; applied.clear(); islandId = null; appliedDetail = null; return; }
    if (history != app.closest.history) {
      history = app.closest.history; applied.clear(); appliedDetail = null; islandId = null;
    }
    if (now < nextTick) return;
    nextTick = now + 1000;
    if (rankingsEnabled()) { refreshRankings(); applyRankings(); }
    if (app.store.config.tools.shopValues) {
      if (settings().market) client.request(Feed.MARKET);
      if (settings().shopPrices) client.request(Feed.ECONOMY);
    }
    if (pending != null && pending.level != mc.level) pending = null;
    finishMeasurement();
  }
  private void finishMeasurement() {
    if (pending == null) return;
    if (!rankingsEnabled()) { pending = null; return; }
    if (!client.settled(Feed.JOBS) || pending.jobs.contains("pecheur") && !client.settled(Feed.FISHING)) {
      if (System.currentTimeMillis() < pending.deadline) return;
    }
    applyRankings();
    var request = pending; pending = null;
    var missing = request.jobs.stream().filter(j -> !covered(j)).toList();
    if (missing.isEmpty()) status = app.closest.status = "Public rankings refreshed";
    else {
      status = "Use an in-game lookup for jobs outside the published rankings";
      request.fallback.accept(missing);
    }
  }
  private Jobs jobs(String job) {
    var snapshot = usable(job.equals("pecheur") ? Feed.FISHING : Feed.JOBS);
    return snapshot == null ? null : (Jobs)snapshot.data();
  }
  private JobEntry own(List<JobEntry> board) {
    UUID uuid = Minecraft.getInstance().getUser().getProfileId();
    return board.stream().filter(p -> p.uuid().equals(uuid)).findFirst().orElse(null);
  }
  private boolean covered(String job) {
    var jobs = jobs(job); if (jobs == null) return false;
    var board = jobs.boards().getOrDefault(job, List.of());
    var own = own(board); if (own == null) return false;
    int neighbor = own.rank() == 1 ? 2 : own.rank() - 1;
    return board.size() == 1 || board.stream().anyMatch(p -> p.rank() == neighbor);
  }
  private void applyRankings() {
    if (!rankingsEnabled() || app.closest.commandMeasurementActive()) return;
    for (var feed : List.of(Feed.JOBS, Feed.FISHING)) {
      var snapshot = usable(feed);
      if (snapshot == null || applied.get(feed) == snapshot) continue;
      var data = (Jobs)snapshot.data();
      for (var entry : data.boards().entrySet()) {
        String job = entry.getKey(); var board = entry.getValue(); var own = own(board);
        var prior = app.closest.history.boards.get(job);
        if (prior != null && prior.at() > data.at()) continue;
        for (var player : board) {
          var old = app.closest.history.players.getOrDefault(job, Map.of()).get(player.name().toLowerCase(Locale.ROOT));
          if (old != null && old.at() > data.at()) continue;
          app.closest.history.cacheRank(job, new JobMessages.Rank(player.rank(), player.reading().level(), player.name()), data.at());
          app.closest.history.cacheStats(player.name(), player.reading(), data.at());
        }
        if (own != null) {
          // Follow account identity even after a nickname change.
          String name = Minecraft.getInstance().getUser().getName();
          app.closest.history.cacheRank(job, new JobMessages.Rank(own.rank(), own.reading().level(), name), data.at());
          app.closest.history.cacheStats(name, own.reading(), data.at());
          app.closest.confirm(job, JobXp.name(job), own.rank(), data.at(), isolated(board, own));
          app.jobs.publicStats(own.reading(), data.at());
        } else if (prior != null && prior.position() <= board.size()) {
          app.closest.history.boards.remove(job);
          var known = app.closest.history.players.get(job);
          if (known != null) known.remove(Minecraft.getInstance().getUser().getName().toLowerCase(Locale.ROOT));
        }
      }
      app.closest.saveHistory(); applied.put(feed, snapshot);
    }
    var snapshot = usable(Feed.ISLANDS);
    if (snapshot != null) applyIslands(snapshot);
  }
  private static boolean isolated(List<JobEntry> board, JobEntry own) {
    return board.stream().filter(p -> Math.abs(p.rank() - own.rank()) == 1).noneMatch(p ->
        p.reading().level() == own.reading().level() && Math.abs(p.reading().xp() - own.reading().xp()) < .01);
  }
  private void applyIslands(ExcaliaApiClient.Snapshot snapshot) {
    var data = (Islands)snapshot.data(); var h = app.closest.history;
    UUID self = Minecraft.getInstance().getUser().getProfileId();
    if (islandId == null && !h.apiIslandId.isBlank()) {
      try { islandId = UUID.fromString(h.apiIslandId); } catch (IllegalArgumentException ignored) { h.apiIslandId = ""; }
    }
    var candidate = data.known().stream().filter(i -> i.ownerId().equals(self)
        || !h.owner.isEmpty() && i.owner().equalsIgnoreCase(h.owner)).findFirst().orElse(null);
    if (candidate != null) islandId = candidate.id();
    if (islandId != null) client.requestDetail(islandId);
    var detailSnapshot = islandId == null ? null : client.detail(islandId);
    var detail = detailSnapshot != null && System.currentTimeMillis() - detailSnapshot.receivedAt() < 120_000
        ? (IslandDetail)detailSnapshot.data() : null;
    boolean belongs = detail != null && detail.members().contains(self);
    String owner = belongs ? detail.owner() : candidate != null && candidate.ownerId().equals(self) ? candidate.owner() : "";
    if (!owner.isEmpty()) {
      h.owner = owner; h.apiIslandId = islandId.toString();
    }
    if (applied.get(Feed.ISLANDS) != snapshot && data.at() >= h.snapshotAt) {
      h.islands.clear(); h.snapshotAt = data.at();
      for (var island : data.ranked()) {
        var row = new IslandBoard.Entry(island.rank(), island.owner().toLowerCase(Locale.ROOT), island.power(), 1,
            island.owner().equalsIgnoreCase(owner));
        h.islands.put(row.rank(), row); h.cacheIsland(row);
      }
      applied.put(Feed.ISLANDS, snapshot); app.closest.saveHistory();
    }
    if (!owner.isEmpty()) {
      var ranked = data.ranked().stream().filter(i -> i.owner().equalsIgnoreCase(owner)).findFirst().orElse(null);
      if (ranked != null) app.closest.islands.publicRank(ranked, data.ranked(), data.at());
      if (belongs && appliedDetail != detailSnapshot) {
        app.closest.islands.publicPower(detail.power(), detailSnapshot.receivedAt());
        appliedDetail = detailSnapshot; app.closest.saveHistory();
      }
    }
  }
  public void forgetIsland() { islandId = null; appliedDetail = null; app.closest.history.apiIslandId = ""; }
}
