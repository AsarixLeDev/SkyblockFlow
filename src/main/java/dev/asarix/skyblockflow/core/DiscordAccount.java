package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Serializes authorization changes; late network replies cannot undo a disconnect. */
public final class DiscordAccount implements AutoCloseable {
  public enum Phase { OFF, OPENING, WAITING, LINKED, REFRESHING, REVOKING, REVOKE_PENDING }
  public record View(Phase phase, String status, String help) { }
  private static final String INTRO = "Connect your own Discord account to display your voice call. No application setup or token is needed.";
  private final DiscordOAuth.App app;
  private final DiscordOAuth oauth;
  private final Clock clock;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(
      Thread.ofPlatform().daemon().name("Discord account").factory());
  private volatile View view = new View(Phase.OFF, "not identified", INTRO);
  private DiscordOAuth.Tokens active, pendingRevoke;
  private DiscordOAuth.Login login;
  private Path file;
  private Future<?> task;
  private long generation, revision, retryAt;
  private boolean remember, closed;

  public DiscordAccount(DiscordOAuth.App app) { this(app, new DiscordOAuth(), Clock.systemUTC()); }
  DiscordAccount(DiscordOAuth.App app, DiscordOAuth oauth, Clock clock) {
    this.app = app;
    this.oauth = oauth;
    this.clock = clock;
  }

  public View view() { return view; }
  public String applicationId() { return app.clientId(); }
  public boolean available() { return app.configured(); }
  public synchronized boolean remembered() { return remember; }
  public synchronized boolean controls() { return active != null && active.controls(); }
  public synchronized boolean linked() { return active != null; }
  public synchronized boolean pendingRevocation() { return pendingRevoke != null; }
  public synchronized boolean busy() {
    return switch (view.phase()) {
      case OPENING, WAITING, REFRESHING, REVOKING -> true;
      default -> false;
    };
  }
  public synchronized long revision() { return revision; }
  public synchronized String access() {
    return active != null && active.expiresAt > clock.millis() && !busy() ? active.access : "";
  }

