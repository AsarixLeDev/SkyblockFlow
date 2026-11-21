package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.LiquipediaMatches;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Small render-thread texture cache backed by bounded, off-thread image downloads. */
final class TeamLogos implements AutoCloseable {
  private static final int MAX_ENTRIES = 16, MAX_BYTES = 524_288, MAX_DIMENSION = 512;
  private static final long RETRY_DELAY = TimeUnit.MINUTES.toNanos(15);

  record Logo(Identifier texture, int width, int height) {}
  private record Entry(Future<BufferedImage> image, Logo logo, long retryAt) {}

  private final Map<URI, Entry> cache = new LinkedHashMap<>(16, .75f, true);
  private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(MAX_ENTRIES),
      task -> Thread.ofPlatform().daemon().name("SkyblockFlow team logos").unstarted(task));
  private final Function<URI, BufferedImage> fetch;
  private int textureNumber;

  TeamLogos() { this(TeamLogos::download); }

  TeamLogos(Function<URI, BufferedImage> fetch) { this.fetch = fetch; }

  Logo get(String url) {
    URI uri = LiquipediaMatches.logoUri(url);
    if (uri == null || io.isShutdown()) return null;
    Entry entry = cache.get(uri);
    long now = System.nanoTime();
    if (entry == null || entry.image() == null && entry.logo() == null && now - entry.retryAt() >= 0) {
      if (entry == null && cache.size() >= MAX_ENTRIES) release(cache.keySet().iterator().next());
      cache.put(uri, new Entry(io.submit(() -> fetch.apply(uri)), null, 0));
      return null;
    }
    if (entry.image() == null) return entry.logo();
    if (!entry.image().isDone()) return null;

    Logo logo = null;
    try {
      BufferedImage image = entry.image().get();
      if (image != null) {
        var texture = Identifier.fromNamespaceAndPath("skyblockflow", "team/" + ++textureNumber);
        DesktopMedia.register(texture, "Liquipedia team logo", image);
        logo = new Logo(texture, image.getWidth(), image.getHeight());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | CancellationException unavailable) {
      // Keep the fallback visible while the failed request cools down.
    }
    cache.put(uri, new Entry(null, logo, now + RETRY_DELAY));
    return logo;
  }

  private static BufferedImage download(URI uri) {
    HttpURLConnection connection = null;
    try {
      RocketLeagueFeed.rateLimit();
      connection = (HttpURLConnection) uri.toURL().openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent",
          "SkyblockFlow (Minecraft overlay; contact: https://github.com/asarix)");
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_BYTES) return null;
      try (var in = connection.getInputStream()) {
        byte[] bytes = in.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) return null;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
          if (input == null) return null;
          var readers = ImageIO.getImageReaders(input);
          if (!readers.hasNext()) return null;
          var reader = readers.next();
          try {
            reader.setInput(input, true, true);
            if (reader.getWidth(0) > MAX_DIMENSION || reader.getHeight(0) > MAX_DIMENSION) return null;
            return reader.read(0);
          } finally {
            reader.dispose();
          }
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    } catch (IOException | RuntimeException unavailable) {
      return null;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private void release(URI uri) {
    Entry entry = cache.remove(uri);
    if (entry.logo() != null) Minecraft.getInstance().getTextureManager().release(entry.logo().texture());
    if (entry.image() != null) {
      entry.image().cancel(true);
      io.purge();
    }
  }

  @Override
  public void close() {
    while (!cache.isEmpty()) release(cache.keySet().iterator().next());
    io.shutdownNow();
  }
}
