package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.ExcaliaApiClient.Feed;
import dev.asarix.skyblockflow.core.ExcaliaApiData.*;
import dev.asarix.skyblockflow.core.Models.Feature;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.Minecraft;

/** Coordinates cached public data with the current connection; never sends a game command. */
public final class ExcaliaApiService {
  private final SkyblockFlowClient app;
  public final PlayerInspections inspections;
  private final ExcaliaApiClient client;
  private final EnumMap<Feed, ExcaliaApiClient.Snapshot> applied = new EnumMap<>(Feed.class);
  private RankHistory history;
  private ExcaliaApiClient.Snapshot appliedDetail;
  private UUID islandId;
  private long nextTick;
  private final EnumMap<Feed,ExcaliaApiClient.Snapshot> ownProgress = new EnumMap<>(Feed.class);
  private final MarketStock marketStock = new MarketStock();
  private final MarketQuotes marketQuotes = new MarketQuotes();
  private final MarketMessages marketMessages = new MarketMessages();
  private final MarketSignObserver marketSigns = new MarketSignObserver();
  private Object marketLevel;
  private record Pending(List<String> jobs, Object level, long deadline) {}
  private Pending pending;
  public String status = "Excalia Season 3 · public data";

  public ExcaliaApiService(SkyblockFlowClient app) {
    this.app = app;
    inspections=new PlayerInspections(app);
    client = new ExcaliaApiClient(app.store.directory().resolve("public-data/s3"));
    net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
      var pv = com.mojang.brigadier.builder.LiteralArgumentBuilder
          .<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("pv")
          .requires(source -> connected()).executes(ctx -> openProfile(Minecraft.getInstance().getUser().getName()));
      dispatcher.register(pv.then(com.mojang.brigadier.builder.RequiredArgumentBuilder
          .<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource,String>argument("player",
              com.mojang.brigadier.arguments.StringArgumentType.word())
          .suggests((ctx,builder)->{
            for(String name:profileSuggestions(builder.getRemaining()))builder.suggest(name);
            return builder.buildFuture();
          })
          .executes(ctx -> openProfile(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,"player")))));
    });
    ClientTickEvents.END_CLIENT_TICK.register(mc -> {tick(); inspections.tick();});
    ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {client.close(); inspections.close(); marketQuotes.close();});
    net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((text, overlay) -> {
      if (overlay || !connected() || !settings().market) return;
      var snapshot = usable(Feed.MARKET);
      var market = snapshot == null ? null : (Market)snapshot.data();
      long now = System.currentTimeMillis();
      for (String line : text.getString().split("\\n")) marketMessages.accept(line, market, marketStock, now);
    });
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
      inspections.disconnect(); marketQuotes.clear(); marketStock.clear(); marketMessages.reset(); marketSigns.reset(); marketLevel = null;
    });
  }
  public List<String> profileSuggestions(String prefix){
    var matches=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
    var mc=Minecraft.getInstance();var c=mc.getConnection();
    java.util.function.Consumer<String> add=n->{
      if(n!=null && n.regionMatches(true,0,prefix,0,prefix.length()))matches.putIfAbsent(n,n);
    };
    if(c!=null)for(var p:c.getOnlinePlayers())add.accept(p.getProfile().name());
    add.accept(mc.getUser().getName());
    inspections.knownNames().forEach(add);
    for(var board:app.closest.history.players.values())for(var p:board.values())add.accept(p.name());
    for(var feed:Feed.values()){var snapshot=snapshot(feed);if(snapshot!=null){
      snapshot.profiles().names().forEach(add);
      if(snapshot.data() instanceof Jobs jobs)for(var board:jobs.boards().values())for(var entry:board)add.accept(entry.name());
      if(snapshot.data() instanceof Islands islands)for(var island:islands.known())add.accept(island.owner());
    }}
    return List.copyOf(matches.values());
  }
  private int openProfile(String name) {
    if (!name.matches("[A-Za-z0-9_]{1,16}")) return 0;
    var mc = Minecraft.getInstance();
    mc.schedule(() -> mc.gui.setScreen(new dev.asarix.skyblockflow.ui.PlayerViewScreen(app,null,name)));
    return 1;
  }
  public ExcaliaApiSettings settings() { return app.store.config.tools.publicData; }
  public boolean connected() { return app.recipes != null && app.recipes.onExcalia() && Minecraft.getInstance().player != null; }
  public boolean rankingsEnabled() { return connected() && settings().leaderboards && app.store.config.enabled(Feature.LEADERBOARDS); }
  public boolean pending() { return pending != null; }
  public void cancel() { pending = null; }

  public boolean measure(Collection<String> jobs) {
    if (!rankingsEnabled()) return false;
    pending = new Pending(List.copyOf(jobs), Minecraft.getInstance().level, System.currentTimeMillis() + 40_000);
    status = app.closest.status = "Refreshing public rankings…";
    refreshRankings(); finishMeasurement(); return true;
  }
  public void refreshRankings() {
    if (!rankingsEnabled()) return;
    requestJobRanking(Feed.JOBS); requestJobRanking(Feed.FISHING); client.request(Feed.ISLANDS); client.request(Feed.MONEY);
  }
  private void requestJobRanking(Feed feed) {
    var active = app.jobs.tracker.activeJob();
    if (app.store.config.enabled(Feature.JOBS) && settings().refreshActiveJob
        && active != null && feed == jobFeed(active.id)) client.refresh(feed, settings().activeJobIntervalMs());
    else client.request(feed);
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
  private ExcaliaApiClient.Snapshot pricingEconomy, pricingMarket;
  private long pricingStockRevision, pricingRevision;
  private CraftingPrices craftingPrices = new CraftingPrices(0, null, null, Map.of());

  record CraftingPrices(long revision, Economy economy, Market market, Map<String, Long> stock) {}

  // Captured on the client thread; calculation workers only read this immutable snapshot.
  CraftingPrices craftingPrices() {
    boolean online = connected();
    var economy = online && settings().shopPrices ? usable(Feed.ECONOMY) : null;
    var market = online && settings().market ? usable(Feed.MARKET) : null;
    long stock = market == null ? 0 : marketStock.revision();
    if (economy != pricingEconomy || market != pricingMarket || stock != pricingStockRevision) {
      pricingEconomy = economy;
      pricingMarket = market;
      pricingStockRevision = stock;
      var marketData = market == null ? null : (Market) market.data();
      craftingPrices = new CraftingPrices(++pricingRevision,
          economy == null ? null : (Economy) economy.data(), marketData,
          marketData == null ? Map.of() : marketStock.snapshot(marketData));
    }
    return craftingPrices;
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
    var prices = craftingPrices();
    var quotes = marketQuotes.get(market, prices.stock(), prices.revision(), item, name);
    if (quotes == null) return List.of();
    var lines = quotes.lines(count);
    if (System.currentTimeMillis() - market.at() > 120_000)
      return lines.stream().map(s -> s + " · cached quote").toList();
    return lines;
  }
  public List<Offer> sellers(String item, String name) {
    if (!connected() || !settings().market) return List.of();
    var snapshot = usable(Feed.MARKET);
    if (snapshot == null) return List.of();
    var prices = craftingPrices();
    var quotes = marketQuotes.get((Market)snapshot.data(), prices.stock(), prices.revision(), item, name);
    return quotes == null ? List.of() : quotes.sellers();
  }
  public void refresh(Feed feed){if(connected())client.refresh(feed);}
  public void refreshIsland(UUID id){if(connected() && id!=null)client.refreshDetail(id);}
  public boolean busy(Feed feed){return connected() && client.busy(feed);}
  public void request(Feed feed) { if (connected()) client.request(feed); }
  public ExcaliaApiClient.Snapshot snapshot(Feed feed) { return connected() ? client.snapshot(feed) : null; }
  public String state(Feed feed) { return connected() ? client.state(feed) : "Join Excalia Season 3"; }
  public ExcaliaApiClient.Snapshot profileIsland(String player,long inspectedPower) {
    var board=usable(Feed.ISLANDS);
    if (board==null) return null;
    var known=client.knownMembership(player);
    UUID id=known==null ? null : ((IslandDetail)known.data()).id();
    for (var island:((Islands)board.data()).known()) if (island.owner().equalsIgnoreCase(player)) {
      id=island.id(); break;
    }
    if (id==null && inspectedPower>=0) {
      // Power identifies a candidate, never a member. Verify exactly one detail response.
      var matches=((Islands)board.data()).known().stream().filter(i->i.power()==inspectedPower).limit(2).toList();
      if(matches.size()==1)id=matches.getFirst().id();
    }
    if (id==null) return null;
    client.requestDetail(id);
    var detail=client.detail(id);
    return detail!=null && !detail.profiles().forPlayer(player).isEmpty() ? detail : null;
  }
  public IslandDetail cachedIslandDetail(UUID id){
    var snapshot=connected()?client.detail(id):null;
    return snapshot!=null && snapshot.data() instanceof IslandDetail details?details:null;
  }
  public ExcaliaApiClient.Snapshot islandDetail(UUID id) {
    if(!connected() || id==null)return null;
    client.requestDetail(id);return client.detail(id);
  }
  @SuppressWarnings("unchecked")
  public List<Wallet> wallets() {
    var data=snapshot(Feed.MONEY);return data!=null && data.data() instanceof List<?> ? (List<Wallet>)data.data():List.of();
  }
  public static Feed jobFeed(String job) { return job.equals("pecheur") ? Feed.FISHING : Feed.JOBS; }
  public JobEntry entry(String job, String name) {
    var data = jobs(job);
    if (data == null) return null;
    for (var row : data.boards().getOrDefault(job,List.of())) if (row.name().equalsIgnoreCase(name)) return row;
    return null;
  }
  public String rank(String job, String name) {
    var data = jobs(job);
    if (data == null) {
      String status = state(jobFeed(job));
      return status.isEmpty() ? "Rank unavailable" : status;
    }
    if (!data.boards().containsKey(job)) return "Rank unavailable";
    var row = entry(job,name);
    return row == null ? "Unranked" : "#" + row.rank();
  }
  public boolean jobFeedsReady() {
    return client.settled(Feed.JOBS) && client.settled(Feed.FISHING);
  }
  private void tick() {
    var mc = Minecraft.getInstance(); long now = System.currentTimeMillis();
    if (marketLevel != mc.level) { marketLevel = mc.level; marketMessages.reset(); marketSigns.reset(); }
    if (!connected()) { pending = null; history = null; applied.clear(); ownProgress.clear(); islandId = null; appliedDetail = null; return; }
    if (history != app.closest.history) {
      history = app.closest.history; applied.clear(); ownProgress.clear(); appliedDetail = null; islandId = null;
    }
    if (now < nextTick) return;
    nextTick = now + 1000;
    if (rankingsEnabled()) { refreshRankings(); applyRankings(); }
    else if (settings().leaderboards && app.store.config.enabled(Feature.JOBS)) {
      requestJobRanking(Feed.JOBS); requestJobRanking(Feed.FISHING);
    }
    if (settings().leaderboards && app.store.config.enabled(Feature.JOBS)) {
      for (Feed feed : List.of(Feed.JOBS,Feed.FISHING)) {
        var snapshot=usable(feed);
        if (snapshot==null || ownProgress.get(feed)==snapshot) continue;
        var data=(Jobs)snapshot.data();
        for (var board:data.boards().values()) {
          var row=own(board);
          if (row!=null && Double.isFinite(row.reading().xp())) app.jobs.publicStats(row.reading(),data.at());
        }
        ownProgress.put(feed,snapshot);
      }
    }
    if (app.store.config.tools.shopValues) {
      if (settings().market) {
        client.request(Feed.MARKET);
        var snapshot = usable(Feed.MARKET);
        marketSigns.tick(mc, snapshot == null ? null : (Market)snapshot.data(), marketStock, now);
      }
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
    pending = null;
    status = app.closest.status = usable(Feed.JOBS) == null ? state(Feed.JOBS) : "Public rankings refreshed";
  }
  private Jobs jobs(String job) {
    var snapshot = usable(job.equals("pecheur") ? Feed.FISHING : Feed.JOBS);
    return snapshot == null ? null : (Jobs)snapshot.data();
  }
  private JobEntry own(List<JobEntry> board) {
    UUID uuid = Minecraft.getInstance().getUser().getProfileId();
    return board.stream().filter(p -> p.uuid().equals(uuid)).findFirst().orElse(null);
  }
  private void applyRankings() {
    if (!rankingsEnabled() || app.closest.commandMeasurementActive()) return;
    var money = usable(Feed.MONEY);
    if (money != null && applied.get(Feed.MONEY) != money) {
      for (var field : money.profiles().forPlayer(Minecraft.getInstance().getUser().getName())) {
        if (field.label().equals("Rank")) {
          app.closest.confirm("skydollars","Skydollars",Integer.parseInt(field.value().substring(1)),money.sourceAt(),true);
          app.closest.saveHistory(); break;
        }
      }
      applied.put(Feed.MONEY,money);
    }
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
          if (Double.isFinite(player.reading().xp())) app.closest.history.cacheStats(player.name(), player.reading(), data.at());
        }
        if (own != null) {
          // Follow account identity even after a nickname change.
          String name = Minecraft.getInstance().getUser().getName();
          app.closest.history.cacheRank(job, new JobMessages.Rank(own.rank(), own.reading().level(), name), data.at());
          if (Double.isFinite(own.reading().xp())) app.closest.history.cacheStats(name, own.reading(), data.at());
          app.closest.confirm(job, JobXp.name(job), own.rank(), data.at(), isolated(board, own));
        } else if (prior != null) {
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
    var detail = detailSnapshot != null && System.currentTimeMillis() - detailSnapshot.receivedAt() < ExcaliaApiClient.MAX_QUOTE_AGE
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
