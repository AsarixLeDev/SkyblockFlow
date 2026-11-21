package dev.asarix.skyblockflow;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** One read-only OS helper per game; no desktop I/O on the render thread. */
public final class DesktopMedia implements AutoCloseable {
  public static final Identifier COVER =
      Identifier.fromNamespaceAndPath("skyblockflow", "spotify_cover");

  static final class Session {
    String title = "", artist = "", album = "", art = "";
    long duration, position;
    boolean playing;
    int discord = -1;
    transient long observed;
    transient BufferedImage image;

    long elapsed() {
      return Math.clamp(
          position + (playing ? (System.nanoTime() - observed) / 1_000_000 : 0),
          0,
          Math.max(0, duration));
    }
  }

  private static final Gson JSON = new Gson();
  private volatile Session latest;
  private volatile Process process;
  private volatile boolean closed;
  private Thread worker;
  private volatile int requested;
  private long nextAttempt;
  private BufferedImage uploaded;

  public void poll(boolean spotify, boolean discord) {
    int mask = (spotify ? 1 : 0) | (discord ? 2 : 0);
    if (mask != requested) {
      idle();
      requested = mask;
      nextAttempt = 0;
    }
    if (mask == 0) return;
    if (closed || worker != null && worker.isAlive() || System.nanoTime() < nextAttempt) return;
    nextAttempt = System.nanoTime() + 30_000_000_000L;
    worker = Thread.ofPlatform().daemon().name("SkyblockFlow desktop media").start(() -> run(mask));
  }

  public Session current() {
    var s = latest;
    return s != null && System.nanoTime() - s.observed < 18_000_000_000L ? s : null;
  }

  private void run(int mask) {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    String name = windows ? "spotify.ps1" : "spotify.py";
    try (var in = getClass().getResourceAsStream("/assets/skyblockflow/media/" + name)) {
      String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      ProcessBuilder builder =
          windows
              ? new ProcessBuilder(
                  "powershell.exe",
                  "-NoProfile",
                  "-NonInteractive",
                  "-EncodedCommand",
                  Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
              : new ProcessBuilder("/usr/bin/python3", "-u", "-c", script);
      builder.environment().put("SKYBLOCKFLOW_SPOTIFY", (mask & 1) != 0 ? "1" : "0");
      builder.environment().put("SKYBLOCKFLOW_DISCORD", (mask & 2) != 0 ? "1" : "0");
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      var p = builder.start();
      process = p;
      try (var reader = p.inputReader(StandardCharsets.UTF_8)) {
        String line, lastArt = "";
        BufferedImage cover = null;
        while (!closed && requested == mask && (line = reader.readLine()) != null) {
          if (line.length() > 200000) continue;
          var s = JSON.fromJson(line, Session.class);
          if (s == null) continue;
          if (s.title == null) s.title = "";
          if (s.artist == null) s.artist = "";
          if (s.album == null) s.album = "";
          if (s.art == null) s.art = "";
          if (!lastArt.equals(s.art)) {
            lastArt = s.art;
            cover = null;
            if (!s.art.isBlank())
              try (var data = new ByteArrayInputStream(Base64.getDecoder().decode(s.art))) {
                cover = ImageIO.read(data);
              } catch (Exception ignored) {
              }
          }
          s.image = cover;
          s.art = "";
          s.observed = System.nanoTime();
          latest = s;
        }
      } finally {
        p.destroyForcibly();
        process = null;
      }
    } catch (Exception e) {
      SkyblockFlowClient.LOG.debug("Desktop media unavailable", e);
    } finally {
      latest = null;
    }
  }

  public boolean upload(Session s) {
    var image = s == null ? null : s.image;
    if (image == uploaded) return image != null;
    var tm = Minecraft.getInstance().getTextureManager();
    if (uploaded != null) tm.release(COVER);
    uploaded = image;
    if (image == null) return false;
    register(COVER, "Spotify artwork", image);
    return true;
  }

  static void register(Identifier id, String name, BufferedImage image) {
    var tm = Minecraft.getInstance().getTextureManager();
    int w = image.getWidth(), h = image.getHeight();
    var tex = new DynamicTexture(name, w, h, false);
    var pixels = tex.getPixels();
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) pixels.setPixel(x, y, image.getRGB(x, y));
    tm.register(id, tex);
    tex.upload();
  }

  public void idle() {
    requested = 0;
    nextAttempt = 0;
    var p = process;
    if (p != null && p.isAlive()) p.destroy();
    latest = null;
    if (uploaded != null) upload(null);
  }

  public void close() {
    closed = true;
    var p = process;
    if (p != null) p.destroyForcibly();
    upload(null);
  }
}
