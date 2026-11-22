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
    JOBS("/api/s3/jobs", 120_000), FISHING("/api/s3/fishing", 60_000),
    ISLANDS("/api/s3/island-stats", 300_000), MARKET("/api/s3/player-shops", 60_000),
    ECONOMY("/api/s3/economie", 30_000);
    final String path; public final long ttl;
    Feed(String path, long ttl) { this.path = path; this.ttl = ttl; }
  }
  public record Snapshot(Object data, long receivedAt) {}
  public static final long MAX_QUOTE_AGE = 600_000;
  private static final int LIMIT = 4 * 1024 * 1024;
  private final Path directory;
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
  private final Slot detail = new Slot();
  private volatile UUID detailId;
  private volatile long blockedUntil;
  private volatile boolean closed;

  public ExcaliaApiClient(Path directory) {
    this.directory = directory;
    for (var feed : Feed.values()) feeds.put(feed, new Slot());
  }
  public Snapshot snapshot(Feed feed) { return feeds.get(feed).snapshot; }
  public boolean settled(Feed feed) { var slot = feeds.get(feed); return slot.snapshot != null || slot.attempted && !slot.busy; }
  public String error(Feed feed) { return feeds.get(feed).error; }
  public Snapshot detail(UUID id) { return id.equals(detailId) ? detail.snapshot : null; }
  public void request(Feed feed) {
    var slot = feeds.get(feed);
    enqueue(slot, feed.path, feed.ttl, feed.name().toLowerCase(Locale.ROOT),
        (json, at) -> parse(feed, json, at));
  }
  public void requestDetail(UUID id) {
    if (detail.busy) return;
    if (!id.equals(detailId)) { detailId = id; detail.snapshot = null; detail.next = 0; detail.attempted = false; }
    enqueue(detail, "/api/island-stats/island/" + id + "?saison=3", 30_000, "island-" + id,
        (json, at) -> { var value = ExcaliaApiData.detail(json);
          if (!id.equals(value.id())) throw new IllegalArgumentException("Wrong island"); return value; });
  }
  @FunctionalInterface private interface Parser { Object parse(JsonObject json, long at); }
  public static Object parse(Feed feed, JsonObject json, long at) {
    return switch (feed) {
      case JOBS -> ExcaliaApiData.jobs(json, at);
      case FISHING -> ExcaliaApiData.fishing(json, at);
      case ISLANDS -> ExcaliaApiData.islands(json, at);
      case MARKET -> ExcaliaApiData.market(json, at);
      case ECONOMY -> ExcaliaApiData.economy(json, at);
    };
  }
  private synchronized void enqueue(Slot slot, String path, long ttl, String file, Parser parser) {
    long now = System.currentTimeMillis();
    if (closed || slot.busy || now < Math.max(slot.next, blockedUntil)) return;
    slot.busy = true;
    executor.execute(() -> {
      try {
        if (closed) return;
        if (!slot.attempted) load(slot, file, ttl, parser);
        if (System.currentTimeMillis() < Math.max(slot.next, blockedUntil)) return;
        var request = HttpRequest.newBuilder(URI.create("https://excalia.fr" + path))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
            .header("User-Agent", "SkyblockFlow/1.0 (https://github.com/AsarixLeDev/SkyblockFlow)").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        var deadline = deadlines.schedule(() -> {
          try { response.body().close(); } catch (IOException ignored) {}
        }, 15, TimeUnit.SECONDS);
        try (var input = response.body()) {
          if (response.statusCode() == 429) {
            blockedUntil = System.currentTimeMillis() + retryDelay(response.headers().firstValue("Retry-After").orElse("60"), System.currentTimeMillis());
            throw new IOException("Please wait before refreshing");
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
        slot.snapshot = new Snapshot(parsed, at); slot.next = at + ttl; slot.error = ""; slot.failures = 0;
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
      slot.snapshot = new Snapshot(parser.parse(envelope.getAsJsonObject("data"), at), at);
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
