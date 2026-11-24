package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.ExcaliaApiClient.Feed;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.*;

final class ProfileContent {
  enum Section {
    OVERVIEW("Overview",Items.NETHER_STAR), JOBS("Jobs",Items.IRON_PICKAXE),
    ISLAND("Island",Items.GRASS_BLOCK), ECONOMY("Economy",Items.GOLD_INGOT),
    ADVENTURE("Adventure",Items.DIAMOND_SWORD), COLLECTION("Collection",Items.AMETHYST_SHARD);
    final String label; final Item icon;
    Section(String label,Item icon){this.label=label;this.icon=icon;}
    List<String> views(){return switch(this){
      case ECONOMY->List.of("Wallet","Shops","Activity"); case ADVENTURE->List.of("Combat","Dungeons","Fishing","Quests");
      case COLLECTION->List.of("Pets","Armory");default->List.of();};}
  }
  record Stat(String group,String label,String value,String detail,Item icon,long at){}
  private final SkyblockFlowClient app; private final String name;
  private final List<Stat> rows=new ArrayList<>();
  private final List<String> states=new ArrayList<>();
  private InspectedProfile.Sheet sheet;
  ProfileContent(SkyblockFlowClient app,String name){this.app=app;this.name=name;}
  UUID fixedIsland;
  record Atlas(long power,IslandStanding standing,ExcaliaApiData.IslandDetail detail,long at){}
  Atlas atlas;
  List<Stat> rows(){return rows;}
  String state(){return String.join(" · ",states);}
  String inspected(String key){var f=sheet==null?null:sheet.facts().get(key);return f==null?"":f.value();}
  private Feed[] feeds(Section s,int tab){return switch(s){
    case OVERVIEW->new Feed[]{Feed.ONLINE,Feed.MONEY,Feed.PASS,Feed.ISLANDS};
    case JOBS->new Feed[]{Feed.JOBS,Feed.FISHING};case ISLAND->new Feed[]{Feed.ISLANDS,Feed.MONEY};
    case ECONOMY->new Feed[]{tab==0?Feed.MONEY:Feed.MARKET};
    case ADVENTURE->tab==0?new Feed[]{}:new Feed[]{switch(tab){case 1->Feed.DUNGEONS;case 2->Feed.FISHING;default->Feed.QUESTS;}};
    case COLLECTION->new Feed[]{tab==0?Feed.PETS:Feed.WEAPONS};
  };}
  List<ProfileIndex.Listing> shops(){
    var snapshot=app.excaliaApi.snapshot(Feed.MARKET);
    return snapshot==null?List.of():snapshot.profiles().shops(name);
  }
  void refresh(Section section,int tab){
    for(var feed:feeds(section,tab))app.excaliaApi.refresh(feed);
    if(section==Section.ISLAND && atlas!=null)
      app.excaliaApi.refreshIsland(fixedIsland!=null?fixedIsland:atlas.detail()!=null?atlas.detail().id():null);
  }
  boolean busy(Section section,int tab){
    for(var feed:feeds(section,tab))if(app.excaliaApi.busy(feed))return true;
    return false;
  }
  void request(Section section,int tab){for(var feed:feeds(section,tab))app.excaliaApi.request(feed);}
  private void fact(String key,String group,String label,Item icon){
    if(sheet==null)return; var f=sheet.facts().get(key);
    if(f!=null)rows.add(new Stat(group,label,f.value(),"Server inspection",icon,f.at()));
  }
  private void best(String key,String label,Item icon,Feed feed,String apiGroup,String apiKey){
    var f=sheet==null?null:sheet.facts().get(key);
    var snapshot=app.excaliaApi.snapshot(feed);
    var api=snapshot==null?null:snapshot.profiles().forPlayer(name).stream()
        .filter(row->row.group().equals(apiGroup)&&row.label().equals(apiKey)).findFirst().orElse(null);
    if(api!=null && (f==null||snapshot.sourceAt()>f.at()))
      rows.add(new Stat("",label,api.value(),"Public data",icon,snapshot.sourceAt()));
    else fact(key,"",label,icon);
  }
  String grade(){
    var f=sheet==null?null:sheet.facts().get("Grade");
    var s=app.excaliaApi.snapshot(Feed.ONLINE);
    if(s!=null && (f==null || s.sourceAt()>f.at()))for(var r:s.profiles().forPlayer(name))
      if(r.label().equalsIgnoreCase("grade name"))return r.value();
    return f==null?"":f.value();
  }
  private void island(boolean overview,boolean map) {
    var snapshot=app.excaliaApi.snapshot(Feed.ISLANDS);
    var observed=sheet==null?null:sheet.facts().get("Island power");
    long power=IslandStanding.whole(observed==null?"":observed.value()), powerAt=observed==null?0:observed.at();
    var board=snapshot==null?null:(ExcaliaApiData.Islands)snapshot.data();
    var owner=board==null?null:board.known().stream().filter(i->i.owner().equalsIgnoreCase(name)).findFirst().orElse(null);
    var membership=fixedIsland==null?app.excaliaApi.profileIsland(name,power):app.excaliaApi.islandDetail(fixedIsland);
    var detail=membership==null?null:(ExcaliaApiData.IslandDetail)membership.data();
    UUID id=fixedIsland!=null?fixedIsland:owner!=null?owner.id():detail!=null?detail.id():null;
    var listed=board==null || id==null?null:board.known().stream().filter(i->i.id().equals(id)).findFirst().orElse(null);
    if(listed!=null && (power<0||board.at()>=powerAt)){power=listed.power();powerAt=board.at();}
    long detailAt=detail==null?0:listed!=null&&listed.power()==detail.power()?board.at():membership.sourceAt();
    if(detail!=null && (power<0||detailAt>=powerAt)){power=detail.power();powerAt=detailAt;}
    if(power>=0)rows.add(new Stat("","Island power",Numbers.integer(power),"Island power snapshot",Items.GRASS_BLOCK,powerAt));
    var standing=board==null?null:IslandStanding.compare(board.ranked(),id,power);
    atlas=new Atlas(power,standing,detail,powerAt);
    if(!overview && detail!=null)for(var member:detail.roster())app.excaliaApi.inspections.loadCached(member.name());
    if(map)return;
    if(standing!=null && standing.published()!=null){
      var own=standing.published();rows.add(new Stat("","Power rank","#"+own.rank(),"Public leaderboard · "+own.owner(),Items.GOLD_INGOT,board.at()));
    }else if(standing==null || standing.estimate().isEmpty())fact("Island rank","Island","Power rank",Items.GOLD_INGOT);
    if(standing!=null && !standing.estimate().isEmpty())
      rows.add(new Stat("","Estimated power rank",standing.estimate(),"Compared with published island power",Items.COMPASS,Math.min(powerAt,board.at())));
    if(overview)return;
    if(standing!=null && standing.target()!=null){
      var other=standing.target();rows.add(new Stat("","To pass #"+other.rank(),Numbers.integer(standing.toPass())+" power",
          "To exceed "+other.owner()+" ("+Numbers.integer(other.power())+")",Items.EXPERIENCE_BOTTLE,Math.min(powerAt,board.at())));
    }
    if(standing!=null && standing.behind()!=null){
      var other=standing.behind();rows.add(new Stat("","Lead over #"+other.rank(),Numbers.integer(standing.lead())+" power",
          "Power ahead of "+other.owner(),Items.SHIELD,Math.min(powerAt,board.at())));
    }
    fact("Island/Tier","Island","Island tier",Items.EXPERIENCE_BOTTLE);
    fact("Island/Dimensions","Island","Dimensions",Items.MAP);
    fact("Island/Generator","Island","Generator tier",Items.COBBLESTONE);
    fact("Island/Members","Island","Members",Items.PLAYER_HEAD);
    if(membership!=null)addFields(membership,Items.OAK_SAPLING);
    from(Feed.ISLANDS,Items.GRASS_BLOCK);
  }
  void update(Section section,int tab){
    sheet=app.excaliaApi.inspections.sheet(name);rows.clear();states.clear();
    for(var feed:feeds(section,tab)){
      var s=app.excaliaApi.state(feed);if(!s.isBlank()&&!states.contains(s))states.add(s);
    }
    switch(section){
      case OVERVIEW->{
        best("Balance","Skydollars",Items.GOLD_INGOT,Feed.MONEY,"Skydollars","Balance");
        island(true,false);
        best("Season tier","Season tier",Items.NETHER_STAR,Feed.PASS,"Season pass","Tier");
        fact("Achievements","Activity","Achievement score",Items.EXPERIENCE_BOTTLE);
        var score=inspected("Achievements").split("/");
        if(score.length==2){
          long earned=IslandStanding.whole(score[0].strip()),total=IslandStanding.whole(score[1].strip());
          if(earned>=0 && total>0 && earned<=total)rows.add(new Stat("Activity","Achievement progress",
              Numbers.fixed(100.0*earned/total,1)+"%","Remaining: "+Numbers.integer(total-earned)+" points",
              Items.ENCHANTED_BOOK,sheet.facts().get("Achievements").at()));
        }
        fact("Playtime","Activity","Playtime",Items.CLOCK);fact("Blocks mined","Activity","Blocks mined",Items.IRON_PICKAXE);
        fact("Crops harvested","Activity","Crops harvested",Items.WHEAT);fact("Mobs killed","Activity","Mobs killed",Items.IRON_SWORD);
        fact("Deaths","Activity","Deaths",Items.SKELETON_SKULL);
        if(rows.isEmpty())addJobs(true);
      }
      case JOBS->{} // ProfileJobs formats XP and rankings together.
      case ISLAND->{island(false,tab==0);
        if(tab==1)rows.removeIf(r->r.group().equals("Members") || r.label().equalsIgnoreCase("Max members")
            || r.label().equalsIgnoreCase("Member count") || r.label().equals("Members"));
      }
      case ECONOMY->{if(tab==0){
        best("Balance","Skydollars",Items.GOLD_INGOT,Feed.MONEY,"Skydollars","Balance");
        fact("Vote coins","Wallet","Vote coins",Items.COPPER_INGOT);
        from(Feed.MONEY,Items.GOLD_NUGGET);
      }else if(tab==2)from(Feed.MARKET,Items.CHEST);}
      case ADVENTURE->{if(tab==0){
        fact("Mobs killed","Combat","Mobs killed",Items.DIAMOND_SWORD);fact("Deaths","Combat","Deaths",Items.SKELETON_SKULL);
        for(String k:List.of("Duel rating","Duel wins","Duel losses"))fact(k,"Duels",k,Items.SHIELD);
        for(String d:List.of("Mothership","Red Base"))for(String k:List.of("Clears","Mobs"))fact(d+"/"+k,d,k,Items.COMPASS);
      }else from(feeds(section,tab)[0],tab==2?Items.FISHING_ROD:tab==1?Items.IRON_SWORD:Items.WRITABLE_BOOK);}
      case COLLECTION->from(feeds(section,tab)[0],tab==0?Items.NAME_TAG:Items.DIAMOND_AXE);
    }
  }
  private void addJobs(boolean knownOnly){
    for(var id:JobXp.NAMES.keySet()){
      var entry=app.excaliaApi.entry(id,name);var snap=app.excaliaApi.snapshot(ExcaliaApiService.jobFeed(id));
      var game=sheet==null?null:sheet.facts().get("Job/"+id);
      String level=game==null?"":game.value();long at=game==null?0:game.at();
      var cached=app.closest.history.players.getOrDefault(id,Map.of()).get(name.toLowerCase(Locale.ROOT));
      if(cached!=null&&cached.reading()!=null && cached.xpAt()>at){level=""+cached.level();at=cached.xpAt();}
      if(name.equalsIgnoreCase(Minecraft.getInstance().getUser().getName())){
        var local=app.jobs.tracker.data.jobs.get(id);
        if(local!=null && local.updated>at){level=""+local.level;at=local.updated;}
      }
      if(entry!=null && snap!=null && snap.sourceAt()>at){level=""+entry.reading().level();at=snap.sourceAt();}
      if(knownOnly && level.isEmpty())continue;
      rows.add(new Stat("Jobs",JobXp.name(id),level.isEmpty()?"Level unknown":"Level "+level,
          app.excaliaApi.rank(id,name),JobStatsScreen.icon(id),at));
    }
  }
  private void from(Feed feed,Item icon){var snapshot=app.excaliaApi.snapshot(feed);if(snapshot!=null)addFields(snapshot,icon);}
  private void addFields(ExcaliaApiClient.Snapshot snapshot,Item icon){
    for(var f:snapshot.profiles().forPlayer(name)){
      if((f.group().equals("Island")||f.group().equals("Owned island")) && (
          f.label().equals("Value")||f.label().equals("Value rank")||f.label().equals("Size")&&!inspected("Island/Dimensions").isEmpty()
          ||f.label().equals("Member count")&&!inspected("Island/Members").isEmpty()
          ||f.label().equals("Generator")&&!inspected("Island/Generator").isEmpty()))continue;
      if(f.group().equals("Skydollars")&&f.label().equals("Balance")&&rows.stream().anyMatch(r->r.label.equals("Skydollars")))continue;
      rows.add(new Stat(f.group(),f.label(),f.value(),"Public data",icon,snapshot.sourceAt()));
    }
  }
}
