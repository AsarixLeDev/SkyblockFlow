package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CelebrationEditSettings;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javazoom.jl.decoder.*;

/** One background decoder, bounded memory/disk cache, no decoded song PCM retained. */
public final class SongAnalysisCache implements AutoCloseable {
  public static final int VERSION = 3;
  private static final int MAX_FRAMES = 3600 * 200 + 200;
  private final HitTimelineStore timelines;
  private final Path sounds, cache;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "Song analysis");
            t.setDaemon(true);
            return t;
          });
  private final Map<String, CompletableFuture<SongAnalysis>> jobs = new LinkedHashMap<>();

  public SongAnalysisCache(Path sounds) {
    this.sounds = sounds;
    timelines = new HitTimelineStore(sounds);
    cache = sounds.resolve(".analysis");
  }

  public synchronized void forgetSharedAudio() { jobs.keySet().removeIf(key -> key.startsWith(".shared/")); }

  public CompletableFuture<SongAnalysis> request(String name, CelebrationEditSettings settings) {
    return request(name, settings, 1);
  }

  public synchronized CompletableFuture<SongAnalysis> request(
      String name, CelebrationEditSettings settings, float pitch) {
    return requestInternal(name, settings, pitch, true);
  }

  public CompletableFuture<SongAnalysis> requestAutomatic(
      String name, CelebrationEditSettings settings, float pitch) {
    return requestInternal(name, settings, pitch, false);
  }

  private synchronized CompletableFuture<SongAnalysis> requestInternal(
      String name, CelebrationEditSettings settings, float pitch, boolean corrected) {
    if (!Float.isFinite(pitch) || pitch < .5 || pitch > 2)
      return CompletableFuture.failedFuture(new IllegalArgumentException("Pitch must be 0.5–2"));
    var copy = ConfigStore.copy(settings, CelebrationEditSettings.class);
    try {
      ConfigStore.validateCelebrationEdit(copy);
    } catch (RuntimeException invalid) {
      return CompletableFuture.failedFuture(invalid);
    }
    copy.beatCooldownMs = Math.round(copy.beatCooldownMs * pitch);
    String key =
        name
            + "|"
            + corrected
            + "|"
            + copy.bassDetectionMode
            + "|"
            + copy.analysisHz
            + "|"
            + copy.bassLowHz
            + "|"
            + copy.bassHighHz
            + "|"
            + copy.bassThreshold
            + "|"
            + copy.beatCooldownMs;
    try {
      var p = safeFile(name);
      key += "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
    var existing = jobs.get(key);
    if (existing != null && !existing.isCompletedExceptionally()) return existing;
    jobs.entrySet().removeIf(e -> e.getValue().isCompletedExceptionally());
    while (jobs.size() >= 8) {
      var victim = jobs.entrySet().stream().filter(e -> e.getValue().isDone()).findFirst();
      if (victim.isEmpty()) break;
      jobs.remove(victim.get().getKey());
    }
    if (jobs.size() >= 16)
      return CompletableFuture.failedFuture(new IOException("Analysis is busy; try again shortly"));
    var result =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                var automatic = analyze(name, copy);
                if (corrected) {
                  try {
                    var saved = timelines.load(name, automatic.durationMs());
                    if (saved != null) return automatic.withHits(saved.hits);
                  } catch (IOException invalidAnnotation) {
                    /* Keep the saved file for recovery; editor reports the error. */
                  }
                }
                return automatic;
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            },
            worker);
    jobs.put(key, result);
    return result;
  }

  private <T> CompletableFuture<T> task(Callable<T> operation) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return operation.call();
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        worker);
  }

  public CompletableFuture<HitTimeline> timeline(
      String name, CelebrationEditSettings settings, float pitch) {
    var copy = ConfigStore.copy(settings, CelebrationEditSettings.class);
    return requestAutomatic(name, copy, pitch)
        .thenApplyAsync(
            automatic -> {
              try {
                var saved = timelines.load(name, automatic.durationMs());
                return saved == null ? timelines.create(name, automatic, copy, pitch) : saved;
              } catch (IOException e) {
                throw new CompletionException(e);
              }
            },
            worker);
  }

  public CompletableFuture<Void> saveTimeline(String name, HitTimeline document) {
    var copy = document.copy();
    return task(
        () -> {
          timelines.save(name, copy);
          synchronized (this) {
            jobs.clear();
          }
          return null;
        });
  }

  public CompletableFuture<HitTimeline> importTimeline(Path path, String name, long duration) {
    return task(() -> timelines.importFile(path, name, duration));
  }

  public CompletableFuture<Path> exportTimeline(Path path, String name, HitTimeline document) {
    var copy = document.copy();
    return task(() -> timelines.exportFile(path, name, copy));
  }

  private Path safeFile(String name) throws IOException {
    Path root = sounds.toRealPath(), file = root.resolve(name).normalize().toRealPath();
    if (!file.startsWith(root)
        || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")
        || Files.size(file) > 32L * 1024 * 1024)
      throw new IOException("Choose an MP3 inside the sounds folder, up to 32 MB");
    return file;
  }

  private SongAnalysis analyze(String name, CelebrationEditSettings settings) throws Exception {
    Path file = safeFile(name);
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(file)) {
      byte[] data = new byte[16384];
      for (int n; (n = input.read(data)) != -1; ) hash.update(data, 0, n);
    }
    hash.update(
        (VERSION
                + "|"
                + settings.bassDetectionMode
                + "|"
                + settings.analysisHz
                + "|"
                + settings.bassLowHz
                + "|"
                + settings.bassHighHz
                + "|"
                + settings.bassThreshold
                + "|"
                + settings.beatCooldownMs)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Path target = cache.resolve(HexFormat.of().formatHex(hash.digest()) + ".bin");
    try {
      if (!name.startsWith(".shared/") && Files.isRegularFile(target)) return load(target);
    } catch (IOException | RuntimeException ignored) {
    }
    var features = new BassFeatures(settings);
    try (var input = new BufferedInputStream(Files.newInputStream(file))) {
      var stream = new Bitstream(input);
      var decoder = new Decoder();
      try {
        Header header;
        while ((header = stream.readFrame()) != null) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
          SampleBuffer pcm = (SampleBuffer) decoder.decodeFrame(header, stream);
          features.accept(
              pcm.getBuffer(),
              0,
              pcm.getBufferLength(),
              pcm.getSampleFrequency(),
              pcm.getChannelCount());
          stream.closeFrame();
          if (features.durationMs() > 3600000)
            throw new IOException("Song must be at most one hour");
        }
      } finally {
        stream.close();
      }
    }
    if (features.count() == 0) throw new IOException("No audio could be decoded from this MP3");
    var result = SongAnalysis.from(features, settings);
    // Cache failures never prevent playback or analysis results.
    try {
      if (name.startsWith(".shared/")) return result;
      Files.createDirectories(cache);
      Path tmp = Files.createTempFile(cache, "analysis-", ".tmp");
      try {
        save(tmp, result);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(tmp);
      }
      try (var files = Files.list(cache)) {
        var all =
            files
                .filter(p -> p.toString().endsWith(".bin"))
                .sorted(
                    Comparator.comparingLong(
                        p -> {
                          try {
                            return Files.getLastModifiedTime(p).toMillis();
                          } catch (IOException e) {
                            return 0;
                          }
                        }))
                .toList();
        long bytes = 0;
        for (var p : all) bytes += Files.size(p);
        for (int i = 0; i < all.size() && (all.size() - i > 24 || bytes > 64L * 1024 * 1024); i++) {
          bytes -= Files.size(all.get(i));
          Files.deleteIfExists(all.get(i));
        }
      }
    } catch (IOException ignored) {
    }
    return result;
  }

  private static void save(Path file, SongAnalysis a) throws IOException {
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
      out.writeInt(VERSION);
      out.writeLong(a.durationMs());
      out.writeDouble(a.stepMs());
      out.writeLong(a.originMs());
      out.writeInt(a.energy().length);
      for (float v : a.energy()) out.writeFloat(v);
      for (float v : a.flux()) out.writeFloat(v);
      out.writeInt(a.hits().size());
      for (var h : a.hits()) {
        out.writeLong(h.timeMs());
        out.writeFloat(h.strength());
      }
    }
  }

  private static SongAnalysis load(Path file) throws IOException {
    if (Files.size(file) > 16L * 1024 * 1024) throw new IOException("Invalid analysis cache");
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      if (in.readInt() != VERSION) throw new IOException("Old analysis");
      long duration = in.readLong();
      double step = in.readDouble();
      long origin = in.readLong();
      int n = in.readInt();
      if (duration <= 0
          || duration > 3600000
          || !Double.isFinite(step)
          || step < 4
          || step > 21
          || n < 1
          || n > MAX_FRAMES) throw new IOException("Invalid analysis");
      float[] energy = new float[n], flux = new float[n];
      for (int i = 0; i < n; i++) energy[i] = finite(in.readFloat());
      for (int i = 0; i < n; i++) flux[i] = finite(in.readFloat());
      int count = in.readInt();
      if (count < 0 || count > n) throw new IOException("Invalid hit count");
      var hits = new ArrayList<SongAnalysis.Hit>();
      long previous = -1;
      for (int i = 0; i < count; i++) {
        long time = in.readLong();
        float strength = finite(in.readFloat());
        if (time < previous || time > duration) throw new IOException("Invalid hit time");
        hits.add(new SongAnalysis.Hit(time, strength));
        previous = time;
      }
      return new SongAnalysis(duration, step, origin, energy, flux, hits);
    }
  }

  private static float finite(float v) throws IOException {
    if (!Float.isFinite(v) || v < 0) throw new IOException("Invalid analysis value");
    return v;
  }

  @Override
  public synchronized void close() {
    worker.shutdownNow();
    for (var f : jobs.values()) f.cancel(true);
    jobs.clear();
  }
}
