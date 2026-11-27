package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Fixed-origin, read-only requests. No credentials or game-session tokens are sent. */
public final class ExcaliaApiClient implements AutoCloseable {
  public enum Feed {
    JOBS("/api/s3/jobs"), FISHING("/api/s3/fishing"), ISLANDS("/api/s3/island-stats"),
    MARKET("/api/s3/player-shops"), ECONOMY("/api/s3/economie"), MONEY("/api/s3/skydollars"),
    ONLINE("/api/online-players-s3"), PASS("/api/s3/battlepass-mc"), QUESTS("/api/s3/quests"),
    DUNGEONS("/api/s3/dungeons"), WEAPONS("/api/s3/weapons"), PETS("/api/s3/pets");
    final String path;
    public final long ttl = 600_000;
    Feed(String path) { this.path = path; }
  }
  public record Snapshot(Object data, long receivedAt, long sourceAt, ProfileIndex profiles) {
    public Snapshot(Object data,long receivedAt) { this(data,receivedAt,receivedAt,ProfileIndex.EMPTY); }
  }
  public static final long MAX_QUOTE_AGE = 600_000;
  private static final int LIMIT = 4 * 1024 * 1024;
  private final Path directory;
  private final URI origin;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    var thread = new Thread(r, "skyblockflow-public-api"); thread.setDaemon(true); return thread;
  });
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
    var thread = new Thread(r, "skyblockflow-api-deadline"); thread.setDaemon(true); return thread;
  });
  private static final class Slot {
    volatile Snapshot snapshot;
    volatile boolean busy, attempted;
    volatile long next;
    volatile String error = "";
    int failures;
  }
  private final Map<Feed, Slot> feeds = new EnumMap<>(Feed.class);
  private final Map<UUID, Slot> details = new LinkedHashMap<>(16, .75f, true);
  private long lastNetworkStart;
  private volatile long blockedUntil;
  private volatile boolean closed;

  public ExcaliaApiClient(Path directory) {
    this(directory,URI.create("https://excalia.fr"));
  }
  ExcaliaApiClient(Path directory, URI origin) {
    this.directory = directory;
    this.origin = origin;
    for (var feed : Feed.values()) feeds.put(feed, new Slot());
  }
  public Snapshot snapshot(Feed feed) { return feeds.get(feed).snapshot; }
  public boolean settled(Feed feed) { var slot = feeds.get(feed); return slot.snapshot != null || slot.attempted && !slot.busy || blockedUntil > System.currentTimeMillis(); }
  public String error(Feed feed) { return feeds.get(feed).error; }
  public synchronized Snapshot detail(UUID id) {
    var slot = details.get(id); return slot == null ? null : slot.snapshot;
  }
  public String state(Feed feed) {
    var slot = feeds.get(feed);
    if (blockedUntil > System.currentTimeMillis())
      return "API busy · retry in " + (1 + (blockedUntil - System.currentTimeMillis()) / 1000) + "s";
    if (!slot.error.isEmpty()) return slot.error;
    return slot.busy || slot.snapshot == null ? "Loading…" : "";
  }
  public boolean busy(Feed feed){return feeds.get(feed).busy;}
  private void allowManualRefresh(Slot slot){
    if(slot!=null && !slot.busy && slot.error.isEmpty() && slot.snapshot!=null
        && System.currentTimeMillis()-slot.snapshot.receivedAt()>=60_000)slot.next=0;
  }
  public synchronized void refresh(Feed feed) { refresh(feed, 60_000); }
  public synchronized void refresh(Feed feed, long intervalMs) {
    var slot = feeds.get(feed);
    if (slot.snapshot != null && System.currentTimeMillis() - slot.snapshot.receivedAt() < Math.max(60_000, intervalMs)) return;
    allowManualRefresh(slot);
    request(feed);
  }
  public synchronized void refreshDetail(UUID id){allowManualRefresh(details.get(id));requestDetail(id);}
  public void request(Feed feed) {
    var slot = feeds.get(feed);
    enqueue(slot, feed.path, feed.ttl, feed.name().toLowerCase(Locale.ROOT),
        (json, at) -> new Snapshot(parse(feed, json, at), at, ExcaliaApiData.timestamp(json, at), ProfileIndex.build(feed, json)));
  }
  public synchronized Snapshot knownMembership(String name) {
    for (var slot:details.values()) {
      var snapshot=slot.snapshot;
      if (snapshot!=null && !snapshot.profiles().forPlayer(name).isEmpty()) return snapshot;
    }
    return null;
  }
  public synchronized void requestDetail(UUID id) {
    if (!details.containsKey(id) && details.size() >= 32) {
      var oldest = details.entrySet().stream().filter(e -> !e.getValue().busy).findFirst();
      if (oldest.isEmpty()) return;
      details.remove(oldest.get().getKey());
    }
    var detail = details.computeIfAbsent(id, k -> new Slot());
    enqueue(detail, "/api/island-stats/island/" + id + "?saison=3", MAX_QUOTE_AGE, "island-" + id,
        (json, at) -> { var value = ExcaliaApiData.detail(json);
          if (!id.equals(value.id())) throw new IllegalArgumentException("Wrong island"); return new Snapshot(value, at, at, ProfileIndex.islandDetail(json)); });
  }
  @FunctionalInterface private interface Parser { Snapshot parse(JsonObject json, long at); }
  public static Object parse(Feed feed, JsonObject json, long at) {
    return switch (feed) {
      case JOBS -> ExcaliaApiData.jobs(json, at);
      case FISHING -> ExcaliaApiData.fishing(json, at);
      case ISLANDS -> ExcaliaApiData.islands(json, at);
      case MONEY -> ExcaliaApiData.wallets(json);
      case MARKET -> ExcaliaApiData.market(json, at);
      case ECONOMY -> ExcaliaApiData.economy(json, at);
      default -> ProfileIndex.EMPTY;
    };
  }
  private synchronized void enqueue(Slot slot, String path, long ttl, String file, Parser parser) {
    long now = System.currentTimeMillis();
    if (closed || slot.busy || now < slot.next || slot.attempted && now < blockedUntil) return;
    slot.busy = true;
    executor.execute(() -> {
      try {
        if (closed) return;
        if (!slot.attempted) load(slot, file, ttl, parser);
        if (System.currentTimeMillis() < Math.max(slot.next, blockedUntil)) return;
        long pause = lastNetworkStart + 1200 - System.currentTimeMillis();
        if (pause > 0) Thread.sleep(pause);
        lastNetworkStart = System.currentTimeMillis();
        var request = HttpRequest.newBuilder(origin.resolve(path))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
            .header("User-Agent", "SkyblockFlow/1.0 (https://github.com/AsarixLeDev/SkyblockFlow)").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        var deadline = deadlines.schedule(() -> {
          try { response.body().close(); } catch (IOException ignored) {}
        }, 15, TimeUnit.SECONDS);
        try (var input = response.body()) {
          if (response.statusCode() == 429 || response.statusCode() == 503 || response.statusCode() == 502) {
            blockedUntil = System.currentTimeMillis() + retryDelay(response.headers().firstValue("Retry-After").orElse("60"), System.currentTimeMillis());
            throw new IOException("API busy (" + response.statusCode() + ")");
          }
          if (response.statusCode() != 200) throw new IOException("Public data unavailable (" + response.statusCode() + ")");
          if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new IOException("Unexpected public data response");
          bytes = input.readNBytes(LIMIT + 1);
        } finally { deadline.cancel(false); }
        if (bytes.length > LIMIT) throw new IOException("Public data response is too large");
        long at = System.currentTimeMillis();
        var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        var parsed = parser.parse(json, at);
        slot.snapshot = parsed; slot.next = at + ttl; slot.error = ""; slot.failures = 0;
        save(file, json, at);
      } catch (Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        slot.error = failure instanceof IOException ? failure.getMessage() : "Public data could not be read";
        slot.next = System.currentTimeMillis() + Math.min(900_000, 60_000L << Math.min(slot.failures++, 4));
      } finally { slot.attempted = true; slot.busy = false; }
    });
  }
  public static long retryDelay(String value, long now) {
    try { return Math.clamp(Math.multiplyExact(Long.parseLong(value.trim()), 1000), 1000, 86_400_000); }
    catch (Exception ignored) {
      try { return Math.clamp(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now, 1000, 86_400_000); }
      catch (Exception badDate) { return 60_000; }
    }
  }
  private void load(Slot slot, String file, long ttl, Parser parser) {
    try {
      var path = directory.resolve(file + ".json");
      if (!Files.isRegularFile(path) || Files.size(path) > LIMIT) return;
      var envelope = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      long at = envelope.get("receivedAt").getAsLong(), age = System.currentTimeMillis() - at;
      if (age < 0 || age > MAX_QUOTE_AGE) return;
      slot.snapshot = parser.parse(envelope.getAsJsonObject("data"), at);
      slot.next = at + ttl;
    } catch (Exception ignored) { /* A damaged cache is replaced by the next public response. */ }
  }
  private void save(String file, JsonObject json, long at) {
    Path temporary = null;
    try {
      Files.createDirectories(directory);
      var envelope = new JsonObject(); envelope.addProperty("receivedAt", at); envelope.add("data", json);
      temporary = Files.createTempFile(directory, "snapshot-", ".tmp");
      Files.writeString(temporary, envelope.toString());
      try { Files.move(temporary, directory.resolve(file + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, directory.resolve(file + ".json"), StandardCopyOption.REPLACE_EXISTING); }
    } catch (IOException ignored) { /* Caching is optional; the validated in-memory snapshot remains usable. */ }
    finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
  }
  @Override public void close() { closed = true; executor.shutdownNow(); http.shutdownNow(); deadlines.shutdownNow(); }
}
