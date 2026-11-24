package dev.asarix.flow.update;

import java.io.IOException;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class UpdateScreen extends Screen {
    private static final int INK=0xffeaf0f4, MUTED=0xffa0b0c2, MINT=0xff9de7cc,
            PANEL=0xff17212f, LINE=0xff314052, GOLD=0xffefcd95;
    private final Updates model;
    private final Screen parent;
    private final net.minecraft.network.chat.FontDescription face;
    private final List<AbstractWidget> controls=new ArrayList<>();
    private Updates.State displayed;
    private boolean confirm;
    private int x,y,w,h,page, pages;
    private String warning="";

    UpdateScreen(Screen parent, Updates model) {
        super(Component.literal((model.account==null?"SkyblockFlow":model.account.name())+" updates"));
        this.parent=parent;this.model=model;
        String ns=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(model.host)?model.host:"skyblockflow";
        face=new net.minecraft.network.chat.FontDescription.Resource(net.minecraft.resources.Identifier.fromNamespaceAndPath(ns,"ui"));
    }

    @Override protected void init() {layout();}
    @Override public void tick() {if(displayed!=model.state)layout();}
    @Override public void onClose() {
        if(confirm){confirm=false;layout();}else minecraft.gui.setScreen(parent);
    }

    private void button(int px,int py,int width,String name,boolean primary,Runnable act) {
        var widget=new Control(px,py,width,19,name,primary,act,null);
        controls.add(widget);addWidget(widget);
    }

    private void layout() {
        clearWidgets();controls.clear();
        displayed=model.state;
        w=Math.min(434,width-14);
        int entries=model.release==null?0:(int)model.release.changes().stream().filter(c->model.developer || !c.type().equals("Refactor")).count();
        h=Math.min(height-12,Math.min(303,Math.max(confirm?228:202,150+33*entries)));
        x=(width-w)/2;y=(height-h)/2;
        int left=x+14, right=x+w-14, footer=y+h-27, prefs=footer-24;
        button(right-20,y+10,20,"×",false,this::onClose);
        if(confirm) {
            button(right-112,footer,112,"Download anyway",true,()->{confirm=false;model.download();layout();});
            button(left,footer,54,"Back",false,this::onClose);
            return;
        }
        int devW=Math.min(120,(w-34)/2);
        button(left,prefs,devW,(model.developer?"☑ ":"☐ ")+"Developer mode",false,()->{model.preference(false);page=0;layout();});
        button(left+devW+4,prefs,Math.min(164,w-devW-32),(model.beta?"☐ ":"☑ ")+"Don't suggest beta releases",false,()->{
            model.preference(true);
            if(model.state==Updates.State.EMPTY || model.state==Updates.State.OFFER && !model.beta && model.release.beta())model.check(false);
            layout();
        });
        if(displayed==Updates.State.OFFER) {
            button(right-88,footer,88,"Download",true,()->{
                try{model.prepare();confirm=true;warning="";}catch(IOException e){warning=e.getMessage();}
                layout();
            });
            addNotes(left,right,prefs-5);
        } else if(displayed==Updates.State.DOWNLOADING) {
            button(right-88,footer,88,"Hide progress",true,this::onClose);
            button(left,footer,64,"Cancel",false,model::cancel);
        } else if(displayed==Updates.State.EMPTY || displayed==Updates.State.ERROR || displayed==Updates.State.DONE){
            if(displayed!=Updates.State.DONE)button(left,footer,74,"Check again",false,()->model.check(false));
            button(right-66,footer,66,"Done",true,this::onClose);
        }
        if(model.preview && !model.busy() && displayed!=Updates.State.OFFER)
            button(left+(displayed==Updates.State.DONE?0:78),footer,90,"Test local build",false,model::fixture);
    }

    private void addNotes(int left,int right,int bottom) {
        var notes=model.release.changes().stream().filter(c->model.developer || !c.type().equals("Refactor")).toList();
        int start=y+66;
        int available=bottom-start;
        int rows=Math.max(1,available/33);
        if(notes.size()>rows)rows=Math.max(1,(available-19)/33);
        pages=Math.max(1,(notes.size()+rows-1)/rows);
        page=Math.clamp(page,0,pages-1);
        for(int i=page*rows;i<Math.min(notes.size(),(page+1)*rows);i++) {
            var entry=notes.get(i);
            var row=new Control(left,start+(i%rows)*33,right-left,32,entry.summary(),false,()->{},entry);
            controls.add(row);addWidget(row);
        }
        if(pages>1) {
            button(left,bottom-18,21,"‹",false,()->{page=Math.max(0,page-1);layout();});
            button(left+53,bottom-18,21,"›",false,()->{page=Math.min(pages-1,page+1);layout();});
        }
    }

    @Override public boolean mouseScrolled(double mx,double my,double sx,double sy) {
        if(!confirm && displayed==Updates.State.OFFER && pages>1 && sy!=0){
            page=Math.clamp(page+(sy<0?1:-1),0,pages-1);layout();return true;
        }return super.mouseScrolled(mx,my,sx,sy);
    }

    private void text(GuiGraphicsExtractor g,String s,int px,int py,int color) {
        g.text(font,label(s),px,py,color,false);
    }
    private Component label(String text){return Component.literal(text).withStyle(st->st.withFont(face).withoutShadow());}
    private int measure(String text){return font.width(label(text));}
    private String trim(String s,int pixels) {
        if(measure(s)<=pixels)return s;
        int end=s.length();
        while(end>0 && measure(s.substring(0,end)+"…")>pixels)end=s.offsetByCodePoints(end,-1);
        return s.substring(0,end)+"…";
    }

    private void paragraph(GuiGraphicsExtractor g,String s,int px,int py,int width,int color) {
        for(var line:font.split(label(s),width)){
            g.text(font,line,px,py,color,false);py+=12;
        }
    }
    // Three small isometric terraces repeat the silhouette of a Skyblock island.
    private void mark(GuiGraphicsExtractor g,int px,int py) {
        for(int layer=2;layer>=0;layer--)for(int i=-7;i<=7;i++){
            int inset=Math.abs(i)*2;
            g.fill(px+inset,py+i+layer*5,px+32-inset,py+i+layer*5+1,
                    layer==0?MINT:layer==1?0xff56aa97:0xff36746f);
        }
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float dt){}
    @Override public boolean isPauseScreen(){return false;}

    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
        g.fillGradient(0,0,width,height,0xff101926,0xff090e18);
        g.fill(x+2,y+4,x+w+2,y+h+4,0x65000000);
        g.fill(x,y,x+w,y+h,LINE);g.fill(x+1,y+1,x+w-1,y+h-1,PANEL);
        g.fillGradient(x+1,y+1,x+w-1,y+55,0xff244137,0xff192c32);
        g.fill(x+1,y+54,x+w-1,y+55,0xff35554f);
        mark(g,x+13,y+22);
        int tx=x+56;
        text(g,confirm?"INSTALLATION":model.release!=null?model.channel():model.account==null?"SKYBLOCKFLOW":model.account.name().toUpperCase(Locale.ROOT),tx,y+9,MINT);
        String head=confirm?"Confirm download":model.release==null?"Mod updates":model.release.title();
        g.pose().pushMatrix();g.pose().translate(tx,y+24);g.pose().scale(1.25f,1.25f);
        text(g,trim(head,(int)((w-99)/1.25)),0,0,INK);g.pose().popMatrix();
        text(g,model.preview?"Isolated test instance":model.installed.isBlank()?"Release notes":"Installed  "+model.installed,tx,y+41,MUTED);
        int left=x+15,b=y+72;
        if(confirm) {
            text(g,"Install this release?",left,b,INK);
            paragraph(g,"Mods run code on your computer. Only continue if you trust the publisher and this release.",left,b+18,w-30,MUTED);
            int meta=b+(w<360?58:50);
            text(g,model.release.jar().getScheme().equals("file")?"Source   Local developer fixture":"Source   GitHub / "+(model.account==null?Release.REPO:model.account.repository()),left,meta,MINT);
            String path=model.destination();
            text(g,"Destination",left,meta+18,MUTED);
            String shown=path;
            while(shown.length()>1 && measure("…"+shown)>w-30)shown=shown.substring(1);
            text(g,shown.equals(path)?path:"…"+shown,left,meta+30,INK);
            if(my>=meta+17 && my<=meta+44)g.setTooltipForNextFrame(Component.literal(path),mx,my);
        }else if(displayed==Updates.State.OFFER) {
            if(pages>1)text(g,(page+1)+"/"+pages,left+23,y+h-70,MUTED);
            if(model.release.changes().stream().noneMatch(c->model.developer || !c.type().equals("Refactor")))
                paragraph(g,"This release contains internal changes. Enable Developer mode to see them.",left,b,w-30,MUTED);
        }else if(displayed==Updates.State.DOWNLOADING || displayed==Updates.State.INSTALLING) {
            boolean saving=displayed==Updates.State.INSTALLING;
            text(g,saving?"Installing verified release":"Downloading release",left,b,INK);
            int bar=w-30;
            g.fill(left,b+24,left+bar,b+28,LINE);
            int progress=(int)Math.min(bar,model.received*bar/Math.max(1,model.release.bytes()));
            g.fill(left,b+24,left+progress,b+28,MINT);
            text(g,String.format(Locale.ROOT,"%.1f / %.1f MB",model.received/1e6,model.release.bytes()/1e6),left,b+36,MUTED);
        }else {
            paragraph(g,switch(displayed){
                case IDLE,CHECKING -> "Checking for releases…";
                case EMPTY -> "You're up to date.";
                default -> model.message;
            },left,b,w-30,displayed==Updates.State.ERROR?GOLD:INK);
        }
        if(!confirm && displayed==Updates.State.OFFER && !warning.isBlank())
            g.setTooltipForNextFrame(Component.literal(warning),mx,my);
        g.fill(x+1,y+h-33,x+w-1,y+h-32,LINE);
        for(var control:controls)control.extractRenderState(g,mx,my,dt);
    }

    private final class Control extends AbstractWidget {
        final Runnable action;
        final boolean primary;
        final Release.Change note;
        Control(int x,int y,int w,int h,String label,boolean primary,Runnable action,Release.Change note){
            super(x,y,w,h,Component.literal(label));this.primary=primary;this.action=action;this.note=note;
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float dt) {
            int x=getX(),y=getY(),w=getWidth(),h=getHeight();
            boolean hot=isHoveredOrFocused();
            if(note!=null) {
                if(hot)g.fill(x,y,x+w,y+h,0xff223243);
                int tint=switch(note.type()){case "Feature"->MINT;case "Fix"->0xffffad9d;case "Improvement"->0xffa9c8ff;case "Performance"->GOLD;default->0xffcbb5ea;};
                g.fill(x+2,y+7,x+5,y+10,MUTED);
                text(g,note.type(),x+11,y+4,tint);
                if(model.developer)text(g,trim(note.author(),w/3),x+w-measure(trim(note.author(),w/3))-3,y+4,MUTED);
                var lines=font.split(label(note.summary()),w-16);
                for(int i=0;i<Math.min(2,lines.size());i++)g.text(font,lines.get(i),x+11,y+15+i*9,INK,false);
                if(hot)g.setTooltipForNextFrame(Component.literal(note.detail().isEmpty()?note.summary():note.detail()),mx,my);
                return;
            }
            int bg=primary?(hot?0xffb6f4dc:MINT):(hot?0xff344757:0xff243241);
            if(!getMessage().getString().startsWith("☑ ") && !getMessage().getString().startsWith("☐ ")){
            g.fill(x+1,y,x+w-1,y+h,hot&&!primary?MINT:primary?bg:LINE);
            g.fill(x,y+1,x+w,y+h-1,bg);
            }else if(hot)g.fill(x,y,x+w,y+h,bg);
            String label=getMessage().getString();
            if(label.startsWith("☑ ") || label.startsWith("☐ ")){
                boolean checked=label.startsWith("☑");
                g.fill(x+5,y+5,x+14,y+14,checked?MINT:MUTED);
                g.fill(x+6,y+6,x+13,y+13,checked?MINT:bg);
                if(checked){g.fill(x+7,y+9,x+9,y+11,PANEL);g.fill(x+9,y+8,x+11,y+10,PANEL);g.fill(x+11,y+7,x+12,y+9,PANEL);}
                text(g,trim(label.substring(2),w-20),x+17,y+5,INK);
            }else{
                String value=trim(label,w-4);
                text(g,value,x+(w-measure(value))/2,y+5,primary?0xff102a25:INK);
            }
        }
        @Override public void onClick(MouseButtonEvent event,boolean twice){action.run();}
        @Override public boolean keyPressed(KeyEvent e){
            if(e.key()==GLFW.GLFW_KEY_ENTER || e.key()==GLFW.GLFW_KEY_SPACE){action.run();return true;}
            return super.keyPressed(e);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput out){defaultButtonNarrationText(out);}
    }
}
