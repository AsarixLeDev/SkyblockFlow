package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CelebrationEditSettings;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** User annotations are stored apart from disposable detector caches, keyed by audio content. */
public final class HitTimelineStore {
  private final Path sounds;

  public HitTimelineStore(Path sounds) {
    this.sounds = sounds;
  }

  public String fingerprint(String name) throws IOException {
    Path root = sounds.toRealPath(), file = root.resolve(name).normalize().toRealPath();
    if (!file.startsWith(root)
        || !file.toString().toLowerCase(Locale.ROOT).endsWith(".mp3")
        || Files.size(file) > 32L * 1024 * 1024) throw new IOException("Invalid MP3 path");
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      try (var in = Files.newInputStream(file)) {
        byte[] buffer = new byte[16384];
        for (int n; (n = in.read(buffer)) != -1; ) hash.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }

  public HitTimeline create(
      String name, SongAnalysis automatic, CelebrationEditSettings settings, float pitch)
      throws IOException {
    var doc = new HitTimeline();
    doc.audioSha256 = fingerprint(name);
    doc.songName = name;
    doc.durationMs = automatic.durationMs();
    doc.detectorSettings = ConfigStore.copy(settings, CelebrationEditSettings.class);
    doc.analysisPitch = pitch;
    doc.detectedHits = new ArrayList<>(automatic.hits());
    doc.hits = new ArrayList<>(automatic.hits());
    return doc;
  }

  private Path local(String hash) {
    return sounds.resolve(".timelines").resolve(hash + ".json");
  }

  public HitTimeline load(String name, long duration) throws IOException {
    String hash = fingerprint(name);
    Path path = local(hash);
    if (!Files.exists(path)) return null;
    return read(path, hash, duration);
  }

  public HitTimeline importFile(Path source, String name, long duration) throws IOException {
    return read(source, fingerprint(name), duration);
  }

  private HitTimeline read(Path file, String hash, long duration) throws IOException {
    if (Files.size(file) > 8L * 1024 * 1024)
      throw new IOException("Timeline must be smaller than 8 MB");
    try (var reader = Files.newBufferedReader(file)) {
      var doc = ConfigStore.GSON.fromJson(reader, HitTimeline.class);
      validate(doc, hash, duration);
      doc.updateEdits();
      return doc;
    } catch (RuntimeException e) {
      throw new IOException("Invalid hit timeline: " + e.getMessage(), e);
    }
  }

  static void validate(HitTimeline doc, String hash, long duration) throws IOException {
    if (doc == null
        || doc.schemaVersion != 1
        || !"original-audio-milliseconds".equals(doc.timebase))
      throw new IOException("Unsupported hit timeline format");
    if (!hash.equals(doc.audioSha256))
      throw new IOException(
          "This timeline belongs to a different MP3. Select the matching audio file.");
    if (doc.durationMs != duration || duration < 1 || duration > 3600000)
      throw new IOException("Timeline duration does not match this song");
    if (doc.detectorSettings == null
        || !Float.isFinite(doc.analysisPitch)
        || doc.analysisPitch < .5
        || doc.analysisPitch > 2) throw new IOException("Invalid detector metadata");
    ConfigStore.validateCelebrationEdit(doc.detectorSettings);
    validateHits(doc.hits, duration);
    validateHits(doc.detectedHits, duration);
  }

  private static void validateHits(List<SongAnalysis.Hit> hits, long duration) throws IOException {
    if (hits == null || hits.size() > 50000)
      throw new IOException("Invalid number of hits (maximum 50000)");
    long previous = -1;
    for (var hit : hits) {
      if (hit == null
          || hit.timeMs() <= previous
          || hit.timeMs() < 0
          || hit.timeMs() > duration
          || !Float.isFinite(hit.strength())
          || hit.strength() < 0
          || hit.strength() > 100)
        throw new IOException("Hits must be ordered, unique, finite and inside the song");
      previous = hit.timeMs();
    }
  }

  public void save(String name, HitTimeline document) throws IOException {
    var doc = document.copy();
    validate(doc, fingerprint(name), doc.durationMs);
    doc.updateEdits();
    Path destination = local(doc.audioSha256);
    Files.createDirectories(destination.getParent());
    Path temp = Files.createTempFile(destination.getParent(), "timeline-", ".tmp");
    try {
      Files.writeString(temp, ConfigStore.GSON.toJson(doc));
      try {
        Files.move(
            temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public Path exportFile(Path destination, String name, HitTimeline document) throws IOException {
    var doc = document.copy();
    validate(doc, fingerprint(name), doc.durationMs);
    doc.updateEdits();
    if (!destination.toString().toLowerCase(Locale.ROOT).endsWith(".json"))
      throw new IOException("Choose a .json filename");
    Path target = destination.toAbsolutePath().normalize();
    // Exports never silently overwrite previous annotations.
    Files.createDirectories(target.getParent());
    String filename = target.getFileName().toString();
    for (int i = 2; Files.exists(target); i++)
      target =
          destination
              .toAbsolutePath()
              .resolveSibling(filename.substring(0, filename.length() - 5) + "-" + i + ".json");
    Files.writeString(target, ConfigStore.GSON.toJson(doc), StandardOpenOption.CREATE_NEW);
    return target;
  }
}
