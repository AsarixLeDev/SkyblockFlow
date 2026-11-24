package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.InspectedProfile.*;
import dev.asarix.skyblockflow.ui.PlayerViewScreen;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;

/** Reads one player-authorized server menu. Presence changes never send commands. */
public final class PlayerInspections implements AutoCloseable {
  private final SkyblockFlowClient app;
  private final ExecutorService disk=Executors.newSingleThreadExecutor(r->{
    var t=new Thread(r,"SkyblockFlow profile cache"); t.setDaemon(true); return t;
  });
  private final Map<String,Sheet> sheets=new LinkedHashMap<>(32,.75f,true);
  private final Map<String,Long> attempts=new HashMap<>();
  private final Set<String> loading=new HashSet<>();
  private final Set<String> left=new HashSet<>(), seen=new HashSet<>(), loaded=new HashSet<>(), archived=new HashSet<>();
  private PlayerViewScreen viewer;
  private String target="", status="";
  private boolean requested, force,sendingInspection;
  private long globalNext,deadline,stabilize,scanBy,manualUntil;
  private AbstractContainerScreen<?> background;
  // Install the server's menu model before its inventory packets arrive. The profile screen keeps focus.
  public boolean openBackground(net.minecraft.network.protocol.game.ClientboundOpenScreenPacket packet){
    var mc=Minecraft.getInstance();
    if(deadline==0 || viewer==null || mc.gui.screen()!=viewer || mc.player==null)return false;
    var type=packet.getType();
    if(type!=net.minecraft.world.inventory.MenuType.GENERIC_9x1 && type!=net.minecraft.world.inventory.MenuType.GENERIC_9x2
        && type!=net.minecraft.world.inventory.MenuType.GENERIC_9x3 && type!=net.minecraft.world.inventory.MenuType.GENERIC_9x4
        && type!=net.minecraft.world.inventory.MenuType.GENERIC_9x5 && type!=net.minecraft.world.inventory.MenuType.GENERIC_9x6)return false;
    var inventory=mc.player.getInventory();
    var menu=(net.minecraft.world.inventory.ChestMenu)type.create(packet.getContainerId(),inventory);
    mc.player.containerMenu=menu;
    background=new net.minecraft.client.gui.screens.inventory.ContainerScreen(menu,inventory,packet.getTitle());
    container=null;deadline=System.currentTimeMillis()+10_000;
    return true;
  }
  public boolean keepViewer(net.minecraft.client.gui.screens.Screen current){
    // A server inventory-close packet may precede the open or arrive after the capture.
    if(current==null || current!=viewer)return false;
    background=null;container=null;return true;
  }
  private void release(boolean reveal){
    var menu=background;background=null;deadline=0;
    var mc=Minecraft.getInstance();
    if(menu==null||mc.player==null)return;
    if(reveal && mc.gui.screen()==viewer && mc.player.containerMenu==menu.getMenu()){
      mc.gui.setScreen(menu);return;
    }
    if(mc.player.containerMenu==menu.getMenu()){
      mc.player.closeContainer();
    }
  }
  public void leave(PlayerViewScreen screen){if(viewer==screen){release(false);requested=false;}}
  private Object container;
  private List<Item> lastItems=List.of();
  private long revision=-1;
  private UUID account;
  private final TreeMap<String,String> known=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private boolean namesDirty;
  private long nextSave;
  private void remember(String name){
    if(name!=null && name.equals(known.get(name)))return;
    if(name==null || !name.matches("[A-Za-z0-9_]{1,16}") || known.size()>=20000 && !known.containsKey(name))return;
    if(!name.equals(known.put(name,name)))namesDirty=true;
  }
  public Collection<String> knownNames(){return known.values();}
  private Path nameIndex(UUID id){return path(id,"index").resolveSibling(".known-names");}
  private void bind(UUID id){
    if(id.equals(account))return;
    disconnect();account=id;var mc=Minecraft.getInstance();var dest=nameIndex(id);
    disk.execute(()->{
      var names=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
      try{
        if(Files.isRegularFile(dest))for(String n:Files.readAllLines(dest))names.put(n,n);
        if(Files.isDirectory(dest.getParent()))try(var files=Files.list(dest.getParent())){
          files.map(f->f.getFileName().toString()).filter(n->n.endsWith(".json")).limit(20000)
            .map(n->n.substring(0,n.length()-5)).forEach(n->names.putIfAbsent(n,n));
        }
      }catch(java.io.IOException ignored){}
      mc.execute(()->{if(id.equals(account))for(String n:names.values())if(!known.containsKey(n))remember(n);});
    });
  }
  private void saveNames(){
    if(!namesDirty || account==null)return;
    var dest=nameIndex(account);var batch=List.copyOf(known.values());namesDirty=false;
    disk.execute(()->{
      try{
        Files.createDirectories(dest.getParent());var all=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
        if(Files.isRegularFile(dest))for(String n:Files.readAllLines(dest))all.put(n,n);
        for(String n:batch)all.put(n,n);
        var tmp=dest.resolveSibling(".known-names.tmp");Files.write(tmp,all.values());
        Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING);
      }catch(java.io.IOException ignored){}
    });
  }
  private int tick;
  public PlayerInspections(SkyblockFlowClient app){this.app=app;}
  private String key(String name){return name.toLowerCase(Locale.ROOT);}
  public Sheet sheet(String name){return sheets.get(key(name));}
  public String status(String name){return key(name).equals(target)?status:"";}
  public boolean pending(PlayerViewScreen screen){return viewer==screen && (requested || deadline!=0);}
  public boolean archived(String name){return archived.contains(key(name));}
  public boolean departed(String name){return left.contains(key(name));}
  private Path path(UUID user,String name){
    return app.store.directory().resolve("public-data/s3/inspected").resolve(user.toString()).resolve(name+".json");
  }
  public void view(PlayerViewScreen screen,String name,boolean refresh) {
    var mc=Minecraft.getInstance();
    if(!app.excaliaApi.connected() || !name.matches("[a-zA-Z0-9_]{1,16}"))return;
    UUID id=mc.getUser().getProfileId();
    bind(id);
    String k=key(name);
    if(viewer==screen && (!refresh || requested || deadline!=0))return;
    release(false);viewer=screen;target=k;force=refresh;requested=true;deadline=0;status="Checking saved profile…";
    loadCached(name);
  }
  public void loadCached(String name){
    UUID id=account;String k=key(name);
    if(id==null || !name.matches("[A-Za-z0-9_]{1,16}") || loaded.contains(k) || !loading.add(k))return;
    var mc=Minecraft.getInstance();
    var dest=path(id,k);
    disk.execute(()->{
      Sheet saved=null;
      try {if(Files.isRegularFile(dest) && Files.size(dest)<131072){
        var value=ConfigStore.GSON.fromJson(Files.readString(dest),Sheet.class);
        if(value!=null && name.equalsIgnoreCase(value.name()) && value.facts()!=null && value.at()>0) saved=value;
      }}catch(Exception ignored){}
      Sheet result=saved;
      mc.execute(()->{ if(!id.equals(account))return;
        loading.remove(k);loaded.add(k); if(result!=null && sheets.putIfAbsent(k,result)==null)archived.add(k);
      });
    });
  }
  public boolean sendingInspection(){return sendingInspection;}
  public void commandSent(String command){
    if(!sendingInspection && command.strip().matches("(?i)/?inspecter(?:\\s+[a-z0-9_]{1,16})?")){
      requested=false;release(false);manualUntil=System.currentTimeMillis()+10000;
    }
  }
  public void disconnect(){
    saveNames();known.clear();nextSave=0;background=null;account=null;viewer=null;target="";status="";requested=false;deadline=0;globalNext=manualUntil=0;
    sheets.clear();archived.clear();seen.clear();left.clear();attempts.clear();loading.clear();loaded.clear();container=null;
  }
  public void tick(){
    var mc=Minecraft.getInstance(); if(!app.excaliaApi.connected())return;
    long now=System.currentTimeMillis();
    bind(mc.getUser().getProfileId());
    if(++tick%20==0){
      var names=new HashSet<String>();
      for(var p:mc.getConnection().getOnlinePlayers()){String name=p.getProfile().name();remember(name);names.add(key(name));}
      if(now>=nextSave){saveNames();nextSave=now+60_000;}
      for(var k:seen)if(!names.contains(k) && (sheets.containsKey(k)||k.equals(target)))left.add(k);
      left.removeAll(names); seen.clear();seen.addAll(names);
      attempts.values().removeIf(t->t<=now);
    }
    var current=mc.gui.screen();
    if(requested && current==viewer && loaded.contains(target)){
      requested=false;
      var cached=sheets.get(target);
      long age=cached==null?Long.MAX_VALUE:now-cached.at();
      if(age>=0 && age<(force?60_000:ExcaliaApiClient.MAX_QUOTE_AGE))status="Saved inspection";
      else if(left.contains(target) && !force)status="Player left · showing saved profile";
      else if(now<Math.max(globalNext,attempts.getOrDefault(target,0L)))status="Lookup on cooldown";
      else {
        attempts.put(target,now+60_000);globalNext=now+5_000;deadline=now+20_000;
        status="Waiting for the server profile…";sendingInspection=true;
        try{mc.player.connection.sendCommand("inspecter "+viewer.playerName());}finally{sendingInspection=false;}
      }
    }
    if(requested && current!=viewer)requested=false;
    if(deadline!=0 && (now>deadline || current==null || current!=viewer && !(current instanceof AbstractContainerScreen<?>))){
      release(false); status="Live inspection unavailable";
    }
    var menu=background!=null && current==viewer?background:current instanceof AbstractContainerScreen<?> visible?visible:null;
    if(menu==null){container=null;return;}
    if(deadline==0 && now>manualUntil && !JobXp.normalize(menu.getTitle().getString()).contains("profil"))return;
    if(container!=menu || revision!=app.inventoryRevision){
      if(container!=menu){lastItems=List.of();scanBy=now+650;}
      else if(stabilize==0)scanBy=now+650;
      container=menu;revision=app.inventoryRevision;stabilize=Math.min(now+180,scanBy);
    }
    if(stabilize==0 || now<stabilize)return;
    stabilize=0;
    var items=new ArrayList<Item>();
    for(var slot:menu.getMenu().slots){
      var stack=slot.getItem();if(slot.container==mc.player.getInventory() || stack.isEmpty())continue;
      var lore=stack.get(DataComponents.LORE);if(lore==null || lore.lines().isEmpty())continue;
      items.add(new Item(stack.getHoverName().getString(),lore.lines().stream().limit(80).map(c->c.getString()).toList()));
    }
    if(background!=null && items.isEmpty() && menu.getTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents title
        && title.getKey().startsWith("container.") && menu.getMenu().slots.stream().anyMatch(s->s.container!=mc.player.getInventory() && !s.getItem().isEmpty())){
      status="Another inventory opened";release(true);return;
    }
    if(items.equals(lastItems))return;
    lastItems=List.copyOf(items);
    var sheet=InspectedProfile.read(items,now);
    if(sheet==null)return;
    String k=key(sheet.name());
    if(background!=null && !target.equals(k)){
      status="Server opened another profile";release(true);return;
    }
    bind(mc.getUser().getProfileId());remember(sheet.name());
    var previous=sheets.get(k);
    if(previous!=null){
      var merged=new LinkedHashMap<>(previous.facts());merged.putAll(sheet.facts());
      sheet=new Sheet(sheet.name(),now,Map.copyOf(merged));
    }
    sheets.put(k,sheet);loaded.add(k);left.remove(k);archived.remove(k);
    var dest=path(account,k);var snapshot=sheet;
    disk.execute(()->persist(dest,snapshot));
    if(deadline!=0 && target.equals(k) && viewer!=null){
      status="Inspection updated";
      if(background!=null)release(false);
      else{deadline=0;mc.player.closeContainer();mc.gui.setScreen(viewer);}
    }
  }
  private static void persist(Path dest,Sheet sheet){
    Path temp=null;
    try {
      Files.createDirectories(dest.getParent());temp=Files.createTempFile(dest.getParent(),"profile-",".tmp");
      Files.writeString(temp,ConfigStore.GSON.toJson(sheet));
      try{Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
      catch(AtomicMoveNotSupportedException e){Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING);}
    } catch(java.io.IOException e){System.getLogger("SkyblockFlow").log(System.Logger.Level.WARNING,"Could not save inspected profile",e);}
    finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(java.io.IOException ignored){}}
  }
  @Override public void close(){saveNames();disk.shutdown();}
}
