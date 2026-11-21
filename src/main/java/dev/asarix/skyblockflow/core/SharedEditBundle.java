package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Validated media and presentation settings for a shared edit. */
public final class SharedEditBundle {
  public static class Manifest {
    public int protocol = 1;
    public CelebrationSettings settings;
    public EditScene scene;
    public RemoteScene remote;
    public long durationMs, seed;
    public String audioSha256;
    public List<SongAnalysis.Hit> hits;
  }

  public record Received(Manifest manifest, byte[] audio, byte[] image, byte[] terrain) {}

  private static final int MB = 1024 * 1024;

  public static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  public static byte[] file(Path directory, String name, int limit) throws IOException {
    Path root = directory.toRealPath(), p = root.resolve(name).normalize().toRealPath();
    if (!p.startsWith(root) || !Files.isRegularFile(p) || Files.size(p) > limit)
      throw new IOException("Invalid or oversized media file");
    return Files.readAllBytes(p);
  }

  public static void write(
      Path target, CelebrationSettings settings, SongAnalysis analysis, Path sounds, Path images)
      throws Exception {
    write(target, settings, analysis, sounds, images, null);
  }

  public static void write(
      Path target,
      CelebrationSettings settings,
      SongAnalysis analysis,
      Path sounds,
      Path images,
      EditScene scene)
      throws Exception {
    write(target, settings, analysis, sounds, images, scene, null, null);
  }

  public static void write(
      Path target,
      CelebrationSettings settings,
      SongAnalysis analysis,
      Path sounds,
      Path images,
      EditScene scene,
      RemoteScene remote,
      byte[] terrain)
      throws Exception {
    if (settings.soundKind != SoundKind.MP3)
      throw new IOException("Choose an MP3 in the celebration settings before sharing an edit.");
    byte[] audio = file(sounds, settings.sound, 32 * MB),
        image = settings.image.isBlank() ? null : file(images, settings.image, 8 * MB);
    if (image != null) validateImage(image);
    var m = new Manifest();
    m.settings = ConfigStore.copy(settings, CelebrationSettings.class);
    m.settings.presetId = "";
    m.scene = remote == null ? scene : null;
    m.remote = remote;
    m.protocol = remote != null ? 3 : scene == null ? 1 : 2;
    if (remote != null) {
      if (terrain == null) throw new IOException("Missing terrain snapshot");
      TerrainSnapshot.decode(terrain);
      remote.terrainSha256 = hash(terrain);
    }
    m.durationMs = analysis.durationMs();
    m.hits = analysis.hits();
    m.audioSha256 = hash(audio);
    m.seed = new Random().nextLong();
    validate(m);
    try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
      entry(
          zip,
          "manifest.json",
          ConfigStore.GSON.toJson(m).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      entry(zip, "audio.mp3", audio);
      if (image != null) entry(zip, "image.png", image);
      if (terrain != null) entry(zip, "terrain.bin", terrain);
    }
  }

  private static void entry(ZipOutputStream z, String name, byte[] bytes) throws IOException {
    var crc = new CRC32();
    crc.update(bytes);
    var entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(bytes.length);
    entry.setCompressedSize(bytes.length);
    entry.setCrc(crc.getValue());
    z.putNextEntry(entry);
    z.write(bytes);
    z.closeEntry();
  }

  public static Received read(Path file) throws Exception {
    return read(file, null);
  }

  public static Received read(Path file, byte[] cachedAudio) throws Exception {
    if (Files.size(file) > 50L * MB) throw new IOException("Edit is too large");
    var entries = new HashMap<String, byte[]>();
    try (var zip = new ZipInputStream(Files.newInputStream(file))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        int limit =
            switch (entry.getName()) {
              case "manifest.json" -> MB;
              case "audio.mp3" -> 32 * MB;
              case "image.png" -> 8 * MB;
              case "terrain.bin" -> TerrainSnapshot.MAX_BYTES;
              default -> throw new IOException("Unexpected edit file");
            };
        if (entries.containsKey(entry.getName())
            || entry.getMethod() != ZipEntry.STORED
            || entry.getSize() > limit) throw new IOException("Invalid edit archive");
        byte[] bytes = zip.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Edit file exceeds its limit");
        entries.put(entry.getName(), bytes);
      }
    }
    if (!entries.containsKey("audio.mp3") && cachedAudio != null)
      entries.put("audio.mp3", cachedAudio);
    if (!entries.containsKey("manifest.json") || !entries.containsKey("audio.mp3"))
      throw new IOException("Incomplete edit");
    Manifest m =
        ConfigStore.GSON.fromJson(
            new String(entries.get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8),
            Manifest.class);
    validate(m);
    if (!hash(entries.get("audio.mp3")).equals(m.audioSha256))
      throw new IOException("Audio fingerprint mismatch");
    if (entries.containsKey("image.png")) validateImage(entries.get("image.png"));
    byte[] terrain = entries.get("terrain.bin");
    if ((m.remote != null) != (terrain != null)) throw new IOException("Incomplete terrain edit");
    if (terrain != null && !hash(terrain).equals(m.remote.terrainSha256))
      throw new IOException("Terrain fingerprint mismatch");
    return new Received(m, entries.get("audio.mp3"), entries.get("image.png"), terrain);
  }

  private static void validate(Manifest m) throws IOException {
    if (m == null
        || (m.protocol < 1 || m.protocol > 3)
        || (m.protocol == 2) != (m.scene != null)
        || (m.protocol == 3) != (m.remote != null)
        || m.settings == null
        || m.settings.soundKind != SoundKind.MP3
        || m.durationMs < 1
        || m.durationMs > 3600000
        || m.audioSha256 == null
        || !m.audioSha256.matches("[a-f0-9]{64}")
        || m.hits == null
        || m.hits.size() > 50000) throw new IOException("Invalid edit manifest");
    ConfigStore.validateCelebration(m.settings);
    if (m.scene != null) m.scene.validate();
    if (m.remote != null) m.remote.validate();
    long previous = -1;
    for (var h : m.hits) {
      if (h == null
          || h.timeMs() <= previous
          || h.timeMs() > m.durationMs
          || !Float.isFinite(h.strength())
          || h.strength() < 0
          || h.strength() > 100) throw new IOException("Invalid shared hit timeline");
      previous = h.timeMs();
    }
  }

  private static void validateImage(byte[] bytes) throws IOException { PngSafety.validate(bytes); }
}
