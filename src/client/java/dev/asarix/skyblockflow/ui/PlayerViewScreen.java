package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.ExcaliaApiClient;
import dev.asarix.skyblockflow.ui.ProfileContent.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.*;

public class PlayerViewScreen extends FlowScreen {
  private static final int NAV_ACTIVE=0xff29444d, MINT=0xffa9e3ce, AMBER=0xffebc388;
  ProfileTheme theme;
  private final String player;
  private final PlayerViewScreen initial;
  private final IslandAtlas islands;
  private final ProfileJobs jobs;
  public boolean jobsFor(String name){return section==Section.JOBS && player.equalsIgnoreCase(name);}
  public String selectedJob(){return jobs.expanded;}
  public boolean pinned(){return !jobs.expanded.isBlank();}

  private int portraitWidth(){return section==Section.OVERVIEW?Math.clamp(bw()/4,60,117)+7:0;}
  private boolean wallet(){return section==Section.ECONOMY && tab==0;}
  private boolean atlas(){return section==Section.ISLAND && tab==0;}
  private boolean custom(){return atlas() || wallet() || section==Section.JOBS;}
  void visitIsland(dev.asarix.skyblockflow.core.ExcaliaApiData.Island row){
    minecraft.gui.setScreen(new PlayerViewScreen(app,initial==null?this:initial,row.owner(),Section.ISLAND,row.id()));
  }
  void visitMember(String name){
    minecraft.gui.setScreen(new PlayerViewScreen(app,initial==null?this:initial,name,Section.OVERVIEW,null));
  }
  @Override public void onClose(){
    if(initial==null){super.onClose();return;}
    initial.section=Section.OVERVIEW;initial.page=initial.tab=0;minecraft.gui.setScreen(initial);
  }
  private final ProfileHead avatar;
  private final ProfileContent content;
  private Section section=Section.OVERVIEW;
  private int tab,page,ticks;
  private long feedbackUntil;
  private boolean inspectingOnRefresh;
  private boolean shops(){return section==Section.ECONOMY && tab==1;}
  private NavButton prev,next;
  private final Map<Item,ItemStack> icons=new IdentityHashMap<>();
  public PlayerViewScreen(SkyblockFlowClient app,Screen parent,String player) {
    this(app,parent,player,Section.OVERVIEW,null);
  }
  PlayerViewScreen(SkyblockFlowClient app,Screen parent,String player,Section selected,UUID fixed){
    super(app,parent,player+" · Player profile");this.player=player;section=selected;
    initial=parent instanceof PlayerViewScreen profile?profile:null;
    avatar=new ProfileHead(player);content=new ProfileContent(app,player);content.fixedIsland=fixed;islands=new IslandAtlas(this,content,app);jobs=new ProfileJobs(this,app,player);
  }
  @Override public void removed(){app.excaliaApi.inspections.leave(this);super.removed();}
  public String playerName(){return player;}
  private int w(){return Math.min(680,width-12);}
  private int h(){return Math.min(398,height-12);}
  private int x(){return (width-w())/2;}
  private int y(){return (height-h())/2;}
  private int rail(){return w()>=440?99:31;}
  private int body(){return x()+rail()+11;}
  private int bw(){return w()-rail()-22;}
  private int top(){return y()+80+(section.views().isEmpty()?0:23);}
  private int bottom(){return y()+h()-27;}
  private int columns(){if(shops())return Math.clamp(bw()/128,1,4);return bw()-portraitWidth()>=215?2:1;}
  private int cardHeight(){if(shops())return 31;return h()<270 ? 30 : section==Section.OVERVIEW || section==Section.JOBS ? 40:32;}
  private int capacity(){return Math.max(1,(bottom()-top()-3)/cardHeight())*columns();}
  private int pages(){
    int size=shops()?content.shops().size():content.rows().size();
    return custom()?1:Math.max(1,(size+capacity()-1)/capacity());
  }
  private void select(Section s,int view){if(section!=s){jobsRequested=false;jobs.expanded="";}section=s;tab=view;page=0;feedbackUntil=0;rebuildWidgets();}
  @Override protected void init(){
    super.init();theme=app.store.config.darkPlayerProfiles?ProfileTheme.DARK:ProfileTheme.LIGHT;if(content.fixedIsland==null){
      if(section==Section.JOBS)app.excaliaApi.inspections.loadCached(player);else app.excaliaApi.inspections.view(this,player,false);
    }
    content.request(section,tab);content.update(section,tab);
    int dy=Math.min(27,Math.max(17,(h()-78)/6));
    int i=0;
    for(var s:Section.values()) widget(x()+4,y()+55+i++*dy,rail()-8,dy-2,s.label,s.icon,0,s==section,()->select(s,0));
    int sx=body();i=0;
    for(var label:section.views()){
      int index=i++,cell=Math.min(78,bw()/section.views().size());
      widget(sx,y()+76,cell-2,19,label,null,1,tab==index,()->select(section,index));sx+=cell;
    }
    if(section==Section.OVERVIEW){
      var target=net.minecraft.world.item.component.ResolvableProfile.createUnresolved(player);
      int ph=Math.min(bottom()-top()-16,(portraitWidth()-17)*17/10);
      var portrait=new net.minecraft.client.gui.components.PlayerSkinWidget(portraitWidth()-7,ph,minecraft.getEntityModels(),()->{
        var c=minecraft.getConnection();var info=c==null?null:c.getPlayerInfo(player);
        return info!=null?info.getSkin():minecraft.playerSkinRenderCache().getOrDefault(target).playerSkin();
      });portrait.setX(body());portrait.setY(top()+(bottom()-top()-ph)/2);addWidget(portrait);chrome.add(portrait);
    }
    int right=x()+w()-6;
    widget(right-(initial==null?47:67),y()+6,21,20,theme.dark?"Switch to light mode":"Switch to dark mode",null,4,false,()->{
      app.store.config.darkPlayerProfiles=!app.store.config.darkPlayerProfiles;app.save();rebuildWidgets();
    });
    widget(right-(initial==null?22:42),y()+6,initial==null?22:42,20,initial==null?"Close":"‹ Back",null,3,false,this::onClose);
    widget(body(),bottom()+4,54,19,"Refresh",null,2,false,()->{
      inspectingOnRefresh=content.fixedIsland==null && (section==Section.OVERVIEW || section==Section.ISLAND
          || wallet() || section==Section.ADVENTURE && tab==0);
      if(inspectingOnRefresh)app.excaliaApi.inspections.view(this,player,true);
      if(section==Section.JOBS)app.closest.requestStats(player);
      content.refresh(section,tab);refresh();feedbackUntil=System.currentTimeMillis()+4500;
    });
    if(section==Section.ISLAND)widget(body()+58,bottom()+4,76,19,atlas()?"Island details":"‹ Island map",null,2,false,()->select(Section.ISLAND,atlas()?1:0));
    if(section==Section.JOBS){
      initJobs();
      widget(body()+58,bottom()+4,76,19,"Leaderboards",null,2,false,()->minecraft.gui.setScreen(new LeaderboardsScreen(app,this)));
      if(player.equalsIgnoreCase(minecraft.getUser().getName()))widget(body()+136,bottom()+4,54,19,"Sessions",null,2,false,()->minecraft.gui.setScreen(new JobSessionScreen(app,this)));
    }
    prev=widget(right-64,bottom()+4,18,19,"‹",null,2,false,()->{page--;refresh();});
    next=widget(right-18,bottom()+4,18,19,"›",null,2,false,()->{page++;refresh();});
    refresh();
  }
  private boolean jobsRequested;
  private int jobHeight(){return Math.min(41,(bottom()-top()-2)/((7+jobColumns()-1)/jobColumns()));}
  private int jobColumns(){return bw()>=310?3:2;}
  private void initJobs(){
    if(!jobsRequested){app.closest.requestStats(player);jobsRequested=true;}
    jobs.update();
    int cols=jobColumns(),cw=(bw()-4*(cols-1))/cols,i=0;
    for(String id:dev.asarix.skyblockflow.core.JobXp.NAMES.keySet()){
      if(pinned() && !jobs.expanded.equals(id))continue;
      int x=body()+i%cols*(cw+4),y=top()+i/cols*jobHeight();
      var button=new FlowButton(x,y,pinned()?bw():cw,jobHeight()-3,dev.asarix.skyblockflow.core.JobXp.name(id),()->{
        jobs.expanded=jobs.expanded.equals(id)?"":id;rebuildWidgets();
      },false){
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
          boolean hot=inside(mx,my,getX(),getY(),getWidth(),getHeight()) || isFocused() && minecraft.getLastInputType().isKeyboard();
          jobs.drawCard(g,id,getX(),getY(),getWidth(),getHeight(),hot);
          if(hot)g.setTooltipForNextFrame(Ui.label(pinned()?"Collapse job":"Show job details"),mx,my);
        }
      };
      addWidget(button);chrome.add(button);i++;
    }
  }
  private NavButton widget(int x,int y,int w,int h,String label,Item icon,int style,boolean selected,Runnable action){
    var b=new NavButton(x,y,w,h,label,icon,style,selected,action);addWidget(b);chrome.add(b);return b;
  }
  private final class NavButton extends FlowButton {
    final String label;final Item icon;final int style;final boolean selected;
    NavButton(int x,int y,int w,int h,String label,Item icon,int style,boolean selected,Runnable run){
      super(x,y,w,h,label,run,false);this.label=label;this.icon=icon;this.style=style;this.selected=selected;
    }
    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
      int x=getX(),y=getY(),w=getWidth(),h=getHeight();boolean hot=inside(mx,my,x,y,w,h) || isFocused() && minecraft.getLastInputType().isKeyboard();
      if(style==0){
        if(selected||hot)Ui.rounded(g,x,y,w,h,3,selected?NAV_ACTIVE:0xff213742);
        if(selected)g.fill(x,y+4,x+2,y+h-4,MINT);
        item(g,icon,x+(rail()>40?5:4),y+(h-14)/2,14);
        if(rail()>40)text(g,label,x+24,y+(h-9)/2,selected?0xffe4fff4:0xffc6d6db);
        else if(hot)g.setTooltipForNextFrame(Ui.label(label),mx,my);
      }else if(style==4){
        int cx=x+w/2,cy=y+h/2;
        int surface=hot?0xff3b535c:0xff2a404c;
        Ui.rounded(g,x+1,y+1,w-2,h-2,4,surface);
        if(theme.dark){
          Ui.rounded(g,cx-3,cy-3,6,6,3,AMBER);
          for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)if(dx!=0||dy!=0){
            int px=cx+dx*(dy==0?6:4),py=cy+dy*(dx==0?6:4);g.fill(px,py,px+1,py+1,AMBER);
          }
        }else{
          Ui.rounded(g,cx-5,cy-5,10,10,5,0xffd8e5ef);
          Ui.rounded(g,cx-1,cy-7,9,9,4,surface);
        }
        if(hot)g.setTooltipForNextFrame(Ui.label(label),mx,my);
      }else if(style==3){
        int cx=x+w/2,cy=y+h/2;
        if(hot)Ui.rounded(g,x,y,w,h,3,NAV_ACTIVE);
        if(initial!=null){text(g,"‹ Back",x+4,cy-4,MINT);return;}
        for(int n=-3;n<=3;n++){g.fill(cx+n,cy+n,cx+n+1,cy+n+1,MINT);g.fill(cx+n,cy-n,cx+n+1,cy-n+1,MINT);}
      }else{
        if(hot||selected)Ui.rounded(g,x,y,w,h,3,selected?theme.focus:theme.subtle);
        if(style==1 && selected)g.fill(x+3,y+h-2,x+w-3,y+h,theme.accent);
        text(g,label,x+(w-Ui.width(label))/2,y+(h-9)/2,active?theme.ink:0xff9b9e97);
      }
    }
  }
  @Override public void tick(){if(++ticks%10==0)refresh();}
  private void refresh(){
    content.update(section,tab);if(section==Section.JOBS)jobs.update();page=Math.clamp(page,0,pages()-1);
    if(prev!=null){prev.visible=next.visible=pages()>1;prev.active=page>0;next.active=page+1<pages();}
  }
  void item(GuiGraphicsExtractor g,Item icon,int x,int y,int size){
    if(icon==null)return;g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(size/16f,size/16f);
    g.item(icons.computeIfAbsent(icon,ItemStack::new),0,0);g.pose().popMatrix();
  }
  void large(GuiGraphicsExtractor g,String label,int x,int y,float scale,int color){
    g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(scale,scale);text(g,label,0,0,color);g.pose().popMatrix();
  }
  @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e,boolean dbl){
    if(atlas() && e.button()==0 && islands.click(e.x(),e.y()))return true;
    if(super.mouseClicked(e,dbl))return true;
    if(section==Section.JOBS && pinned() && e.button()==0 && !inside(e.x(),e.y(),body(),top(),bw(),bottom()-top())){
      jobs.expanded="";rebuildWidgets();return true;
    }
    return false;
  }
  private void drawShops(GuiGraphicsExtractor g,int mx,int my){
    var listings=content.shops();
    if(listings.isEmpty()){
      String message=content.state().isBlank()?"No published shop listings for "+player:content.state();
      text(g,cut(message,bw()-8),body()+4,top()+8,theme.muted);return;
    }
    int cols=columns(),cw=(bw()-(cols-1)*4)/cols,start=page*capacity();
    for(int i=start;i<Math.min(listings.size(),start+capacity());i++){
      var row=listings.get(i);int k=i-start,x=body()+k%cols*(cw+4),y=top()+k/cols*cardHeight();
      boolean hot=inside(mx,my,x,y,cw,28);
      Ui.rounded(g,x,y,cw,28,3,hot?theme.subtle:theme.card);
      var id=net.minecraft.resources.Identifier.tryParse(row.type().toLowerCase(Locale.ROOT));
      var icon=id==null?null:net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
      item(g,icon==null || icon==Items.AIR?Items.CHEST:icon,x+4,y+2,13);
      String side=row.buying()?"Buys":"Sells";int badge=Ui.width(side)+6;
      Ui.rounded(g,x+cw-badge-3,y+3,badge,12,3,row.buying()?theme.goldChip:theme.focus);
      text(g,side,x+cw-badge,y+5,row.buying()?theme.goldText:theme.accent);
      text(g,cut(row.name(),cw-badge-26),x+20,y+5,theme.ink);
      String price=row.price()+" / ea",stock=row.stock().equals("Unlimited")?"Unlimited":"Stock "+row.stock();
      int room=cw-12,stockWidth=Math.min(Ui.width(stock),room/2);
      text(g,cut(price,room-stockWidth-4),x+5,y+17,theme.ink);
      text(g,cut(stock,stockWidth),x+cw-5-stockWidth,y+17,theme.muted);
      if(hot){
        var lines=List.of(row.name(),side+" for "+row.price()+" skydollars each","Stock: "+row.stock());
        g.setComponentTooltipForNextFrame(minecraft.font,lines.stream().map(Ui::label).toList(),mx,my);
      }
    }
  }
  private void drawWallet(GuiGraphicsExtractor g,int x,int y,int w,int h,int mx,int my){
    var rows=content.rows();String amount=rows.stream().filter(r->r.label().equals("Skydollars")).map(Stat::value).findFirst().orElse("Unavailable");
    Ui.rounded(g,x,y,w,30,4,0xff23483f);text(g,"Skydollars",x+9,y+4,0xffbbd7be);
    large(g,amount,x+9,y+16,1.18f,0xfff4fcf4);String coins=content.inspected("Vote coins");
    if(!coins.isEmpty()){String label=coins+" vote coins";text(g,label,x+w-9-Ui.width(label),y+13,AMBER);}
    if(inside(mx,my,x,y,w,30)){
      var lines=new ArrayList<net.minecraft.network.chat.Component>();
      lines.add(Ui.label(player+" · "+amount+" skydollars"));
      rows.stream().filter(r->r.label().equalsIgnoreCase("Share")).findFirst().ifPresent(r->{
        try{lines.add(Ui.label(dev.asarix.skyblockflow.core.Numbers.fixed(Double.parseDouble(r.value()),2)+"% of published economy"));}catch(NumberFormatException ignored){}
      });
      var balance=rows.stream().filter(r->r.label().equals("Skydollars")).findFirst();
      balance.ifPresent(r->lines.add(Ui.label(r.detail()+" · "+ago(r.at()))));
      g.setComponentTooltipForNextFrame(minecraft.font,lines,mx,my);
    }
    text(g,"Balance leaderboard",x+3,y+37,theme.ink);
    var all=app.excaliaApi.wallets();int me=-1;
    for(int i=0;i<all.size();i++)if(player.equalsIgnoreCase(all.get(i).name())){me=i;break;}
    if(all.isEmpty()){text(g,cut(content.state().isBlank()?"No published balances":content.state(),w-8),x+4,y+54,theme.muted);return;}
    int count=Math.min(all.size(),Math.max(1,(h-51-(me<0?14:0))/18));
    int start=me<0?0:Math.clamp(me-count/2,0,all.size()-count);
    for(int i=0;i<count;i++){
      var row=all.get(start+i);int ry=y+50+i*18;boolean active=start+i==me;
      Ui.rounded(g,x,ry,w,16,3,active?theme.selected:(i%2==0?theme.card:theme.row));
      if(active)g.fill(x,ry+2,x+2,ry+14,theme.progress);
      text(g,"#"+row.rank(),x+7,ry+4,active?theme.accent:theme.muted);
      text(g,row.name(),x+46,ry+4,theme.ink);
      String value=dev.asarix.skyblockflow.core.Numbers.decimal(row.balance(),0,2);
      text(g,value,x+w-8-Ui.width(value),ry+4,theme.ink);
    }
    if(me<0)text(g,cut(player+" · Outside published top "+all.getLast().rank(),w-6),x+3,y+51+count*18,theme.muted);
  }
  static String ago(long time){
    long m=Math.max(0,(System.currentTimeMillis()-time)/60_000);
    return m==0?"just now":m<60?m+"m ago":m<1440?m/60+"h ago":m/1440+"d ago";
  }
  private String freshness(){
    if(System.currentTimeMillis()<feedbackUntil){
      if(content.busy(section,tab) || inspectingOnRefresh && app.excaliaApi.inspections.pending(this)){
        feedbackUntil=System.currentTimeMillis()+2500;return "Refreshing…";
      }
      if(!content.state().isBlank())return content.state();
      if(inspectingOnRefresh){String status=app.excaliaApi.inspections.status(player);if(!status.isBlank())return status;}
      return "Up to date";
    }
    if(content.fixedIsland!=null){var a=content.atlas;return a!=null && a.at()>0?"Island snapshot · "+ago(a.at()):"Loading island…";}
    var service=app.excaliaApi.inspections;var s=service.sheet(player);
    if(s==null)return service.status(player);
    boolean old=System.currentTimeMillis()-s.at()>=ExcaliaApiClient.MAX_QUOTE_AGE||service.departed(player)||service.archived(player);
    return old?"Saved "+ago(s.at())+" · possibly outdated":"Inspected "+ago(s.at());
  }
  @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
    g.fill(0,0,width,height,0xd8101921);
    int x=x(),y=y(),w=w(),h=h(),b=body();
    Ui.rounded(g,x-2,y-2,w+4,h+5,7,0x90061016);
    Ui.rounded(g,x,y,w,h,6,theme.paper);
    g.fill(x,y+4,x+rail(),y+h-3,theme.rail);
    g.fillGradient(x,y,x+w,y+47,theme.headerTop,theme.headerBottom);
    g.fill(x,y+47,x+w,y+48,0xff48625e);
    Ui.rounded(g,x+9,y+6,36,36,3,0xff68847c);avatar.draw(g,x+11,y+8,32);
    String grade=content.grade();
    text(g,"EXCALIA   /   SEASON 3",x+54,y+6,AMBER);
    large(g,player,x+53,y+18,1.55f,0xfff5f7ef);
    text(g,grade.isBlank()?"Player profile":grade,x+54,y+35,MINT);
    if(w>430) {
      String f=freshness();text(g,cut(f,Math.min(190,w/3)),x+w-32-Math.min(190,Ui.width(f)),y+32,0xffc6d6db);
    }
    large(g,section.label,b,y+56,1.28f,theme.ink);
    if(w<=430) {
      String f=cut(freshness(),bw()-Ui.width(section.label)*2-14);
      text(g,f,b+bw()-Ui.width(f),y+59,theme.muted);
    }
    g.fill(b,y+72,b+bw(),y+73,theme.rule);
    if(atlas())islands.draw(g,b,top(),bw(),bottom()-top()-3,mx,my);
    else if(section==Section.JOBS)jobs.drawDetails(g,b,top()+jobHeight()+2,bw(),bottom()-top()-jobHeight()-4,mx,my);
    else if(shops())drawShops(g,mx,my);
    else if(wallet())drawWallet(g,b,top(),bw(),bottom()-top()-3,mx,my);
    else{
    int pw=portraitWidth();
    if(pw>0){
      int right=b+pw-7;g.fillGradient(b,top(),right,bottom()-4,theme.portraitTop,theme.portraitBottom);
      g.fill(b+5,top()+6,b+18,top()+7,0xffa8ccae);
      g.fill(b+5,top()+6,b+6,top()+18,0xffa8ccae);
      g.fill(right-17,bottom()-11,right-5,bottom()-10,0xff6a938a);
    }
    var rows=content.rows();int cols=columns(),cw=(bw()-pw-(cols-1)*6)/cols,start=page*capacity();
    for(int i=start;i<Math.min(rows.size(),start+capacity());i++){
      var r=rows.get(i);int idx=i-start,cx=b+pw+idx%cols*(cw+6),cy=top()+idx/cols*cardHeight(),ch=cardHeight()-4;
      Ui.rounded(g,cx,cy,cw,ch,3,theme.card);
      int marker=section==Section.OVERVIEW && i<3?0xffbe9350:0xff8ba79d;
      if(section==Section.OVERVIEW && i==0)Ui.rounded(g,cx,cy,cw,ch,3,theme.dark?theme.selected:0xffe8eee4);
      g.fill(cx,cy+5,cx+2,cy+ch-5,marker);
      item(g,r.icon(),cx+7,cy+(ch-16)/2,16);
      int tx=cx+29,avail=cw-34;
      String title=r.group().isEmpty()||r.group().equals("Activity")||r.group().equals("Jobs")
          ||r.group().equals(section.label) ? r.label():r.group()+" · "+r.label();
      if(Ui.width(title)>avail && title.equals("Achievement progress"))title="Achievements";
      text(g,cut(title,avail),tx,cy+3,theme.muted);
      String value=r.value();
      if(section==Section.JOBS)value+="   "+r.detail();
      float scale=ch>30 && Ui.width(value)*1.25<=avail ? 1.25f:1;
      large(g,cut(value,(int)(avail/scale)),tx,cy+(ch>30?17:15),scale,theme.ink);
      boolean stale=r.at()>0 && System.currentTimeMillis()-r.at()>=ExcaliaApiClient.MAX_QUOTE_AGE;
      if(stale)g.fill(cx+cw-5,cy+3,cx+cw-3,cy+5,0xffb17e36);
      if((stale || r.label().startsWith("To pass") || r.label().startsWith("Lead over") || r.label().equals("Achievement progress") || Ui.width(title)>avail || Ui.width(value)>avail) && inside(mx,my,cx,cy,cw,ch))g.setTooltipForNextFrame(Ui.label(title+" · "+r.value()+" · "+r.detail()
          +(r.at()>0?" · "+(stale?"Possibly outdated · ":"")+ago(r.at()):"")),mx,my);
    }
    if(rows.isEmpty()){b+=pw;
      int ey=top()+8;item(g,section.icon,b+3,ey,19);
      String msg=!content.state().isBlank()?content.state():section==Section.OVERVIEW?
          "Waiting for player information":"No published values for this section";
      text(g,cut(msg,bw()-34),b+30,ey+4,theme.muted);
      if(section==Section.OVERVIEW)text(g,cut("An online inspection supplies activity and job levels.",bw()-4),b+2,ey+27,theme.muted);
    }
    }
    b=body();g.fill(b,bottom(),b+bw(),bottom()+1,theme.rule);
    if(pages()>1){String p=(page+1)+"/"+pages();text(g,p,x+w-47+(28-Ui.width(p))/2,bottom()+9,theme.muted);}
    renderChrome(g,mx,my,dt);
  }
}
