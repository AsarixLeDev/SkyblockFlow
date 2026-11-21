package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CelebrationEditSettings;
import java.util.*;

/**
 * Compact, immutable analysis of the original song; timestamps are before playback pitch changes.
 */
public record SongAnalysis(
    long durationMs, double stepMs, long originMs, float[] energy, float[] flux, List<Hit> hits) {
  public record Hit(long timeMs, float strength) {}

  public SongAnalysis {
    energy = energy.clone();
    flux = flux.clone();
    hits = List.copyOf(hits);
  }

  public static SongAnalysis from(BassFeatures f, CelebrationEditSettings s) {
    f.finish();
    float[] energy = f.energies(), flux = f.fluxes();
    if (f.narrowBand()) {
      var detector = new BassPulseDetector();
      var hits = detector.update(f, s, true, s.beatCooldownMs);
      return new SongAnalysis(
          f.durationMs(), f.stepMs(), f.originMs(), energy, detector.fluxes(f.count()), hits);
    }
    var hits = new ArrayList<Hit>();
    for (int i = 0; i < flux.length; i++)
      if (isPeak(energy, flux, i, f.stepMs(), f.peak(), s.bassThreshold)) {
        var hit =
            new Hit(Math.min(f.durationMs(), f.time(i)), strength(flux[i], f.stepMs(), f.peak()));
        if (!hits.isEmpty() && hit.timeMs() - hits.getLast().timeMs() < s.beatCooldownMs) {
          if (hit.strength() > hits.getLast().strength()) hits.set(hits.size() - 1, hit);
        } else hits.add(hit);
      }
    return new SongAnalysis(f.durationMs(), f.stepMs(), f.originMs(), energy, flux, hits);
  }

  public static float strength(float value, double step, float peak) {
    return (float) (value * 15 / step);
  }

  static boolean isPeak(
      float[] energy, float[] flux, int i, double step, float peak, float threshold) {
    if (energy[i] < .000001 || i > 0 && energy[i] <= energy[i - 1]) return false;
    int near = Math.max(1, (int) Math.round(20 / step)),
        history = Math.max(near + 1, (int) Math.round(400 / step));
    // A sudden note cutoff spreads energy sideways in the FFT. Its energy collapses immediately
    // afterwards; a new attack over sustained bass does not. Both paths already wait this
    // lookahead.
    if (i + near < energy.length && energy[i + near] < energy[i] * .35) return false;
    float value = flux[i];
    if (strength(value, step, peak) < threshold) return false;
    for (int j = Math.max(0, i - near); j <= Math.min(flux.length - 1, i + near); j++)
      if (j != i && (flux[j] > value || j < i && flux[j] == value)) return false;
    // Median + median deviation is not dragged upwards by a few loud preceding hits.
    int start = Math.max(0, i - history), end = Math.max(start, i - near);
    if (end == start) return true;
    float[] local = Arrays.copyOfRange(flux, start, end);
    Arrays.sort(local);
    float median = local[local.length / 2];
    for (int j = 0; j < local.length; j++) local[j] = Math.abs(local[j] - median);
    Arrays.sort(local);
    return value > median + 2.5 * local[local.length / 2];
  }

  public SongAnalysis withHits(List<Hit> corrected) {
    return new SongAnalysis(durationMs, stepMs, originMs, energy, flux, corrected);
  }

  public long firstHitMs() {
    return hits.isEmpty() ? -1 : hits.getFirst().timeMs();
  }

  public long longestGapMs() {
    long max = 0;
    for (int i = 1; i < hits.size(); i++)
      max = Math.max(max, hits.get(i).timeMs() - hits.get(i - 1).timeMs());
    return max;
  }

  public long shortestGapMs() {
    long min = Long.MAX_VALUE;
    for (int i = 1; i < hits.size(); i++)
      min = Math.min(min, hits.get(i).timeMs() - hits.get(i - 1).timeMs());
    return min == Long.MAX_VALUE ? 0 : min;
  }

  public long medianGapMs() {
    if (hits.size() < 2) return 0;
    long[] gaps = new long[hits.size() - 1];
    for (int i = 0; i < gaps.length; i++) gaps[i] = hits.get(i + 1).timeMs() - hits.get(i).timeMs();
    Arrays.sort(gaps);
    return gaps[gaps.length / 2];
  }

  public float energyAt(long timeMs) {
    if (energy.length == 0) return 0;
    int i = (int) Math.round((timeMs - originMs) / stepMs);
    return energy[Math.clamp(i, 0, energy.length - 1)];
  }
}
