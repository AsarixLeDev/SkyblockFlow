package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.ExcaliaApiData.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;

final class IslandAtlas {
  private ProfileTheme theme;
  private boolean compact;
  private static final Identifier[] ART=new Identifier[3];
  static {for(int i=0;i<ART.length;i++)ART[i]=Identifier.fromNamespaceAndPath("skyblockflow","textures/gui/profile_island_"+i+".png");}
  private final PlayerViewScreen host;
  private final ProfileContent content;
  private final SkyblockFlowClient app;
  private record Hit(int x,int y,int w,int h,Runnable action){}
  private final List<Hit> links=new ArrayList<>();
  private float ox,oy,scale,mx,my;
  private List<String> tooltip=List.of();
  private int memberPage;
  private final Map<UUID,Long> neighborRequests=new HashMap<>();
  private UUID hoveredNeighbor;
  private long hoverStarted;
  private boolean onNeighbor;
  private void warmNeighbor(Island row){
    onNeighbor=true;long now=System.currentTimeMillis();
    if(!row.id().equals(hoveredNeighbor)){hoveredNeighbor=row.id();hoverStarted=now;}
    if(now-hoverStarted<350 || now<neighborRequests.getOrDefault(row.id(),0L))return;
    neighborRequests.put(row.id(),now+30_000);app.excaliaApi.islandDetail(row.id());
  }
  IslandAtlas(PlayerViewScreen host,ProfileContent content,SkyblockFlowClient app){this.host=host;this.content=content;this.app=app;}
  private boolean inside(double x,double y,int l,int t,int w,int h){return x>=l && x<l+w && y>=t && y<t+h;}
  boolean click(double sx,double sy){
    if(scale<=0)return false;
    for(var hit:links.reversed())if(inside((sx-ox)/scale,(sy-oy)/scale,hit.x,hit.y,hit.w,hit.h)){hit.action.run();return true;}
    return false;
  }
  private void widget(GuiGraphicsExtractor g,int x,int y,int w,int h,Runnable action,Supplier<List<String>> tip,Runnable paint){
    if(action!=null)links.add(new Hit(x,y,w,h,action));
    boolean hover=inside(mx,my,x,y,w,h);
    g.pose().pushMatrix();
    if(hover){
      tooltip=tip.get();float cx=x+w*.5f,cy=y+h*.5f;
      g.pose().translate(cx,cy);g.pose().scale(1.045f,1.045f);g.pose().translate(-cx,-cy);
      Ui.rounded(g,x-1,y+1,w+2,h+2,5,theme.shadow);Ui.rounded(g,x-1,y-1,w+2,h+2,5,theme.hover);
    }
    paint.run();g.pose().popMatrix();
  }
  private void center(GuiGraphicsExtractor g,String text,int x,int y,int color){Ui.text(g,text,x-Ui.width(text)/2,y,color);}
  private String num(long n){return Numbers.integer(n);}
  private String money(BigDecimal n){return n==null?"Unavailable":Numbers.decimal(n,0,2);}
  private String age(Instant since){
    if(since==null)return "Unknown";
    long hours=Math.max(0,Duration.between(since,Instant.now()).toHours());return hours/24+"d "+hours%24+"h";
  }
  private String date(Instant t){return t==null?"Unavailable":DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm z",Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(t);}
  private void neighbor(GuiGraphicsExtractor g,Island row,boolean ahead,long gap){
    if(row==null)return;
    int x=ahead?284:6,c=x+47,arrow=ahead?267:110;
    if(inside(mx,my,x,compact?22:43,94,compact?63:77))warmNeighbor(row);
    Runnable go=()->host.visitIsland(row);
    Supplier<List<String>> tip=()->List.of(row.owner()+" · #"+row.rank(),num(row.power())+" power",
        ahead?num(gap)+" power to go to pass":num(gap)+" power ahead","Click to open island");
    widget(g,x,compact?22:43,94,compact?63:77,go,tip,()->{
      g.blit(ART[Math.floorMod(row.id().hashCode(),3)],c-30,compact?24:48,c+30,compact?56:88,0,1,0,1);
      center(g,"#"+row.rank()+"  ·  "+num(row.power()),c,compact?72:106,theme.mapMuted);
    });
    int nameWidth=Math.min(90,Ui.width(row.owner())+8),nameY=compact?60:93;
    widget(g,c-nameWidth/2,nameY-2,nameWidth,12,()->host.visitMember(row.owner()),()->{
      var detail=app.excaliaApi.cachedIslandDetail(row.id());
      Instant joined=detail==null?null:detail.roster().stream().filter(m->m.name().equalsIgnoreCase(row.owner()))
          .map(Member::joined).filter(Objects::nonNull).findFirst().orElse(null);
      return List.of(row.owner()+" · owner",joined==null?"Join date unavailable":"Member for "+age(joined)+" · "+date(joined),"Click to open profile");
    },()->center(g,Ui.trim(row.owner(),90),c,nameY,theme.mapText));
    int arrowY=compact?49:72;
    widget(g,arrow-8,arrowY,16,20,go,tip,()->{
      int dir=ahead?1:-1;
      for(int i=-4;i<=4;i++){int xx=arrow+dir*(2-Math.abs(i));g.fill(xx,arrowY+10+i,xx+2,arrowY+11+i,theme.accent);}
    });
  }
  void draw(GuiGraphicsExtractor g,int x,int y,int w,int h,int mouseX,int mouseY){
    theme=host.theme;links.clear();tooltip=List.of();onNeighbor=false;
    g.fillGradient(x,y,x+w,y+h,theme.sky,theme.horizon);
    var a=content.atlas;var d=a==null?null:a.detail();var s=a==null?null:a.standing();
    if(a==null || (a.power()<0 && d==null && (s==null||s.published()==null))){
      center(g,Ui.trim(content.state().isBlank()?"Island information unavailable":content.state(),w-10),x+w/2,y+h/2,theme.mapMuted);return;
    }
    compact=h<157;int logicalHeight=compact?158:209;
    scale=Math.min(w/384f,h/(float)logicalHeight);ox=x+(w-384*scale)/2;oy=y+(h-logicalHeight*scale)/2;
    mx=(mouseX-ox)/scale;my=(mouseY-oy)/scale;g.pose().pushMatrix();g.pose().translate(ox,oy);g.pose().scale(scale,scale);
    if(theme.dark){
      // Keep the small points clear of the labels and controls.
      for(int i=0;i<27;i++){int sx=5+(i*109)%374,sy=2+(i*37)%58;
        g.fill(sx,sy,sx+1,sy+1,(i%4==0)?0xff839ba6:0xff4b6374);}
    }
    int tier=d==null?0:IslandUpgrades.tier(d.size());
    if(tier==0){String observed=content.inspected("Island/Tier").split("/")[0].trim();if(observed.matches("[1-9][0-9]?"))tier=Integer.parseInt(observed);}
    final int level=tier;String label=level==0?"Island level unavailable":"Island level "+level+" / 60";
    widget(g,124,2,136,24,null,()->List.of(label,level==60?"Maximum island level":needed(level==0?0:level+1,a.power()),
        d!=null&&d.size()>0?"Island size: "+d.size()+" × "+d.size():"Size unavailable"),()->{
      center(g,label,192,4,theme.accent);Ui.rounded(g,130,17,124,4,2,theme.track);
      if(level>0)Ui.rounded(g,130,17,(int)(124*level/60f),4,2,theme.progress);
    });
    Island own=s==null?null:s.published();
    String owner=d!=null?d.owner():own!=null?own.owner():"";
    String rank=own!=null?"#"+own.rank():s!=null&&!s.estimate().isEmpty()?"Est. "+s.estimate():content.inspected("Island rank");
    String power=a.power()<0?"Power unavailable":num(a.power())+" power";
    widget(g,130,compact?23:29,124,compact?62:92,null,()->List.of(owner.isBlank()?"Island":owner+"'s island",power,rank.isBlank()?"Rank unavailable":rank,
        a.at()>0?"Snapshot "+PlayerViewScreen.ago(a.at()):"Snapshot time unavailable"),()->{
      g.blit(ART[d!=null?Math.floorMod(d.id().hashCode(),3):0],compact?158:145,compact?23:29,compact?226:239,compact?62:90,0,1,0,1);
      center(g,Ui.trim(owner.isBlank()?"Island":owner+"'s island",121),192,compact?63:94,theme.accent);
      center(g,power+(rank.isBlank()?"":"  ·  "+rank),192,compact?76:106,theme.mapText);
    });
    if(s!=null){neighbor(g,s.behind(),false,s.lead());neighbor(g,s.target(),true,s.toPass());}
    if(!onNeighbor)hoveredNeighbor=null;
    List<Member> roster=d==null?(owner.isEmpty()?List.of():List.of(new Member(owner,"OWNER",null))):d.roster();
    var ordered=new ArrayList<>(roster);ordered.sort(Comparator.comparing(m->!m.name().equalsIgnoreCase(owner)));
    int per=6, pages=(ordered.size()+per-1)/per;memberPage=Math.clamp(memberPage,0,Math.max(0,pages-1));
    if(ordered.isEmpty())center(g,"Member list unavailable",192,compact?98:144,theme.mapMuted);
    for(int first=memberPage*per;first<Math.min(ordered.size(),(memberPage+1)*per);first+=3){
      int end=Math.min(first+3,ordered.size()),count=end-first,cw=104,yy=(compact?88:126)+(first%per/3)*18;
      for(int i=first;i<end;i++){
        var m=ordered.get(i);int xx=192-count*cw/2+(i-first)*cw;boolean leader=m.name().equalsIgnoreCase(owner);
        widget(g,xx+2,yy,cw-4,16,()->host.visitMember(m.name()),()->List.of(m.name()+" · "+m.role().replace('_',' ').toLowerCase(Locale.ROOT),
            m.joined()==null?"Join date unavailable":"Member for "+age(m.joined())+" · "+date(m.joined()),"Click to open profile"),()->{
          Ui.rounded(g,xx+2,yy,cw-4,16,4,leader?theme.goldChip:theme.chip);
          if(leader)host.item(g,Items.GOLDEN_HELMET,xx+6,yy+2,12);
          center(g,Ui.trim(m.name(),cw-(leader?24:10)),xx+cw/2+(leader?5:0),yy+4,leader?theme.goldText:theme.mapText);
        });
      }
    }
    if(pages>1)widget(g,352,compact?106:143,29,17,()->memberPage=(memberPage+1)%pages,()->List.of("Next members"),()->center(g,(memberPage+1)+"/"+pages,367,compact?110:147,theme.accent));
    int next=IslandUpgrades.nextGenerator(level);
    String generator=d!=null&&!d.generator().isBlank()?d.generator().replace('_',' '):content.inspected("Island/Generator");
    metric(g,4,Items.CLOCK,"Island age",d==null?"Unknown":age(d.created()),()->List.of("Created "+date(d==null?null:d.created())));
    metric(g,131,Items.GOLD_INGOT,"Island bank",money(d==null?null:d.bank()),()->funds(d));
    metric(g,258,Items.FURNACE,"Generator",generator.isBlank()?"Unknown":generator,()->List.of(
        next>0?"Next upgrade: Tier "+(2+(next-11)/10)+" at level "+next:level>=51?"Maximum generator tier":"Next upgrade unavailable",
        next>0?needed(next,a.power()):level>=51?"No further upgrade this season":"Level unavailable"));
    g.pose().popMatrix();
    if(!tooltip.isEmpty())g.setComponentTooltipForNextFrame(net.minecraft.client.Minecraft.getInstance().font,tooltip.stream().map(Ui::label).toList(),mouseX,mouseY);
  }
  private String needed(int level,long power){
    int threshold=IslandUpgrades.power(level);
    return threshold<0||power<0?"Power requirement unavailable":num(Math.max(0,threshold-power))+" power left · "+num(threshold)+" required";
  }
  private void metric(GuiGraphicsExtractor g,int x,Item icon,String label,String value,Supplier<List<String>> tip){
    int y=compact?125:172;
    widget(g,x,y,122,32,null,tip,()->{
      Ui.rounded(g,x,y,122,32,4,theme.widget);host.item(g,icon,x+6,y+8,16);
      Ui.text(g,label,x+29,y+5,theme.mapMuted);Ui.text(g,Ui.trim(value,91),x+29,y+18,theme.mapText);
    });
  }
  private List<String> funds(IslandDetail d){
    if(d==null)return List.of("Island bank unavailable");
    var detail=new ArrayList<String>();detail.add("Island bank   "+money(d.bank()));
    var wallets=app.excaliaApi.wallets();var snap=app.excaliaApi.snapshot(ExcaliaApiClient.Feed.MONEY);
    boolean complete=d.bank()!=null;BigDecimal sum=complete?d.bank():BigDecimal.ZERO;
    for(var m:d.roster()){
      var wallet=wallets.stream().filter(b->b.name().equalsIgnoreCase(m.name())).findFirst().orElse(null);
      var sheet=app.excaliaApi.inspections.sheet(m.name());var fact=sheet==null?null:sheet.facts().get("Balance");
      BigDecimal value=wallet==null?null:wallet.balance();long at=wallet==null?0:snap.sourceAt();
      if(fact!=null && fact.at()>at)try{
        String raw=fact.value().replaceAll("[\\s'\\u00a0\\u202f]","").replace(',','.');
        value=new BigDecimal(raw);at=fact.at();
      }catch(NumberFormatException ignored){}
      if(value!=null)sum=sum.add(value);else complete=false;
      detail.add(m.name()+"   "+money(value)+(at>0?" · "+PlayerViewScreen.ago(at):""));
    }
    detail.addFirst((complete?"Total island wealth   ":"Known subtotal   ")+money(sum));
    return detail;
  }
}
