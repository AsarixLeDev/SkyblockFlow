package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One authenticated relay connection. The Minecraft access token is never accepted here. */
public final class EditRelayClient implements AutoCloseable {
  public static class Failure extends IOException {
    public final int status;

    public Failure(int status, String text) {
      super(text);
      this.status = status;
    }
  }

  private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES = deadlines();
  private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
    var executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
      var thread = new Thread(task, "SkyblockFlow transfer timeout"); thread.setDaemon(true); return thread;
    });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }
  private AutoCloseable bodyDeadline(InputStream input) {
    var timeout = DEADLINES.schedule(() -> {
      try { input.close(); } catch (IOException ignored) { }
    }, requestTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    return () -> timeout.cancel(false);
  }

  private final URI base;
  private final Duration requestTimeout;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private String token = "";
  public long clockOffsetMs;

  public EditRelayClient(String url) { this(url, Duration.ofSeconds(75)); }

  public EditRelayClient(String url, Duration timeout) {
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(75)) > 0)
      throw new IllegalArgumentException("Invalid relay timeout");
    base = address(url);
    requestTimeout = timeout;
  }

  public static URI address(String url) {
    URI uri = URI.create(url.strip());
    boolean local =
        "http".equals(uri.getScheme())
            && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
    if ((!"https".equals(uri.getScheme()) && !local)
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (!uri.getPath().isEmpty() && !uri.getPath().equals("/")))
      throw new IllegalArgumentException(
          "Use the relay's HTTPS domain, without a path. HTTP is only allowed on localhost.");
    return URI.create(url.strip().replaceAll("/+$", ""));
  }

  public void token(String value) {
    token = value;
  }

  private HttpRequest.Builder request(String path) {
    var b = HttpRequest.newBuilder(base.resolve(path)).timeout(requestTimeout);
    if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
    return b;
  }

  public JsonObject get(String path) throws Exception {
    return json(request(path).GET().build());
  }

  public JsonObject post(String path, Object body) throws Exception {
    return json(
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(ConfigStore.GSON.toJson(body)))
            .build());
  }

  private volatile java.util.function.BiConsumer<Long, Long> uploadProgress = (done, total) -> {};

  public void uploadProgress(java.util.function.BiConsumer<Long, Long> progress) {
    uploadProgress = progress;
  }

  public JsonObject upload(Path bundle) throws Exception {
    var body = HttpRequest.BodyPublishers.ofFile(bundle);
    long total = body.contentLength();
    var progress = uploadProgress;
    HttpRequest.BodyPublisher counted =
        new HttpRequest.BodyPublisher() {
          public long contentLength() {
            return total;
          }

          public void subscribe(
              java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> downstream) {
            body.subscribe(
                new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                  long sent;

                  public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                    progress.accept(0L, total);
                    downstream.onSubscribe(subscription);
                  }

                  public void onNext(java.nio.ByteBuffer bytes) {
                    int size = bytes.remaining();
                    downstream.onNext(bytes);
                    sent += size;
                    progress.accept(sent, total);
                  }

                  public void onError(Throwable error) {
                    downstream.onError(error);
                  }

                  public void onComplete() {
                    downstream.onComplete();
                  }
                });
          }
        };
    return json(
        request("/v1/bundles").header("Content-Type", "application/zip").POST(counted).build());
  }

  /** Roll back the upload if sending fails or the user stops during the transfer. */
  public JsonObject sendInvite(
      Path bundle, String world, Map<String, Object> details, BooleanSupplier cancelled)
      throws Exception {
    String bundleId = null;
    boolean sent = false;
    try {
      checkCancelled(cancelled);
      bundleId = upload(bundle).get("bundleId").getAsString();
      checkCancelled(cancelled);
      post("/v1/state", Map.of("world", world, "sceneProtocol", 3));
      checkCancelled(cancelled);
      var body = new HashMap<String, Object>(details);
      body.put("bundleId", bundleId);
      var response = post("/v1/invites", body);
      checkCancelled(cancelled);
      sent = true;
      return response;
    } finally {
      if (bundleId != null && !sent) {
        try {
          post("/v1/bundles/" + bundleId + "/discard", Map.of());
        } catch (Exception ignored) {
          // Preserve the original failure. The relay also reclaims leftovers on the next upload.
        }
      }
    }
  }

  private static void checkCancelled(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) throw new CancellationException("Shared edit stopped");
  }

  private JsonObject json(HttpRequest req) throws Exception {
    long before = System.currentTimeMillis();
    var response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    try (var in = response.body(); var deadline = bodyDeadline(in)) {
      byte[] bytes = in.readNBytes(1024 * 1024 + 1);
      if (bytes.length > 1024 * 1024) throw new IOException("Relay response too large");
      JsonObject doc;
      try {
        doc =
            JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
      } catch (Exception error) {
        throw new IOException(
            "Relay returned an invalid response (HTTP " + response.statusCode() + ")");
      }
      if (response.statusCode() != 200)
        throw new Failure(
            response.statusCode(),
            doc.has("error")
                ? doc.get("error").getAsString()
                : "Relay HTTP " + response.statusCode());
      if (doc.has("serverTimeMs"))
        clockOffsetMs =
            doc.get("serverTimeMs").getAsLong() - (before + System.currentTimeMillis()) / 2;
      return doc;
    }
  }

  public void download(String id, Path target) throws Exception {
    download(id, target, "", (done, total) -> {});
  }

  public void download(
      String id,
      Path target,
      String cachedAudio,
      java.util.function.BiConsumer<Long, Long> progress)
      throws Exception {
    if (!id.matches("[a-f0-9]{32}")) throw new IOException("Invalid invitation id");
    var builder = request("/v1/invites/" + id + "/download");
    if (cachedAudio.matches("[a-f0-9]{64}")) builder.header("X-Flow-Cached-Audio", cachedAudio);
    var response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var in = response.body(); var deadline = bodyDeadline(in)) {
      if (response.statusCode() != 200)
        throw new IOException("Download unavailable (HTTP " + response.statusCode() + ")");
      long length = response.headers().firstValueAsLong("Content-Length").orElse(0);
      if (length < 0 || length > 50L * 1024 * 1024) throw new IOException("Edit exceeds 50 MB");
      progress.accept(0L, length);
      try (var out = Files.newOutputStream(target)) {
        byte[] bytes = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(bytes)) >= 0) {
          total += n;
          if (total > 50L * 1024 * 1024) throw new IOException("Edit exceeds 50 MB");
          out.write(bytes, 0, n);
          progress.accept(total, length);
        }
      }
    } catch (Exception error) {
      Files.deleteIfExists(target);
      throw error;
    }
  }

  public void leaveAppearanceThenClose() { stopThenClose("/v1/appearance/leave"); }

  public void stopThenClose() { stopThenClose("/v1/stop"); }

  private void stopThenClose(String path) {
    if (token.isEmpty()) {
      close();
      return;
    }
    try { http.sendAsync(
            request(path)
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .whenComplete((r, e) -> http.shutdownNow());
    } catch (RuntimeException ignored) { http.shutdownNow(); }
  }

  @Override
  public void close() {
    http.shutdownNow();
  }
}
