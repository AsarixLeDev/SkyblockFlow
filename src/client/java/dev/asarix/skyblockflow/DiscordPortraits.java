package dev.asarix.skyblockflow;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** The render thread owns the texture cache. Only CDN reads and PNG decoding use the worker. */
final class DiscordPortraits {
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> Thread.ofPlatform().daemon().name("SkyblockFlow portraits").unstarted(r));

  private record Entry(CompletableFuture<BufferedImage> data, Identifier texture) {}

  private final Map<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);

  Identifier texture(DiscordVoice.Member member) {
    String hash = member.avatar();
    if (!hash.matches("(?:a_)?[a-fA-F0-9]{32}") || !member.id().matches("[0-9]+")) return null;
    String key = member.id() + "/" + hash;
    var e = entries.get(key);
    if (e == null) {
      if (entries.size() >= 32) release(entries.keySet().iterator().next());
      entries.put(key, new Entry(CompletableFuture.supplyAsync(() -> download(key), io), null));
      return null;
    }
    if (e.texture != null || e.data == null || !e.data.isDone()) return e.texture;
    var img = e.data.getNow(null);
    Identifier id = null;
    if (img != null) {
      id = Identifier.fromNamespaceAndPath("skyblockflow", "voice/" + key);
      DesktopMedia.register(id, "Discord portrait", img);
    }
    entries.put(key, new Entry(null, id));
    return id;
  }

  private static BufferedImage download(String key) {
    try {
      var c =
          URI.create("https://cdn.discordapp.com/avatars/" + key + ".png?size=32")
              .toURL()
              .openConnection();
      if (c instanceof HttpURLConnection h) h.setInstanceFollowRedirects(false);
      c.setConnectTimeout(2000);
      c.setReadTimeout(2000);
      try (var in = c.getInputStream()) {
        var bytes = in.readNBytes(65537);
        if (bytes.length > 65536) return null;
        try (var image = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
          var readers = ImageIO.getImageReaders(image);
          if (!readers.hasNext()) return null;
          var reader = readers.next();
          try {
            reader.setInput(image, true, true);
            if (reader.getWidth(0) < 1 || reader.getHeight(0) < 1
                || reader.getWidth(0) > 128 || reader.getHeight(0) > 128) return null;
            return reader.read(0);
          } finally { reader.dispose(); }
        }
      }
    } catch (Exception e) {
      return null;
    }
  }

  private void release(String key) {
    var e = entries.remove(key);
    if (e.texture != null) Minecraft.getInstance().getTextureManager().release(e.texture);
    if (e.data != null) e.data.cancel(false);
  }

  void clear() {
    while (!entries.isEmpty()) release(entries.keySet().iterator().next());
  }

  void close() {
    clear();
    io.shutdownNow();
  }
}
