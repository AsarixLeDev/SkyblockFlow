package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import java.util.*;
import dev.asarix.skyblockflow.core.ExcaliaApiClient.Feed;

/** Immutable player rows built once on the HTTP worker, shared by every profile viewer. */
public final class ProfileIndex {
  public record Field(String group, String label, String value) {}
  public record Listing(String name,String type,String price,String stock,boolean buying){}
  private final Map<String,List<Listing>> shops=new HashMap<>();
  public List<Listing> shops(String owner){return shops.getOrDefault(owner.toLowerCase(Locale.ROOT),List.of());}
  public static final ProfileIndex EMPTY = new ProfileIndex();
  private final Map<String, LinkedHashSet<Field>> building = new HashMap<>();
  private final Map<String, List<Field>> rows = new HashMap<>();
  private final Map<String,String> displayNames=new HashMap<>();
  public Collection<String> names(){return displayNames.values();}
  public List<Field> forPlayer(String name) {
    return rows.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
  }
  static ProfileIndex build(Feed feed, JsonObject root) {
    var index = new ProfileIndex();
    switch (feed) {
      case ONLINE -> index.table(root, "players", "Presence", "name", "grade.name serverIds");
      case MONEY -> index.table(root, "top", "Skydollars", "name", "balance share");
      case PASS -> {
        for (var k : List.of("topByTier","topByPoints","topByDaily","topPremium"))
          index.table(root,k,"Season pass","name","tier");
      }
      case QUESTS -> index.boards(root, "Quests", "completed current redoableTotal", "topByCompleted topByCurrent topByRedoable");
      case DUNGEONS -> {
        for (var e : root.getAsJsonArray("perType")) {
          var dungeon=e.getAsJsonObject();
          index.boards(dungeon, label(dungeon.get("type").getAsString()), "completions bestTimeSeconds deaths mobsKilled", "topByTime topByRuns topByMobs");
        }
      }
      case WEAPONS -> {
        index.boards(root,"Crafting","crafted rares legendary palierMax","topCrafters topRareCrafters");
        index.boards(root,"Collection","entries items top","topCollectors");
        index.boards(root,"Armory","items rares best","topOwners");
        index.table(root,"bigFinds","Discovery","crafter","item rarity at");
        index.table(root,"rarePieces","First discovery","first","item rarity firstAt");
      }
      case PETS -> {
        index.boards(root,"Pet collection","pets types species legendary levelMax xp","topCollectors topTamers topLegendary");
        index.boards(root,"Pet mastery","speciesName rarity level xp","topLevels");
        index.table(root,"latestRare","Rare companion","name","speciesName rarity at");
      }
      case FISHING -> {
        index.boards(root,"Fishing","level xp totalCatches speciesCount ghostsFound","topByLevel topByCatches topByCollection topByGhosts");
        index.table(root,"records","Fishing record","recordHolderName","fishId recordSize recordWeight");
      }
      case ISLANDS -> {
        for (var k : List.of("topByValue","topByBalance","topByMembers","topBySize","topByReputation"))
          index.table(root,k,"Owned island","ownerName","size createdAt balance generator memberCount warps reputation.avg reputation.count value");
      }
      case MARKET -> {
        index.boards(root,"Sales","count qty money","topSellers");
        index.boards(root,"Purchases","count qty money","topBuyers");
        index.boards(root,"Buyback","count qty money","topBuybackShops");
        index.table(root,"topShops","Popular shop","owner","name selling count money");
        index.listings(root,"listings",false);
        index.listings(root,"buyListings",true);
        index.shops.replaceAll((owner,offers)->List.copyOf(offers));
        index.table(root,"latest","Recent sale","seller","name buyer qty money at");
        index.table(root,"latest","Recent purchase","buyer","name seller qty money at");
      }
      default -> { }
    }
    index.building.forEach((k,v)->index.rows.put(k,List.copyOf(v)));
    index.building.clear(); return index;
  }
  static ProfileIndex islandDetail(JsonObject root) {
    var index=new ProfileIndex();
    var members=root.getAsJsonArray("members");
    for (var member:members) {
      var row=member.getAsJsonObject();
      if (!row.has("name") || !row.get("name").isJsonPrimitive()) continue;
      String name=row.get("name").getAsString();
      var fields=new ArrayList<Field>();
      for (String k:List.of("role","joinedAt"))
        if (row.has(k) && row.get(k).isJsonPrimitive()) fields.add(new Field("Membership",label(k),row.get(k).getAsString()));
      for (String k:List.of("value","balance","size","maxMembers","generator","warps","createdAt"))
        if (root.has(k) && root.get(k).isJsonPrimitive()) fields.add(new Field("Island",label(k),root.get(k).getAsString()));
      for (var peer:members) {
        var m=peer.getAsJsonObject();
        if (m.has("name") && m.get("name").isJsonPrimitive() && m.has("role") && m.get("role").isJsonPrimitive())
          fields.add(new Field("Members",m.get("name").getAsString(),m.get("role").getAsString()));
      }
      if (root.has("materials") && root.get("materials").isJsonArray())
        for (var material:root.getAsJsonArray("materials")) if (material.isJsonObject()) {
          var m=material.getAsJsonObject();
          for (var field:m.entrySet()) if (field.getValue().isJsonPrimitive())
            fields.add(new Field("Island materials",label(field.getKey()),field.getValue().getAsString()));
        }
      index.rows.put(name.toLowerCase(Locale.ROOT),List.copyOf(fields));
    }
    return index;
  }
  private static String displayText(JsonObject row,String key){
    var value=row.get(key);
    if(value==null || !value.isJsonPrimitive())return "";
    return value.getAsString().replaceAll("[\\p{Cntrl}§]","").strip();
  }
  private static String displayNumber(JsonObject row,String key){
    try{
      var number=row.getAsJsonPrimitive(key).getAsBigDecimal();
      if(number.signum()<0 || number.precision()>30 || Math.abs(number.scale())>16)return "—";
      return Numbers.decimal(number,0,Math.max(0,number.stripTrailingZeros().scale()));
    }catch(RuntimeException missing){return "—";}
  }
  private void listings(JsonObject root,String key,boolean buying){
    for(var element:root.getAsJsonArray(key)){
      if(!element.isJsonObject())continue;
      var row=element.getAsJsonObject();String owner=displayText(row,"owner"),name=displayText(row,"name");
      if(!owner.matches("[a-zA-Z0-9_]{1,16}") || name.isBlank())continue;
      String stock="true".equalsIgnoreCase(displayText(row,"unlimited"))?"Unlimited":displayNumber(row,"stock");
      var card=new Listing(name,displayText(row,"type"),displayNumber(row,"price"),stock,buying);
      displayNames.putIfAbsent(owner.toLowerCase(Locale.ROOT),owner);
      shops.computeIfAbsent(owner.toLowerCase(Locale.ROOT),k->new ArrayList<>()).add(card);
    }
  }
  private void boards(JsonObject root,String group,String fields,String keys) {
    for (String key : keys.split(" ")) table(root,key,group,"name",fields);
  }
  private void table(JsonObject root,String key,String group,String identity,String fields) {
    var array=root.getAsJsonArray(key);
    if (array==null) throw new IllegalArgumentException("Missing "+key);
    int position=0;
    for (var element : array) {
      position++;
      if (!element.isJsonObject()) continue;
      var row=element.getAsJsonObject();
      if (!row.has(identity) || !row.get(identity).isJsonPrimitive()) continue;
      String name=row.get(identity).getAsString();
      if (!name.matches("[a-zA-Z0-9_]{1,16}")) continue;
      displayNames.putIfAbsent(name.toLowerCase(Locale.ROOT),name);
      var out=building.computeIfAbsent(name.toLowerCase(Locale.ROOT), k->new LinkedHashSet<>());
      String section=group;
      for (String detail : List.of("fishId","speciesName","item","name"))
        if (!identity.equals(detail) && row.has(detail) && row.get(detail).isJsonPrimitive()) {
          section=group+" · "+row.get(detail).getAsString(); break;
        }
      if (key.startsWith("top") && !group.equals("Season pass")) {
        String board=key.equals("top") ? "Rank" : label(key.replaceFirst("^top(By)?",""))+" rank";
        out.add(new Field(section,board,"#"+position));
      }
      for (String field : fields.split(" ")) {
        JsonElement v=row;
        for (String part : field.split("\\.")) v=v!=null && v.isJsonObject() ? v.getAsJsonObject().get(part) : null;
        if (v==null || v.isJsonNull()) continue;
        String value;
        if (v.isJsonArray()) {
          var parts=new ArrayList<String>();
          for (var part:v.getAsJsonArray()) if (part.isJsonPrimitive()) parts.add(part.getAsString());
          value=String.join(", ",parts);
        } else if (v.isJsonPrimitive()) {
          var primitive=v.getAsJsonPrimitive();
          value=primitive.isNumber() ? Numbers.decimal(primitive.getAsBigDecimal(),0,2) : primitive.getAsString();
        } else continue;
        value=value.replaceAll("[\\p{Cntrl}§]","").strip();
        if (!value.isEmpty() && value.length()<=200) out.add(new Field(section,label(field),value));
      }
    }
  }
  static String label(String key) {
    String s=key.replaceAll("([a-z])([A-Z])","$1 $2").replace('_',' ').replace('.',' ').toLowerCase(Locale.ROOT);
    return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);
  }
}
