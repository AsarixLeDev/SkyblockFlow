package dev.asarix.flow.update;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class Updates {
    enum State { IDLE, CHECKING, OFFER, EMPTY, DOWNLOADING, INSTALLING, DONE, ERROR }
    static Updates instance;
    final String host;
    final AccountFeed account;
    final Path dir;
    final boolean preview;
    private final Properties preferences = new Properties();
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Flow release check").factory());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    volatile State state = State.IDLE;
    volatile Release release;
    volatile String message="", installed="";
    volatile long received;
    volatile boolean cancelled, beta, developer;
    private volatile boolean launchOffer;
    private boolean started;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("FlowUpdates");
    private boolean local;
    private Path target;
    private volatile String targetError="";
    private volatile InputStream activeStream;

    Updates(String host) {
        this(host,null);
    }

    Updates(String host, AccountFeed account) {
        this.account=account;
        this.host = host;
        preview = account==null && !host.equals(UpdateInstall.MOD);
        dir = FabricLoader.getInstance().getConfigDir().resolve(host).resolve(account==null?"updater":"private-updater").toAbsolutePath();
        try {
            Files.createDirectories(dir);
            if (Files.exists(dir.resolve("preferences"))) try (var in=Files.newInputStream(dir.resolve("preferences"))) { preferences.load(in); }
        } catch(IOException ignored) {}
        beta = Boolean.parseBoolean(preferences.getProperty("suggestBeta","true"));
        developer = Boolean.parseBoolean(preferences.getProperty("developer","false"));
    }

    public static void register(String host) {
        instance = new Updates(host);
        instance.listen();
    }

    void listen() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            this.started=true;
            this.check(true);
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    public static void open(Screen parent) {
        instance.show(parent);
    }

    void show(Screen parent) {
        var u=this;
        if (u.state==State.IDLE || u.state==State.EMPTY || u.state==State.ERROR) u.check(false);
        Minecraft.getInstance().gui.setScreen(new UpdateScreen(parent,u));
    }

    void tick(Minecraft mc) {
        if (started && !launchOffer) return;
        var s=mc.gui.screen();
        if (mc.level!=null || s==null) return;
        String cls=s.getClass().getName().toLowerCase(Locale.ROOT);
        boolean lunarMenu=cls.contains("lunar") && (cls.contains("mainmenu") || cls.contains("titlescreen")
                || cls.startsWith("com.moonsworth.lunar.") && mc.getConnection()==null && s.getTitle().getString().equals("ScreenInjector")
                || s.getTitle().getString().equalsIgnoreCase("main menu") || s.getTitle().getString().equalsIgnoreCase("title screen"));
        if (!(s instanceof TitleScreen) && !lunarMenu) return;
        if (!started) { started=true; if(state==State.IDLE)check(true); }
        if (launchOffer && (state==State.OFFER || state==State.ERROR && account!=null)) {
            launchOffer=false;
            mc.gui.setScreen(new UpdateScreen(s,this));
        }
    }

    void preference(boolean changeBeta) {
        if (changeBeta) beta=!beta; else developer=!developer;
        preferences.setProperty("suggestBeta",Boolean.toString(beta));
        preferences.setProperty("developer",Boolean.toString(developer));
        try (var out=Files.newOutputStream(dir.resolve("preferences"))) { preferences.store(out,null); }
        catch(IOException e) { message="Could not save update preferences."; }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent","SkyblockFlow-Updater");
    }

    private String installed() throws IOException {
        if (!preview) return FabricLoader.getInstance().getModContainer(host).orElseThrow().getMetadata().getVersion().getFriendlyString();
        Path test=UpdateInstall.active(host,dir);
        return Files.exists(test) ? UpdateInstall.metadata(test).get("version").getAsString() : "0.0.0";
    }

    synchronized void check(boolean launch) {
        if (busy() || state==State.DONE) return;
        state=State.CHECKING; message=""; release=null; local=false; launchOffer=false;
        io.execute(() -> {
            try {
                installed=installed();
                if(launch && preview && beta && Files.isRegularFile(dir.resolve("test-release/skyblockflow.jar"))) {
                    var fixture=readFixture();
                    if(!installed.equals(fixture.version())) {local=true;offer(fixture,true);return;}
                }
                List<Release> options=new ArrayList<>();
                if(account!=null) options.addAll(account.releases(beta));
                else for (int page=1;page<=10;page++) {
                    JsonArray items=feed(page);
                    for (var obj:items) {
                        var parsed=Release.parse(obj.getAsJsonObject());
                        parsed.filter(r -> beta || !r.beta()).ifPresent(options::add);
                    }
                    if (!options.isEmpty() || items.size()<100) break;
                }
                var available=options.stream().filter(r -> Release.compare(r.version(),installed)>0 ||
                        installed.endsWith("-dev") && !r.version().equals(installed))
                        .max((a,b)->Release.compare(a.version(),b.version()));
                if (available.isEmpty() && preview && beta && Files.isRegularFile(dir.resolve("test-release/skyblockflow.jar"))) {
                    var fixture=readFixture();
                    if (!installed.equals(fixture.version())) { local=true; offer(fixture,launch); return; }
                }
                if (available.isEmpty()) { message="You're up to date."; state=State.EMPTY; return; }
                var item=available.get();
                if (item.details()!=null) try {
                    if(account!=null) {
                        try(var in=account.asset(item.details())) {
                            byte[] data=in.readNBytes(1048577);
                            if(data.length<=1048576)item=item.detailed(new String(data,java.nio.charset.StandardCharsets.UTF_8));
                        }
                    } else {
                    var res=http.send(request(item.details()).GET().build(), HttpResponse.BodyHandlers.ofString());
                    if(res.statusCode()==200 && res.body().length()<=1024*1024) item=item.detailed(res.body());
                    }
                } catch(IOException ignored) {}
                offer(item,launch);
            } catch(Exception e) { launchOffer=launch && account!=null; fail(e); }
        });
    }

    private JsonArray feed(int page) throws Exception {
        var cached=dir.resolve("releases-"+page+".json");
        if (Files.isRegularFile(cached) && System.currentTimeMillis()-Files.getLastModifiedTime(cached).toMillis()<300000)
            return JsonParser.parseString(Files.readString(cached)).getAsJsonArray();
        var req=request(URI.create("https://api.github.com/repos/"+Release.REPO+"/releases?per_page=100&page="+page))
                .header("Accept","application/vnd.github+json");
        Path etag=dir.resolve("etag-"+page);
        if(Files.isRegularFile(etag) && Files.isRegularFile(cached)) req.header("If-None-Match",Files.readString(etag));
        var res=http.send(req.GET().build(),HttpResponse.BodyHandlers.ofString());
        if(res.statusCode()==304) {
            Files.setLastModifiedTime(cached,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return JsonParser.parseString(Files.readString(cached)).getAsJsonArray();
        }
        if(res.statusCode()!=200) throw new IOException(res.statusCode()==403 || res.statusCode()==429 ?
                "GitHub is rate-limiting requests. Try again later." : "GitHub returned HTTP "+res.statusCode()+".");
        var items=JsonParser.parseString(res.body()).getAsJsonArray();
        Files.writeString(cached,res.body());
        Files.writeString(etag,res.headers().firstValue("ETag").orElse(""));
        return items;
    }

    synchronized void fixture() {
        if(!preview || busy())return;
        launchOffer=false; state=State.CHECKING; release=null;
        io.execute(()->{
            try {
                installed=installed();local=true;
                offer(readFixture(),false);
            }catch(Exception e){fail(e);}
        });
    }

    private Release readFixture() throws IOException {
        var build=dir.resolve("test-release");
        Path jar=build.resolve("skyblockflow.jar");
        if(!Files.isRegularFile(jar))throw new IOException("Place the test build in the updater test-release folder.");
        String version=UpdateInstall.metadata(jar).get("version").getAsString();
        var item=new Release(version,true,"SkyblockFlow "+version,Release.notes(Files.readString(build.resolve("RELEASE_NOTES.md"))),
                jar.toUri(),UpdateSwap.hash(jar),Files.size(jar),null);
        return item.detailed(Files.readString(build.resolve("CHANGE_DETAILS.txt")));
    }

    private void offer(Release item,boolean popup) {
        target=null;targetError="";
        try {target=UpdateInstall.active(host,dir,preview);} catch(IOException e){targetError=e.getMessage();}
        release=item;launchOffer=popup;message="";state=State.OFFER;
        if(preview)LOG.info("Update preview {} ready (startup={})",item.version(),popup);
    }

    boolean busy(){return state==State.CHECKING || state==State.DOWNLOADING || state==State.INSTALLING;}
    String destination() {return target==null?"":target.toString();}
    String channel() {return local ? "LOCAL PREVIEW" : release!=null && release.beta() ? "BETA RELEASE" : "PUBLIC RELEASE";}

    synchronized void prepare() throws IOException {
        if (state!=State.OFFER || release==null) throw new IOException("Check for updates again.");
        if(target==null)throw new IOException(targetError);
    }

    synchronized void cancel() {
        if(state!=State.DOWNLOADING)return;
        cancelled=true;
        var stream=activeStream;
        if(stream!=null)Thread.startVirtualThread(()->{try{stream.close();}catch(IOException ignored){}});
    }

    synchronized void download() {
        if(state!=State.OFFER || release==null || target==null)return;
        var selected=release;
        final Path active=target;
        received=0;cancelled=false;state=State.DOWNLOADING;
        io.execute(()->{
            Path temp=null;
            try {
                String old=Files.exists(active)?UpdateSwap.hash(active):"";
                temp=Files.createTempFile(active.getParent(),".skyblockflow-",".pending");
                if(local && preview) {
                    try(var in=Files.newInputStream(Path.of(selected.jar()))) {transfer(in,temp,selected.bytes());}
                } else if(account!=null) {
                    try(var in=account.asset(selected.jar())){transfer(in,temp,selected.bytes());}
                } else {
                    var res=http.send(request(selected.jar()).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                    try(var in=res.body()) {
                        if(res.statusCode()!=200)throw new IOException("Download returned HTTP "+res.statusCode()+".");
                        transfer(in,temp,selected.bytes());
                    }
                }
                UpdateInstall.verify(temp,selected,preview?UpdateInstall.MOD:host);
                synchronized(this){if(cancelled)throw new CancellationException();state=State.INSTALLING;}
                boolean immediate=UpdateInstall.install(temp,active,old,selected.digest(),dir);
                temp=null;
                message=preview?"Test build installed in the isolated instance.":
                    immediate?"Mod installed. Restart your game.":"Update ready. Close Minecraft to finish installing.";
                state=State.DONE;
            }catch(CancellationException e){message="Download cancelled.";state=State.OFFER;}
            catch(Exception e){if(cancelled){message="Download cancelled.";state=State.OFFER;}else fail(e);}
            finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
        });
    }

    private void transfer(InputStream in,Path dest,long limit)throws IOException {
        activeStream=in;
        try(var out=Files.newOutputStream(dest)) {
            byte[] buf=new byte[32768];
            for(int n;(n=in.read(buf))!=-1;) {
                if(cancelled)throw new CancellationException();
                if(received+n>limit)throw new IOException("Download exceeds advertised size.");
                out.write(buf,0,n);received+=n;
            }
        } finally {activeStream=null;}
    }

    private void fail(Exception e) {
        message=e.getMessage()==null || e.getMessage().isBlank()?"Couldn't reach the update service. Try again.":e.getMessage();
        LOG.debug("Update check or install failed",e);
        state=State.ERROR;
    }
}
