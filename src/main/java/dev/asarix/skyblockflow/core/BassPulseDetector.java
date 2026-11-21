package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CelebrationEditSettings;
import java.util.*;

/** Envelope onsets for bands with too few FFT bins for neighboring-bin spectral comparisons. */
final class BassPulseDetector {
  private float[] smooth = new float[1024], flux = new float[1024];
  private int smoothed, scored, checked;
  private boolean armed = true;
  private float crest;
  private long lastHit = -100000;

  float[] fluxes(int count) {
    return Arrays.copyOf(flux, count);
  }

  List<SongAnalysis.Hit> update(
      BassFeatures features, CelebrationEditSettings s, boolean end, long spacing) {
    int count = features.count();
    if (smooth.length < count) {
      smooth = Arrays.copyOf(smooth, Math.max(count, smooth.length * 2));
      flux = Arrays.copyOf(flux, smooth.length);
    }
    double step = features.stepMs();
    int half = Math.max(1, (int) Math.round(10 / step));
    int before = Math.max(1, (int) Math.round(800 / step));
    int ahead = Math.max(1, (int) Math.round(80 / step));
    int near = Math.max(1, (int) Math.round(20 / step));
    float[] raw = features.rawEnergy();
    int smoothEnd = end ? count : Math.max(0, count - half);
    for (; smoothed < smoothEnd; smoothed++) {
      int start = Math.max(0, smoothed - half), stop = Math.min(count, smoothed + half + 1);
      double total = 0;
      for (int j = start; j < stop; j++) total += raw[j];
      smooth[smoothed] = (float) (total / (stop - start));
    }
    int scoreEnd = end ? count : Math.max(0, smoothed - ahead);
    for (; scored < scoreEnd; scored++) {
      if (scored == 0) continue;
      float localPeak = .000001f;
      for (int j = Math.max(0, scored - before); j < Math.min(smoothed, scored + ahead + 1); j++)
        localPeak = Math.max(localPeak, smooth[j]);
      // The threshold uses the same 15 ms time normalization as spectral flux.
      flux[scored] = Math.max(0, smooth[scored] - smooth[scored - 1]) / localPeak / 2;
    }
    var result = new ArrayList<SongAnalysis.Hit>();
    int checkEnd = end ? count : Math.max(0, scored - near);
    for (; checked < checkEnd; checked++) {
      int i = checked;
      crest = Math.max(crest, smooth[i]);
      // A sustained/warbling note must lose 45% of its envelope before it can retrigger.
      if (smooth[i] <= crest * .55f) {
        armed = true;
        crest = smooth[i];
      }
      if (!armed
          || i == 0
          || !features.eligible(i)
          || raw[i] <= raw[i - 1]
          || smooth[i] < Math.max(.000001f, features.peak() * .03f)) continue;
      float strength = SongAnalysis.strength(flux[i], step, features.peak());
      if (strength < s.bassThreshold) continue;
      boolean maximum = true;
      for (int j = Math.max(0, i - near); j < Math.min(scored, i + near + 1); j++)
        if (j != i && (flux[j] > flux[i] || j < i && flux[j] == flux[i])) {
          maximum = false;
          break;
        }
      long time = features.time(i);
      if (!maximum || time > features.durationMs() || time - lastHit < spacing) continue;
      result.add(new SongAnalysis.Hit(time, strength));
      lastHit = time;
      armed = false;
      crest = smooth[i];
    }
    return result;
  }
}
