package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CelebrationEditSettings;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Audio-clock beat delivery with cached MP3 analysis and a streaming fallback for vanilla sounds.
 */
public final class BassAnalyzer {
  private final CelebrationEditSettings settings;
  private BassFeatures features;
  private BassPulseDetector pulses = new BassPulseDetector();
  private SongAnalysis song;
  private LongSupplier clock;
  private float pitch = 1;
  private final List<SongAnalysis.Hit> live = new ArrayList<>(), delivered = new ArrayList<>();
  private int checked, cursor;
  private long beats, lastLive = -100000, finalPosition;
  private boolean clockStarted, awaiting, previewAnalysis, playbackFailed;
  private volatile long scheduledStartNanos;

  public void schedule(long nanos) {
    scheduledStartNanos = nanos;
  }

  public boolean waitingScheduledStart() {
    return scheduledStartNanos != 0 && !clockStarted();
  }

  public void awaitScheduledStart(java.util.function.BooleanSupplier active)
      throws InterruptedException {
    long remaining;
    while (active.getAsBoolean() && (remaining = scheduledStartNanos - System.nanoTime()) > 0)
      java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(remaining, 20000000));
  }

  public synchronized void expectPlayback() {
    awaiting = true;
  }

  public synchronized boolean playbackFailed() {
    return playbackFailed;
  }

  public synchronized void cancelPlayback() {
    playbackFailed = true;
    scheduledStartNanos = 0;
    awaiting = false;
  }

  public synchronized boolean awaitingPlayback() {
    return awaiting;
  }

  public BassAnalyzer(CelebrationEditSettings settings) {
    this.settings = settings;
    features = new BassFeatures(settings);
  }

  public synchronized void useAnalysis(SongAnalysis song) {
    if (!previewAnalysis) this.song = song;
  }

  public synchronized void usePreviewAnalysis(SongAnalysis song) {
    this.song = song;
    previewAnalysis = true;
    cursor = 0;
    while (cursor < song.hits().size()
        && Math.round(song.hits().get(cursor).timeMs() / pitch) + settings.syncOffsetMs
            < positionMs()) cursor++;
    delivered.clear();
  }

  public synchronized SongOutro.Timing outro(long celebrationDuration) {
    if (song == null || !clockStarted) return SongOutro.fallback(celebrationDuration);
    long last =
        song.hits().isEmpty()
            ? -1
            : Math.max(
                0, Math.round(song.hits().getLast().timeMs() / pitch) + settings.syncOffsetMs);
    return SongOutro.timing(Math.round(song.durationMs() / pitch), last, celebrationDuration);
  }

  public synchronized void beginPlayback(LongSupplier milliseconds, float pitch) {
    clock = milliseconds;
    this.pitch = pitch;
    clockStarted = true;
    awaiting = false;
    finalPosition = 0;
    cursor = 0;
    checked = 0;
    live.clear();
    delivered.clear();
    features = new BassFeatures(settings);
    lastLive = -100000;
    pulses = new BassPulseDetector();
  }

  public synchronized void endPlayback() {
    finalPosition = positionMs();
    clock = null;
  }

  public synchronized boolean clockStarted() {
    return clockStarted;
  }

  public synchronized long positionMs() {
    if (clock != null)
      try {
        finalPosition = Math.max(0, clock.getAsLong());
      } catch (RuntimeException closed) {
      }
    return finalPosition;
  }

  public synchronized void accept(
      short[] pcm, int offset, int length, int sampleRate, int channels) {
    if (song != null) return;
    if (!features.matches(sampleRate, channels)) {
      checked = cursor = 0;
      live.clear();
      lastLive = -100000;
      pulses = new BassPulseDetector();
    }
    features.accept(pcm, offset, length, sampleRate, channels);
    if (features.narrowBand()) {
      live.addAll(
          pulses.update(features, settings, false, Math.round(settings.beatCooldownMs * pitch)));
      if (!clockStarted) finalPosition = features.durationMs();
      return;
    }
    int look = Math.max(1, (int) Math.round(20 / features.stepMs()));
    // Only inspect newly available peaks; no repeated analysis of the entire song.
    float[] e = features.rawEnergy(), f = features.rawFlux();
    for (; checked < features.count() - look; checked++) {
      long time = features.time(checked);
      if (Math.round(time / pitch) - lastLive >= settings.beatCooldownMs
          && SongAnalysis.isPeak(
              e, f, checked, features.stepMs(), features.peak(), settings.bassThreshold)) {
        live.add(
            new SongAnalysis.Hit(
                time, SongAnalysis.strength(f[checked], features.stepMs(), features.peak())));
        lastLive = Math.round(time / pitch);
      }
    }
    if (!clockStarted) finalPosition = features.durationMs();
  }

  private void advance() {
    if (awaiting || song != null && !clockStarted) return;
    List<SongAnalysis.Hit> source = song == null ? live : song.hits();
    long now = positionMs();
    while (cursor < source.size()) {
      var hit = source.get(cursor);
      // A positive offset deliberately delays visuals. Timestamps are converted from original song
      // time.
      long time = Math.max(0, Math.round(hit.timeMs() / pitch) + settings.syncOffsetMs);
      if (time > now) break;
      cursor++;
      beats++;
      delivered.add(new SongAnalysis.Hit(time, hit.strength()));
    }
  }

  public synchronized List<SongAnalysis.Hit> drainHits() {
    advance();
    var result = List.copyOf(delivered);
    delivered.clear();
    return result;
  }

  public synchronized long beats() {
    advance();
    return beats;
  }

  public synchronized float energy() {
    return song == null
        ? features.count() == 0 ? 0 : features.energy(features.count() - 1)
        : song.energyAt(Math.round(positionMs() * pitch));
  }

  public synchronized float delta() {
    return features.count() == 0 ? 0 : features.flux(features.count() - 1);
  }
}