  public synchronized void load(Path directory) {
    active = pendingRevoke = null;
    remember = false;
    retryAt = 0;
    revision++;
    view = new View(Phase.OFF, "not identified", INTRO);
    file = directory.resolve("discord-oauth-private.json");
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) > 65536) throw new IOException();
      JsonObject data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
      if (!data.has("schema") || data.get("schema").getAsInt() != 2
          || !app.clientId().equals(data.get("client_id").getAsString())) {
        Files.delete(file);
        view = new View(Phase.OFF, "not identified", "Connect again to upgrade your Discord link. The guide explains how to remove old authorizations.");
        return;
      }
      protect(file);
      remember = data.has("remember") && data.get("remember").getAsBoolean();
      if (data.has("pending_revoke")) {
        pendingRevoke = decode(data.getAsJsonObject("pending_revoke"));
        view = pendingView();
      } else if (remember && data.has("tokens")) {
        active = decode(data.getAsJsonObject("tokens"));
        view = linkedView();
      }
    } catch (Exception ignored) {
      active = null;
      view = new View(Phase.OFF, "not identified", "The saved Discord connection could not be read. Connect again.");
    }
  }

  public synchronized void remember(boolean value) {
    boolean previous = remember;
    remember = value;
    try { save(); }
    catch (IOException e) {
      remember = previous;
      view = new View(active == null ? Phase.OFF : Phase.LINKED, active == null ? "not identified" : "Discord linked",
          "Could not update saved credentials. Check the configuration folder permissions.");
    }
  }

  public synchronized void connect(boolean controls, Consumer<URI> browser) {
    if (closed || busy() || pendingRevoke != null) return;
    if (!available()) {
      view = new View(Phase.OFF, "Unavailable", "Discord connection is not configured in this build. Please contact the mod author.");
      return;
    }
    long attempt = ++generation;
    view = new View(Phase.OPENING, "Connecting…", "Opening Discord's sign-in page in your browser.");
    task = executor.submit(() -> authorize(attempt, controls, browser));
  }

  private void authorize(long attempt, boolean controls, Consumer<URI> browser) {
    DiscordOAuth.Tokens received = null;
    try (var flow = oauth.login(app, controls)) {
      synchronized (this) {
        if (attempt != generation || closed || pendingRevoke != null) return;
        login = flow;
        view = new View(Phase.WAITING, "Approve in browser", "Approve the Discord sign-in page, then return here. This request expires in five minutes.");
      }
      browser.accept(flow.authorization());
      received = oauth.exchange(app, flow, flow.await(Duration.ofMinutes(5)));
      synchronized (this) {
        if (attempt != generation || closed) return;
        active = received;
        revision++;
        try { save(); }
        catch (IOException e) { active = null; throw e; }
        view = linkedView();
        received = null;
      }
    } catch (Exception e) {
      synchronized (this) {
        if (attempt == generation && !closed) {
          view = new View(active == null ? Phase.OFF : Phase.LINKED, active == null ? "not identified" : "Discord linked",
              e instanceof DiscordOAuth.Failure failure ? failure.getMessage()
                  : "Discord sign-in could not finish. Check your connection and try again.");
        }
      }
    } finally {
      synchronized (this) { if (attempt == generation) login = null; }
      if (received != null) discard(received);

    }
  }

  private void discard(DiscordOAuth.Tokens tokens) {
    // A cancelled exchange can still issue a grant. Never lose it if revocation is offline.
    synchronized (this) {
      generation++;
      active = null;
      revision++;
      pendingRevoke = tokens;
      view = new View(Phase.REVOKING, "Cancelling…", "Removing the cancelled Discord authorization.");
      try { save(); } catch (IOException e) { storageFailure(); }
    }
    try {
      oauth.revoke(app, tokens);
      synchronized (this) {
        pendingRevoke = null;
        try { save(); } catch (IOException e) { pendingRevoke = tokens; throw e; }
        view = new View(Phase.OFF, "not identified", "Sign-in cancelled and Discord authorization removed.");
      }
    } catch (Exception e) {
      synchronized (this) { retryAt = clock.millis() + 60_000; view = pendingView(); }
    }
  }

  public synchronized void reopen(Consumer<URI> browser) {
    if (login != null && view.phase() == Phase.WAITING) browser.accept(login.authorization());
  }

  public synchronized void cancel() {
    if (view.phase() != Phase.OPENING && view.phase() != Phase.WAITING) return;
    generation++;
    if (login != null) login.close();
    login = null;
    // Leave the exchange task running so any late-issued token can be revoked.
    view = active == null ? new View(Phase.OFF, "not identified", "Sign-in cancelled. You can connect whenever you are ready.") : linkedView();
  }

  public synchronized void disconnect() {
    if (closed || view.phase() == Phase.REVOKING) return;
    generation++;
    if (login != null) login.close();
    login = null;
    if (active != null) pendingRevoke = active;
    active = null;
    revision++;
    if (pendingRevoke == null) {
      view = new View(Phase.OFF, "not identified", INTRO);
      try { save(); } catch (IOException e) { storageFailure(); }
      return;
    }
    try { save(); }
    catch (IOException e) {
      storageFailure();
      return;
    }
    revoke();
  }

  public synchronized void retryRevocation() {
    if (!closed && pendingRevoke != null && !busy()) revoke();
  }

  private void revoke() {
    long attempt = ++generation;
    view = new View(Phase.REVOKING, "Disconnecting…", "Removing this app's Discord authorization. This also disconnects the other Flow mod when it uses the same Discord app.");
    task = executor.submit(() -> {
      try {
        DiscordOAuth.Tokens tokens;
        synchronized (this) { tokens = pendingRevoke; }
        if (tokens == null) return;
        oauth.revoke(app, tokens);
        synchronized (this) {
          if (attempt != generation) return;
          pendingRevoke = null;
          try { save(); }
          catch (IOException e) { pendingRevoke = tokens; throw e; }
          view = new View(Phase.OFF, "not identified", "Disconnected. Discord authorization and local credentials have been removed.");
        }
      } catch (Exception e) {
        synchronized (this) {
          if (attempt != generation) return;
          retryAt = clock.millis() + 60_000;
          view = pendingView();
        }
      }
    });
  }

  public synchronized void tick() {
    if (closed || busy() || clock.millis() < retryAt) return;
    if (pendingRevoke != null) { revoke(); return; }
    if (active == null || active.expiresAt - clock.millis() > 120_000) return;
    long attempt = ++generation;
    var previous = active;
    view = new View(Phase.REFRESHING, "Reconnecting…", "Renewing your Discord connection.");
    task = executor.submit(() -> {
      DiscordOAuth.Tokens received = null;
      try {
        received = oauth.refresh(app, previous);
        synchronized (this) {
          if (attempt != generation || closed) return;
          active = received;
          revision++;
          save();
          view = linkedView();
          received = null;
        }
      } catch (Exception e) {
        synchronized (this) {
          if (attempt != generation || closed) return;
          boolean permanent = e instanceof DiscordOAuth.Failure f && f.permanent;
          if (permanent) { active = null; revision++; }
          retryAt = clock.millis() + 60_000;
          view = new View(active == null ? Phase.OFF : Phase.LINKED, active == null ? "not identified" : "Reconnecting…",
              permanent ? "Your Discord connection expired or was removed. Connect again."
                  : "Discord is temporarily unavailable. Reconnection will be retried automatically.");
          try { save(); } catch (IOException ignored) { }
        }
      } finally {
        if (received != null) {
          // Disconnect may have raced a rotating refresh token. Preserve the latest token for revocation.
          synchronized (this) {
            if (active == null && pendingRevoke != null) {
              pendingRevoke = received;
              try { save(); } catch (IOException ignored) { }
            }
          }
        }
      }
    });
  }

  public synchronized void rejected() {
    // A revoked grant must never cause a reconnect loop or reopen a browser automatically.
    generation++;
    if (active != null) pendingRevoke = active;
    active = null;
    revision++;
    view = new View(pendingRevoke == null ? Phase.OFF : Phase.REVOKE_PENDING, "not identified",
        "Discord rejected this connection. Its authorization will be removed before you reconnect; this release may still need Discord approval.");
    try { save(); } catch (IOException e) { storageFailure(); }
  }

  private View linkedView() {
    return new View(Phase.LINKED, "Discord linked", "Your account is linked. Open Discord and join a voice channel to display its members.");
  }
  private View pendingView() {
    return new View(Phase.REVOKE_PENDING, "Disconnected locally", "Discord could not confirm removal yet. We will retry automatically. You can also remove this app in Discord's Authorized Apps.");
  }
  private void storageFailure() {
    view = new View(pendingRevoke != null ? Phase.REVOKE_PENDING : Phase.OFF, "Check connection", "Could not update local credentials. Check folder permissions and remove this app in Discord's Authorized Apps.");
    retryAt = clock.millis() + 60_000;
  }

  private static DiscordOAuth.Tokens decode(JsonObject json) {
    var scopes = new HashSet<String>();
    for (var scope : json.getAsJsonArray("scopes")) scopes.add(scope.getAsString());
    return new DiscordOAuth.Tokens(json.get("access").getAsString(), json.get("refresh").getAsString(), json.get("expires_at").getAsLong(), scopes);
  }
  private static JsonObject encode(DiscordOAuth.Tokens tokens) {
    var json = new JsonObject();
    json.addProperty("access", tokens.access);
    json.addProperty("refresh", tokens.refresh);
    json.addProperty("expires_at", tokens.expiresAt);
    var scopes = new JsonArray();
    tokens.scopes.stream().sorted().forEach(scopes::add);
    json.add("scopes", scopes);
    return json;
  }

  private void save() throws IOException {
    if (file == null) return;
    if (pendingRevoke == null && (!remember || active == null)) { Files.deleteIfExists(file); return; }
    var json = new JsonObject();
    json.addProperty("schema", 2);
    json.addProperty("client_id", app.clientId());
    json.addProperty("remember", remember);
    if (pendingRevoke != null) json.add("pending_revoke", encode(pendingRevoke));
    else json.add("tokens", encode(active));
    Files.createDirectories(file.getParent());
    Path temp = Files.createTempFile(file.getParent(), "discord-oauth-", ".tmp");
    try {
      protect(temp);
      Files.writeString(temp, json.toString());
      try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    } finally { Files.deleteIfExists(temp); }
  }

  private static void protect(Path path) throws IOException {
    var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) { posix.setPermissions(PosixFilePermissions.fromString("rw-------")); return; }
    var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl == null) throw new IOException("Private storage unavailable");
    acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
  }

  @Override public synchronized void close() {
    if (closed) return;
    if (!remember && active != null) disconnect();
    closed = true;
    if (pendingRevoke == null) generation++;
    if (login != null) login.close();
    login = null;
    executor.shutdown();
    // Pending removal is persisted for retry on next launch; shutdown never blocks Minecraft.
  }
}
