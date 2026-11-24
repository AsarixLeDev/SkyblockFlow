package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import java.math.*;
import java.time.Instant;
import java.util.*;

/** Validated public Season 3 snapshots. Array order is the server's ranking order. */
public final class ExcaliaApiData {
  private ExcaliaApiData() {}
  public record JobEntry(UUID uuid, String name, int rank, JobXp.Reading reading) {}
  public record Jobs(Map<String, List<JobEntry>> boards, long at) {}
  public record Island(UUID id, UUID ownerId, String owner, int rank, long power) {}
  public record Islands(List<Island> ranked, List<Island> known, long at) {}
  public record Member(String name,String role,Instant joined) {}
  public record IslandDetail(UUID id, UUID ownerId, String owner, Set<UUID> members, long power,
      List<Member> roster,Instant created,BigDecimal bank,int size,String generator) {}
  public record Wallet(int rank,String name,BigDecimal balance) {}
  public static List<Wallet> wallets(JsonObject root) {
    var rows=new ArrayList<Wallet>();
    for(var e:array(root,"top")){
      var r=e.getAsJsonObject(); String name=player(r,"name");
      int rank=r.has("rank")?(int)integer(r,"rank",1,1000000):rows.size()+1;
      rows.add(new Wallet(rank,name,number(r,"balance")));
    }
    rows.sort(Comparator.comparingInt(Wallet::rank));return List.copyOf(rows);
  }
  private static Instant date(JsonObject object,String key) {
    try{return Instant.parse(text(object,key));}catch(RuntimeException missing){return null;}
  }
  public record Offer(String shop, String key, String type, String name, String owner, BigDecimal price,
                      long stock, boolean unlimited) {
    public Offer(String key, String type, String name, String owner, BigDecimal price, long stock, boolean unlimited) {
      this("", key, type, name, owner, price, stock, unlimited);
    }
    public String identity() { return shop.isEmpty() ? owner + "\u0000" + key + "\u0000" + price.toPlainString() : shop + "\u0000" + key; }
    public Offer withStock(long remaining) { return new Offer(shop, key, type, name, owner, price, remaining, unlimited); }
  }
  public record Market(Map<String, List<Offer>> selling, Map<String, List<Offer>> buying, long at) {
    public List<String> lines(String type, String name, int count) {
      var lines = new ArrayList<String>();
      quote(lines, best(selling.getOrDefault(type, List.of()), name, false), count, false);
      quote(lines, best(buying.getOrDefault(type, List.of()), name, true), count, true);
      return List.copyOf(lines);
    }
  }
  public record Economy(Map<String, List<SeasonThreeCatalog.ShopQuote>> prices, long at) {}

  public static Jobs jobs(JsonObject root, long receivedAt) {
    var boards = new LinkedHashMap<String, List<JobEntry>>();
    var source = object(root, "topPerJob");
    for (var key : source.keySet()) {
      if (!JobXp.NAMES.containsKey(key)) continue;
      boards.put(key, jobBoard(array(source, key), key, "experience"));
    }
    if (boards.isEmpty()) throw new IllegalArgumentException("No job rankings");
    return new Jobs(Map.copyOf(boards), timestamp(root, receivedAt));
  }

  public static Jobs fishing(JsonObject root, long receivedAt) {
    return new Jobs(Map.of("pecheur", jobBoard(array(root, "topByLevel"), "pecheur", "xp")),
        timestamp(root, receivedAt));
  }

