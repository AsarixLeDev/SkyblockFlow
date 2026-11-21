package dev.asarix.skyblockflow;

import com.google.gson.*;
import dev.asarix.skyblockflow.core.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Reads the local Discord client's voice events after per-player OAuth authorization. */
public final class DiscordVoice implements AutoCloseable {
  record Member(String id, String name, String avatar, boolean speaking, boolean muted) {}
  record Call(String channel, List<Member> members, boolean muted, boolean deafened) {}
  interface Connector { Link open() throws IOException; }
  private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon().name("Discord connection timeout").factory());
  public final DiscordAccount account;
  private final boolean available;
  private final Connector endpoint;
  volatile Call call;
  private volatile String status = "not identified", help = "", connectionToken = "";
  private volatile boolean authenticated;
  private final Map<String, Member> members = new LinkedHashMap<>();
  private final Set<String> speaking = new HashSet<>();
  private volatile Link link;
  private Thread worker;
  private long retry, epoch, observedRevision = -1, sequence;
  private String channel = "", self = "";
  private boolean muted, deafened;

  public DiscordVoice() { this(new DiscordAccount(application()), Link::open, publicConnectionEnabled()); }
  private static boolean publicConnectionEnabled() {
    var properties = new Properties();
    try (var stream = DiscordVoice.class.getResourceAsStream("/discord-public.properties")) {
      if (stream != null) properties.load(stream);
      return "true".equals(properties.getProperty("enabled", "false"));
    } catch (IOException ignored) { return false; }
  }
  DiscordVoice(DiscordAccount account, Connector endpoint) {
    this(account, endpoint, true);
  }
  private DiscordVoice(DiscordAccount account, Connector endpoint, boolean available) {
    this.available = available;
    this.account = account;
    this.endpoint = endpoint;
  }
  private static DiscordOAuth.App application() {
    var properties = new Properties();
    try (var stream = DiscordVoice.class.getResourceAsStream("/discord-public.properties")) {
      if (stream != null) properties.load(stream);
      return new DiscordOAuth.App(properties.getProperty("clientId", ""),
          URI.create(properties.getProperty("redirectUri", "http://127.0.0.1:48533/callback")));
    } catch (Exception ignored) {
      return new DiscordOAuth.App("", URI.create("http://127.0.0.1:48533/callback"));
    }
  }
  public boolean available() { return available; }
  public void load(Path directory) { if (available) account.load(directory); }
  public boolean inCall() { return call != null; }
  public boolean configured() { return account.linked(); }
  public boolean canControl() { return authenticated && account.controls(); }
  public String status() {
    if (!available) return "Not available in this release";
    return account.busy() || !account.linked() ? account.view().status() : status;
  }
  public String help() {
    if (!available) return "The Discord overlay is waiting for public access verification. It cannot connect in this release. You do not need to create a Discord application.";
    return account.busy() || !account.linked() || help.isBlank() ? account.view().help() : help;
  }
  public void connect(boolean controls, Consumer<URI> browser) {
    if (!available) return;
    closeLink();
    help = "";
    account.connect(controls, browser);
  }
  public void disconnect() {
    closeLink();
    account.disconnect();
  }

  synchronized void tick(boolean enabled) {
    if (!available) return;
    account.tick();
    long revision = account.revision();
    if (!enabled || account.access().isBlank() || revision != observedRevision) {
      if (link != null || authenticated || worker != null && worker.isAlive()) closeLink();
      if (!enabled || account.access().isBlank()) return;
      observedRevision = revision;
      retry = 0;
    }
    if (worker != null && worker.isAlive() || System.nanoTime() < retry) return;
    retry = System.nanoTime() + 30_000_000_000L;
    long attempt = ++epoch;
    String token = account.access();
    status = "Connecting…";
    help = "Connecting to the Discord desktop app on this computer.";
    worker = Thread.ofPlatform().daemon().name("SkyblockFlow Discord events").start(() -> listen(attempt, token));
  }

  private synchronized void closeLink() {
    epoch++;
    var previous = link;
    link = null;
    connectionToken = "";
    authenticated = false;
    clearCall();
    if (previous != null) try { previous.close(); } catch (IOException ignored) { }
  }
  private void clearCall() {
    channel = "";
    self = "";
    muted = false;
    deafened = false;
    members.clear();
    speaking.clear();
    call = null;
  }
  @Override public void close() { closeLink(); account.close(); }

  private static JsonObject args(String key, String value) {
    var json = new JsonObject();
    json.addProperty(key, value);
    return json;
  }
  private static String str(JsonObject json, String key) {
    if (json == null) return "";
    var value = json.get(key);
    return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
  }
  private static boolean flag(JsonObject json, String key) {
    return json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
  }
  private synchronized void send(String cmd, String evt, JsonObject args) throws IOException {
    var current = link;
    if (current == null) return;
    var json = new JsonObject();
    json.addProperty("cmd", cmd);
    json.add("args", args);
    json.addProperty("nonce", Long.toString(++sequence));
    if (evt != null) json.addProperty("evt", evt);
    current.send(1, json);
  }

  void toggleMute(boolean output) {
    var snapshot = call;
    if (snapshot == null || !canControl()) return;
    Thread.ofPlatform().daemon().name("SkyblockFlow Discord mute").start(() -> {
      synchronized (this) {
        var current = call;
        if (current == null || !canControl() || !snapshot.channel().equals(current.channel())) return;
        var arguments = new JsonObject();
        arguments.addProperty(output ? "deaf" : "mute", output ? !current.deafened() : !current.muted());
        try { send("SET_VOICE_SETTINGS", null, arguments); }
        catch (IOException ignored) { }
      }
    });
  }

  private void listen(long attempt, String token) {
    ScheduledFuture<?> deadline = null;
    try (var current = endpoint.open()) {
      synchronized (this) {
        if (attempt != epoch) return;
        link = current;
        connectionToken = token;
      }
      deadline = TIMEOUTS.schedule(() -> {
        if (!authenticated) try { current.close(); } catch (IOException ignored) { }
      }, 15, TimeUnit.SECONDS);
      var hello = args("client_id", applicationId());
      hello.addProperty("v", 1);
      current.send(0, hello);
      while (true) {
        var header = ByteBuffer.wrap(current.read(8)).order(ByteOrder.LITTLE_ENDIAN);
        int op = header.getInt(), size = header.getInt();
        if (size < 0 || size > 2_000_000) throw new IOException("Invalid frame");
        var json = JsonParser.parseString(new String(current.read(size), StandardCharsets.UTF_8)).getAsJsonObject();
        synchronized (this) {
          if (attempt != epoch) return;
          if (op == 2) throw new ConnectionFailure("not identified", "Discord closed this authorization. Connect again; this release may still need Discord approval.");
          if (op == 3) current.send(4, json);
          else if (op == 1) event(json);
          if (authenticated && deadline != null) { deadline.cancel(false); deadline = null; }
        }
      }
    } catch (Exception error) {
      synchronized (this) {
        if (attempt != epoch) return;
        if (error instanceof ConnectionFailure failure) {
          if (!(failure instanceof Unavailable)) account.rejected();
          status = failure.status;
          help = failure.getMessage();
        } else {
          status = "Discord unavailable";
          help = "Discord stopped responding. Open the desktop app; reconnection will be retried automatically.";
        }
        SkyblockFlowClient.LOG.info("Discord voice: {}", help);
      }
    } finally {
      if (deadline != null) deadline.cancel(false);
      synchronized (this) {
        if (attempt == epoch) {
          link = null;
          connectionToken = "";
          authenticated = false;
          clearCall();
        }
      }
    }
  }
  private String applicationId() { return account.applicationId(); }

  synchronized void event(JsonObject json) throws IOException {
    String cmd = str(json, "cmd"), evt = str(json, "evt");
    JsonObject d = json.has("data") && json.get("data").isJsonObject()
        ? json.getAsJsonObject("data") : new JsonObject();
    if (evt.equals("ERROR")) {
      if (cmd.equals("SET_VOICE_SETTINGS")) {
        help = "Discord could not change your microphone settings. Check microphone controls in the connection menu.";
        return;
      }
      throw new ConnectionFailure("not identified", "Discord denied voice access. Connect again; if it repeats, contact the mod author.");
    }
    if (cmd.equals("DISPATCH")) {
      switch (evt) {
        case "READY" -> send("AUTHENTICATE", null, args("access_token", connectionToken));
        case "VOICE_CHANNEL_SELECT" -> {
          setChannel("");
          send("GET_SELECTED_VOICE_CHANNEL", null, new JsonObject());
        }
        case "SPEAKING_START", "SPEAKING_STOP" -> {
          String id = str(d, "user_id");
          if (evt.endsWith("START")) speaking.add(id);
          else speaking.remove(id);
          publish();
        }
        case "VOICE_STATE_CREATE", "VOICE_STATE_UPDATE" -> {
          member(d);
          publish();
        }
        case "VOICE_STATE_DELETE" -> {
          if (d.has("user")) {
            String id = str(d.getAsJsonObject("user"), "id");
            members.remove(id);
            speaking.remove(id);
            publish();
          }
        }
        case "VOICE_SETTINGS_UPDATE" -> {
          muted = flag(d, "mute");
          deafened = flag(d, "deaf");
          publish();
        }
      }
    } else
      switch (cmd) {
        case "AUTHENTICATE" -> {
          var granted = new HashSet<String>();
          if (d.has("scopes")) for (var scope : d.getAsJsonArray("scopes")) granted.add(scope.getAsString());
          if (!granted.containsAll(DiscordOAuth.READ_SCOPES))
            throw new ConnectionFailure("not identified", "Discord did not grant voice access. Connect again or contact the mod author.");
          authenticated = true;
          status = "Connected · no call";
          help = "Connected to Discord. Join a voice channel to show its members in the overlay.";
          self = str(d.getAsJsonObject("user"), "id");
          for (String e : List.of("VOICE_CHANNEL_SELECT", "VOICE_SETTINGS_UPDATE"))
            send("SUBSCRIBE", e, new JsonObject());
          send("GET_SELECTED_VOICE_CHANNEL", null, new JsonObject());
          send("GET_VOICE_SETTINGS", null, new JsonObject());
        }
        case "GET_SELECTED_VOICE_CHANNEL" -> {
          setChannel(str(d, "id"));
          var states = d.getAsJsonArray("voice_states");
          if (states != null) for (var v : states) member(v.getAsJsonObject());
          publish();
        }
        case "GET_VOICE_SETTINGS", "SET_VOICE_SETTINGS" -> {
          muted = flag(d, "mute");
          deafened = flag(d, "deaf");
          publish();
        }
      }
  }

  private void setChannel(String id) throws IOException {
    if (channel.equals(id)) return;
    for (String e :
        List.of(
            "VOICE_STATE_CREATE",
            "VOICE_STATE_UPDATE",
            "VOICE_STATE_DELETE",
            "SPEAKING_START",
            "SPEAKING_STOP")) {
      if (!channel.isEmpty()) send("UNSUBSCRIBE", e, args("channel_id", channel));
      if (!id.isEmpty()) send("SUBSCRIBE", e, args("channel_id", id));
    }
    channel = id;
    members.clear();
    speaking.clear();
    publish();
  }

  private void member(JsonObject d) {
    if (!d.has("user")) return;
    var u = d.getAsJsonObject("user");
    String id = str(u, "id");
    if (!id.matches("[0-9]+")) return;
    String name = str(d, "nick");
    if (name.isBlank()) name = str(u, "username");
    var state = d.getAsJsonObject("voice_state");
    members.put(
        id,
        new Member(
            id,
            name,
            str(u, "avatar"),
            false,
            flag(d, "mute") || state != null && (flag(state, "mute") || flag(state, "self_mute"))));
  }

  private void publish() {
    call =
        channel.isEmpty()
            ? null
            : new Call(
                channel,
                members.values().stream()
                    .map(
                        m ->
                            new Member(
                                m.id,
                                m.name,
                                m.avatar,
                                speaking.contains(m.id),
                                m.muted || m.id.equals(self) && muted))
                    .toList(),
                muted,
                deafened);
  }

  private static class ConnectionFailure extends IOException {
    final String status;
    ConnectionFailure(String status, String help) { super(help); this.status = status; }
  }
  private static final class Unavailable extends ConnectionFailure {
    Unavailable() { super("Discord unavailable", "Open the Discord desktop app on this computer. Reconnection will be retried automatically."); }
  }

  record Link(Closeable resource, ReadableByteChannel in, WritableByteChannel out)
      implements Closeable {
    static Link open() throws IOException {
      boolean win = System.getProperty("os.name").startsWith("Windows");
      if (!win) return openUnix(socketDirectories(System.getenv()));
      for (int i = 0; i < 10; i++) {
        try {
          var pipe = DiscordWindowsPipe.open(i);
          return new Link(pipe, pipe, pipe);
        } catch (IOException ignored) { }
      }
      throw new Unavailable();
    }

    static List<Path> socketDirectories(Map<String, String> environment) {
      var roots = new LinkedHashSet<Path>();
      for (String key : List.of("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP")) {
        String value = environment.get(key);
        if (value != null && !value.isBlank()) roots.add(Path.of(value));
      }
      roots.add(Path.of("/tmp"));
      var directories = new LinkedHashSet<Path>();
      for (Path root : roots) {
        directories.add(root);
        directories.add(root.resolve("snap.discord"));
        directories.add(root.resolve("app/com.discordapp.Discord"));
        directories.add(root.resolve("app/com.discordapp.DiscordCanary"));
        directories.add(root.resolve("app/com.discordapp.DiscordPTB"));
      }
      return List.copyOf(directories);
    }

    static Link openUnix(List<Path> directories) throws IOException {
      var expected = FileSystems.getDefault().getUserPrincipalLookupService()
          .lookupPrincipalByName(System.getProperty("user.name"));
      return openUnix(directories, expected);
    }

    static Link openUnix(List<Path> directories, java.nio.file.attribute.UserPrincipal expected) throws IOException {
      for (Path directory : directories)
        for (int i = 0; i < 10; i++) {
          Path p = directory.resolve("discord-ipc-" + i);
          if (!Files.exists(p)) continue;
          var s = SocketChannel.open(StandardProtocolFamily.UNIX);
          try {
            s.connect(UnixDomainSocketAddress.of(p));
            var peer = s.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED);
            if (peer == null || !peer.user().equals(expected)) throw new IOException("Untrusted local Discord peer");
            return new Link(s, s, s);
          } catch (IOException | UnsupportedOperationException e) {
            s.close();
          }
        }
      throw new Unavailable();
    }

    byte[] read(int n) throws IOException {
      var b = ByteBuffer.allocate(n);
      while (b.hasRemaining()) if (in.read(b) < 0) throw new EOFException();
      return b.array();
    }

    synchronized void send(int op, JsonObject o) throws IOException {
      var data = o.toString().getBytes(StandardCharsets.UTF_8);
      var frame = ByteBuffer.allocate(data.length + 8).order(ByteOrder.LITTLE_ENDIAN);
      frame.putInt(op).putInt(data.length).put(data).flip();
      while (frame.hasRemaining()) out.write(frame);
    }

    public void close() throws IOException {
      resource.close();
    }
  }
}
