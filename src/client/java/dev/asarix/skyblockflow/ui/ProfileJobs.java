package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class ProfileJobs {
  private final PlayerViewScreen host;
  private final SkyblockFlowClient app;
  private final String player;
  private final Map<String,Card> cards=new LinkedHashMap<>();
  private List<Detail> personal=List.of(),ranking=List.of();
  String expanded="";
  private record Card(int level,double fraction,String rank,boolean first,String levelText,String progressText){}
  private record Detail(String label,String value,double bar){}
  ProfileJobs(PlayerViewScreen host,SkyblockFlowClient app,String player){this.host=host;this.app=app;this.player=player;}
  private boolean own(){return player.equalsIgnoreCase(Minecraft.getInstance().getUser().getName());}
  private JobTracker.Job job(String id) {
    var api = app.excaliaApi.entry(id,player);
    var source = app.excaliaApi.snapshot(ExcaliaApiService.jobFeed(id));
    var observed = app.closest.history.players.getOrDefault(id,Map.of()).get(player.toLowerCase(Locale.ROOT));
    var local = own() ? app.jobs.tracker.data.jobs.get(id) : null;
    var reading = observed == null ? null : observed.reading();
    long at = observed == null ? 0 : observed.xpAt();
    if (api != null && Double.isFinite(api.reading().xp()) && source != null && source.sourceAt() >= at) {
      reading = api.reading(); at = source.sourceAt();
    }
    if (local != null && (reading == null || local.updated >= at)) return local;
    if (reading == null) return null;
    var result = new JobTracker.Job(); result.id=id; result.level=reading.level();
    result.updated=at;result.xp=reading.xp(); result.required=reading.required(); return result;
  }
  private RankHistory.Player rival(String id,int rank){
    var snapshot=app.excaliaApi.snapshot(ExcaliaApiService.jobFeed(id));
    if(snapshot==null)return null;
    int wanted=rank==1?2:rank-1;
    var entries=((ExcaliaApiData.Jobs)snapshot.data()).boards().getOrDefault(id,List.of());
    for(var row:entries)if(row.rank()==wanted)return new RankHistory.Player(row.rank(),row.name(),row.reading().level(),
        Double.isFinite(row.reading().xp())?row.reading():null,snapshot.sourceAt());
    if(own()){
      var other=app.closest.result(id);
      if(other!=null && other.rank()==wanted && other.reading()!=null)return new RankHistory.Player(wanted,other.name(),other.reading().level(),other.reading(),other.at());
    }
    return null;
  }
  void update(){
    var sheet=app.excaliaApi.inspections.sheet(player);
    for(String id:JobXp.NAMES.keySet()){
      var j=job(id);int level=j==null?-1:j.level;
      long at=j==null?0:j.updated;
      var row=app.excaliaApi.entry(id,player);var source=app.excaliaApi.snapshot(ExcaliaApiService.jobFeed(id));
      if(row!=null && source.sourceAt()>=at){level=row.reading().level();at=source.sourceAt();}
      var observed=sheet==null?null:sheet.facts().get("Job/"+id);
      if(observed!=null && observed.at()>at)try{level=Integer.parseInt(observed.value());}catch(NumberFormatException ignored){}
      if(j!=null && j.level!=level)j=null;
      boolean max=level==200;
      double f=max?1:j!=null && j.required>0 && Double.isFinite(j.xp)?Math.clamp(j.xp/j.required,0,1):Double.NaN;
      String rank=app.excaliaApi.rank(id,player);
      var card=new Card(level,f,rank,row!=null && row.rank()==1,level<0?"Lv. —":"Lv. "+level+(max?" · Max":""),
          Double.isFinite(f)?Numbers.fixed(f*100,1)+"%":"XP unavailable");
      cards.put(id,card);
      if(expanded.equals(id))details(id,j,card,row==null?0:row.rank());
    }
  }
  private void details(String id,JobTracker.Job j,Card card,int rank){
    boolean max=card.level==200,ready=JobXp.curvesReady();
    boolean known=max || j!=null && Double.isFinite(card.fraction);
    String xp=max?"Level 200 complete":known?JobXp.amount(j.xp)+" / "+JobXp.amount(j.required)+" XP":"XP unavailable";
    var left=new ArrayList<Detail>();
    left.add(new Detail("Level progress · "+card.progressText,xp,card.fraction));
    double goal=ready?JobXp.totalAt(id,200):0;
    double total=ready && known?(max?goal:JobXp.totalAt(id,card.level)+j.xp):Double.NaN;
    double f=total/goal;
    left.add(new Detail("Season progress"+(Double.isFinite(f)?" · "+Numbers.fixed(100*f,1)+"%":""),
       !ready?"Calculating…":known?JobXp.amount(total)+" / "+JobXp.amount(goal)+" XP":"Total XP unavailable",f));
    personal=List.copyOf(left);
    var right=new ArrayList<Detail>();var other=rank>0?rival(id,rank):null;
    if(other==null)right.add(new Detail("Leaderboard",rank<=0?card.rank:"Neighbor unavailable",Double.NaN));
    else{
      var r=other.reading();String amount=r==null?"XP unavailable":other.level()==200?"Level 200 complete":
          JobXp.amount(r.xp())+" / "+JobXp.amount(r.required())+" XP";
      double fraction=other.level()==200?1:r!=null && r.required()>0?r.xp()/r.required():Double.NaN;
      right.add(new Detail((rank==1?"Second place":"Next player")+" · #"+other.rank(),other.name()+" · Lv. "+other.level(),Double.NaN));
      right.add(new Detail("Rival progress"+(Double.isFinite(fraction)?" · "+Numbers.fixed(100*fraction,1)+"%":""),amount,fraction));
      double gap=known && r!=null && ready?JobMessages.gap(id,card.level,max?0:j.xp,max?0:j.required,r):Double.NaN;
      right.add(new Detail(rank==1?"Lead over second place":"XP to next player",Double.isFinite(gap)?JobXp.amount(Math.max(0,rank==1?-gap:gap))+" XP":"Unavailable",Double.NaN));
    }
    ranking=List.copyOf(right);
  }
  private static void bar(GuiGraphicsExtractor g,int x,int y,int w,double fraction,int track,int fill){
    Ui.rounded(g,x,y,w,3,1,track);
    if(Double.isFinite(fraction) && fraction>0)Ui.rounded(g,x,y,Math.min(w,Math.max(1,(int)Math.round(w*fraction))),3,1,fill);
  }
  void drawCard(GuiGraphicsExtractor g,String id,int x,int y,int w,int h,boolean hot){
    var c=cards.get(id);if(c==null)return;var t=host.theme;boolean max=c.level==200;
    boolean compact=h<32;
    int ink=max?t.goldText:t.ink,accent=max?0xffca9a46:t.accent;
    g.pose().pushMatrix();
    if(hot){float cx=x+w*.5f,cy=y+h*.5f;g.pose().translate(cx,cy);g.pose().scale(1.025f,1.025f);g.pose().translate(-cx,-cy);
      Ui.rounded(g,x-1,y+1,w+2,h+2,4,t.shadow);}
    if(c.first){
      int a=(int)(155+65*Math.sin(System.nanoTime()/650_000_000.0));
      Ui.rounded(g,x-3,y-3,w+6,h+6,6,(a/7<<24)|0x00ffd47b);
      Ui.rounded(g,x-2,y-2,w+4,h+4,5,(a/2<<24)|0x00ffd47b);
      Ui.rounded(g,x-1,y-1,w+2,h+2,4,(a<<24)|0x00ffc563);
    }
    Ui.rounded(g,x,y,w,h,3,max?t.goldChip:hot?t.subtle:t.card);
    host.item(g,JobStatsScreen.icon(id),x+4,y+(compact?1:3),13);
    String name=JobXp.name(id);
    Ui.text(g,Ui.trim(name,w-22),x+20,y+(compact?2:5),ink);
    if(expanded.equals(id))Ui.text(g,"Collapse",x+w-6-Ui.width("Collapse"),y+(compact?2:5),max?t.goldText:t.muted);
    int baseline=y+h-(compact?14:17);
    String rank=c.rank.startsWith("#")||c.rank.equals("Unranked")?c.rank:"—";
    int right=x+w-5;
    Ui.text(g,Ui.trim(c.levelText,w-11-Ui.width(rank)),x+5,baseline,ink);
    Ui.text(g,rank,right-Ui.width(rank),baseline,c.first?(t.dark?0xffffd585:0xff805714):t.muted);
    bar(g,x+5,y+h-(compact?4:6),w-10,c.fraction,t.track,accent);
    g.pose().popMatrix();
  }
  void drawDetails(GuiGraphicsExtractor g,int x,int y,int w,int h,int mx,int my){
    if(expanded.isEmpty())return;
    int half=(w-5)/2;
    detailColumn(g,x,y,half,h,personal,mx,my);detailColumn(g,x+half+5,y,w-half-5,h,ranking,mx,my);
  }
  private void detailColumn(GuiGraphicsExtractor g,int x,int y,int w,int h,List<Detail> rows,int mx,int my){
    var t=host.theme;int step=Math.min(43,h/Math.max(1,rows.size()));
    for(int i=0;i<rows.size();i++){
      var r=rows.get(i);int top=y+i*step,hh=step-2;Ui.rounded(g,x,top,w,hh,3,t.card);
      Ui.text(g,Ui.trim(r.label,w-12),x+6,top+3,t.muted);
      Ui.text(g,Ui.trim(r.value,w-12),x+6,top+Math.min(16,Math.max(11,hh-12)),t.ink);
      if(Double.isFinite(r.bar) && hh>=24)bar(g,x+6,top+hh-4,w-12,r.bar,t.track,t.progress);
      if((Ui.width(r.label)>w-12 || Ui.width(r.value)>w-12) && FlowScreen.inside(mx,my,x,top,w,hh))g.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
          List.of(Ui.label(r.label),Ui.label(r.value)),mx,my);
    }
  }
}