  private static List<JobEntry> jobBoard(JsonArray array, String job, String xpKey) {
    var rows = new ArrayList<JobEntry>(); var ids = new HashSet<UUID>(); var names = new HashSet<String>();
    int previousLevel = 201;
    for (var element : array) {
      var row = element.getAsJsonObject();
      UUID id = uuid(row, "uuid"); String name = player(row, "name");
      int level = Math.toIntExact(integer(row, "level", 0, 200));
      double xp = number(row, xpKey).doubleValue();
      if (!Double.isFinite(xp) || xp < 0 || xp > 1e12 || level > previousLevel
          || !ids.add(id) || !names.add(name.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("Invalid job ranking");
      previousLevel = level;
      long required = JobXp.requirement(job, level);
      // XP at the level cap is retained by the server but no longer advances the level.
      rows.add(new JobEntry(id, name, rows.size() + 1,
          new JobXp.Reading(job, level, JobXp.levelProgress(job, level, xp), required, 0)));
      if (rows.size() > 2048) throw new IllegalArgumentException("Oversized ranking");
    }
    return List.copyOf(rows);
  }

  public static Islands islands(JsonObject root, long receivedAt) {
    var known = new LinkedHashMap<UUID, Island>(); var ranked = new ArrayList<Island>();
    long previous = Long.MAX_VALUE;
    for (var value : array(root, "topByValue")) {
      var island = island(value.getAsJsonObject(), ranked.size() + 1);
      if (island.power > previous || known.put(island.id, island) != null)
        throw new IllegalArgumentException("Invalid island order");
      previous = island.power; ranked.add(island);
    }
    for (String key : List.of("topByBalance", "topByMembers", "topBySize", "topByReputation"))
      for (var value : optionalArray(root, key)) {
        var island = island(value.getAsJsonObject(), 0); known.putIfAbsent(island.id, island);
      }
    return new Islands(List.copyOf(ranked), List.copyOf(known.values()), timestamp(root, receivedAt));
  }

  private static Island island(JsonObject row, int rank) {
    return new Island(uuid(row, "islandId"), uuid(row, "ownerUuid"), player(row, "ownerName"),
        rank, integer(row, "value", 0, 1_000_000_000_000L));
  }

  public static IslandDetail detail(JsonObject root) {
    var roster=new ArrayList<Member>();
    var members = new HashSet<UUID>(); UUID ownerId = null; String owner = "";
    for (var element : array(root, "members")) {
      var row = element.getAsJsonObject(); UUID id = uuid(row, "uuid"); members.add(id);
      roster.add(new Member(player(row,"name"),text(row,"role"),date(row,"joinedAt")));
      if (text(row, "role").equals("OWNER")) {
        if (ownerId != null) throw new IllegalArgumentException("Multiple owners");
        ownerId = id; owner = player(row, "name");
      }
    }
    if (ownerId == null) throw new IllegalArgumentException("Missing owner");
    return new IslandDetail(uuid(root, "islandId"), ownerId, owner, Set.copyOf(members),
        integer(root, "value", 0, 1_000_000_000_000L),List.copyOf(roster),date(root,"createdAt"),
        root.has("balance")?number(root,"balance"):null,root.has("size")?(int)integer(root,"size",0,100000):0,
        root.has("generator")?text(root,"generator"):"");
  }

  public static Market market(JsonObject root, long receivedAt) {
    return new Market(offers(array(root, "listings")), offers(array(root, "buyListings")),
        timestamp(root, receivedAt));
  }

  private static Map<String, List<Offer>> offers(JsonArray source) {
    var map = new LinkedHashMap<String, List<Offer>>();
    if (source.size() > 20000) throw new IllegalArgumentException("Oversized market");
    for (var element : source) {
      var row = element.getAsJsonObject();
      if (!row.has("plain") || !row.get("plain").getAsBoolean()) continue;
      var price = number(row, "price");
      if (price.signum() <= 0 || price.compareTo(new BigDecimal("100000000")) > 0) continue;
      boolean unlimited = row.has("unlimited") && row.get("unlimited").getAsBoolean();
      long stock = row.has("stock") && !row.get("stock").isJsonNull()
          ? integer(row, "stock", 0, Long.MAX_VALUE) : 0;
      if (!unlimited && stock == 0) continue;
      String type = itemId(text(row, "type"));
      String shop = row.has("shop") && !row.get("shop").isJsonNull() ? row.get("shop").getAsString() : "";
      if (!shop.matches("[0-9]{1,20}")) shop = "";
      var offer = new Offer(shop, text(row, "key"), type, text(row, "name"), player(row, "owner"), price, stock, unlimited);
      map.computeIfAbsent(type, k -> new ArrayList<>()).add(offer);
    }
    map.replaceAll((key, value) -> List.copyOf(value)); return Map.copyOf(map);
  }

  public static List<Offer> best(List<Offer> source, String name, boolean buying) {
    var candidates = source;
    if (source.stream().map(Offer::key).distinct().count() > 1)
      candidates = source.stream().filter(o -> JobXp.normalize(o.name).equals(JobXp.normalize(name))).toList();
    if (candidates.stream().map(Offer::key).distinct().count() > 1) return List.of();
    var order = Comparator.comparing(Offer::price);
    if (buying) order = order.reversed();
    return candidates.stream().filter(o -> o.unlimited || o.stock > 0)
        .sorted(order.thenComparing(Offer::owner).thenComparing(Offer::shop)).limit(5).toList();
  }

  public static void quote(List<String> lines, List<Offer> candidates, int count, boolean buying) {
    var best = candidates.isEmpty() ? null : candidates.getFirst();
    if (best == null) return;
    String line = (buying ? "Market sell: " : "Market buy: ") + Numbers.decimal(best.price, 0, 4)
        + " $ / item · " + best.owner;
    if (count > 1) {
      long quantity = best.unlimited ? count : Math.min(count, best.stock);
      line += " · " + Numbers.integer(quantity) + " = "
          + Numbers.decimal(best.price.multiply(BigDecimal.valueOf(quantity)), 0, 4) + " $";
      if (quantity < count) line += " (limited stock)";
    }
    lines.add(line);
  }

  public static Economy economy(JsonObject root, long receivedAt) {
    var result = new LinkedHashMap<String, List<SeasonThreeCatalog.ShopQuote>>();
    var ambiguous = new HashSet<String>();
    for (var shopValue : array(root, "shops")) {
      var shop = shopValue.getAsJsonObject(); String shopId = text(shop, "id").replaceFirst("^shop_", "");
      for (var itemValue : array(shop, "items")) {
        var item = itemValue.getAsJsonObject(); String type = itemId(text(item, "type"));
        var units = new LinkedHashMap<String, BigDecimal[]>();
        for (String direction : List.of("buy", "sell")) {
          if (!item.has(direction + "Price") || item.get(direction + "Price").isJsonNull()) continue;
          BigDecimal price = number(item, direction + "Price");
          long amount = integer(item, direction + "Amount", 1, 1_000_000);
          String currency = text(item, direction + "Currency");
          if (!Set.of("Skydollars", "Excacoins", "Votecoins").contains(currency) || price.signum() < 0) continue;
          units.computeIfAbsent(currency, k -> new BigDecimal[2])[direction.equals("buy") ? 0 : 1] =
              price.divide(BigDecimal.valueOf(amount), MathContext.DECIMAL64);
        }
        for (var unit : units.entrySet()) {
          String identity = type + "/" + shopId + "/" + unit.getKey();
          var quote = new SeasonThreeCatalog.ShopQuote(shopId, unit.getKey(), unit.getValue()[0], unit.getValue()[1]);
          var list = result.computeIfAbsent(type, k -> new ArrayList<>());
          var previous = list.stream().filter(q -> q.shop().equals(shopId) && q.unit().equals(unit.getKey())).findFirst().orElse(null);
          if (previous != null && !previous.equals(quote)) ambiguous.add(identity);
          else if (previous == null) list.add(quote);
        }
      }
    }
    result.replaceAll((type, list) -> list.stream()
        .filter(q -> !ambiguous.contains(type + "/" + q.shop() + "/" + q.unit())).toList());
    return new Economy(Map.copyOf(result), receivedAt);
  }

  public static String itemId(String type) {
    String id = type.contains(":") ? type : "minecraft:" + type;
    if (!id.matches("(?:minecraft|oraxen):[a-z0-9_./-]{1,160}")) throw new IllegalArgumentException("Invalid item ID");
    return id;
  }
  public static long timestamp(JsonObject root, long receivedAt) {
    if (!root.has("fetchedAt")) return receivedAt;
    long at = Instant.parse(text(root, "fetchedAt")).toEpochMilli();
    if (at > receivedAt + 60_000 || at < 0) throw new IllegalArgumentException("Invalid snapshot time");
    return Math.min(at, receivedAt);
  }
  private static JsonObject object(JsonObject j, String key) { return j.getAsJsonObject(key); }
  private static JsonArray array(JsonObject j, String key) {
    var a = j.getAsJsonArray(key); if (a == null) throw new IllegalArgumentException("Missing " + key); return a;
  }
  private static JsonArray optionalArray(JsonObject j, String key) { return j.has(key) ? array(j, key) : new JsonArray(); }
  private static String text(JsonObject j, String key) {
    var element = j.get(key);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Missing " + key);
    String text = element.getAsString();
    if (text.length() > 512 || text.chars().anyMatch(c -> c < 32 || c == 127 || c == '§'))
      throw new IllegalArgumentException("Invalid text");
    return text;
  }
  private static UUID uuid(JsonObject j, String key) { return UUID.fromString(text(j, key)); }
  private static String player(JsonObject j, String key) {
    String name = text(j, key);
    if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid player name"); return name;
  }
  private static BigDecimal number(JsonObject j, String key) {
    var value = j.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
        || value.getAsString().length() > 40) throw new IllegalArgumentException("Invalid " + key);
    var n = value.getAsBigDecimal();
    if (n.abs().compareTo(new BigDecimal("1000000000000000")) > 0 || Math.abs(n.scale()) > 32)
      throw new IllegalArgumentException("Invalid number"); return n;
  }
  private static long integer(JsonObject j, String key, long min, long max) {
    long n = number(j, key).longValueExact();
    if (n < min || n > max) throw new IllegalArgumentException("Invalid " + key); return n;
  }
}
