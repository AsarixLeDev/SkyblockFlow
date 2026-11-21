package dev.asarix.skyblockflow.core;

import java.util.ArrayDeque;

/** Detects whether the action-bar counter includes the equipped pet bonus, using confirmed gains. */
final class ActionXpCalibration {
  private record Point(double raw, double real, long at, int updates) {}
  private final ArrayDeque<Point> points = new ArrayDeque<>();
  private double raw, real, factor = 1, petBoost = Double.NaN, candidate = Double.NaN;
  private long rawAt, realAt, startedAt;
  private int updates, agreement;
  double factor() { return factor; }
  void reset() { factor = 1; petBoost = Double.NaN; restartWindow(); }
  boolean petMultiplier(double value) {
    double known = Double.isFinite(value) && value >= 1 && value <= 100 ? value : Double.NaN;
    if (Double.compare(known, petBoost) == 0) return false;
    petBoost = known; factor = 1; restartWindow();
    return true;
  }
  void restartWindow() {
    points.clear(); raw = real = 0; rawAt = realAt = startedAt = 0;
    updates = agreement = 0; candidate = Double.NaN;
  }
  void raw(double gain, long now) {
    if (gain > 0 && Double.isFinite(gain)) { raw += gain; rawAt = now; if (startedAt == 0) startedAt = now; }
  }
  void confirmed(double gain, long now) {
    if (gain > 0 && Double.isFinite(gain)) { real += gain; realAt = now; if (startedAt == 0) startedAt = now; updates++; }
  }
  boolean tick(long now) {
    if (!Double.isFinite(petBoost) || petBoost <= 1) return false;
    if (raw <= 0 || real <= 0 || now - rawAt > 1500 || now - realAt > 1500) return false;
    var last = points.peekLast();
    if (last != null && (raw <= last.raw || real <= last.real || now - last.at < 200)) return false;
    // Prefer settled packet pairs. Continuous traffic still receives a sample every half second.
    if (now - Math.max(rawAt, realAt) < 100 && (now - (last == null ? startedAt : last.at) < 500)) return false;
    points.addLast(new Point(raw, real, now, updates));
    while (points.size() > 16) points.removeFirst();
    if (points.size() < 8) return false;
    var first = points.getFirst(); last = points.getLast();
    if (last.updates - first.updates < 6 || last.at - first.at < 1500) return false;
    double meanRaw = 0, meanReal = 0;
    for (var p : points) { meanRaw += p.raw - first.raw; meanReal += p.real - first.real; }
    meanRaw /= points.size(); meanReal /= points.size();
    double xx = 0, yy = 0, xy = 0;
    for (var p : points) {
      double x = p.raw - first.raw - meanRaw, y = p.real - first.real - meanReal;
      xx += x * x; yy += y * y; xy += x * y;
    }
    if (xx <= .000001 || yy <= .000001) return false;
    double slope = xy / xx;
    if (!Double.isFinite(slope) || xy * xy / (xx * yy) < .995) {
      agreement = 0; return false;
    }
    double span = last.real - first.real;
    for (var p : points) {
      double error = Math.abs(p.real - first.real - meanReal - slope * (p.raw - first.raw - meanRaw));
      if (error > Math.max(.03, span * .04)) { agreement = 0; return false; }
    }
    // These are the only two server modes: the bonus is present, or it is missing.
    // Never invent a generic multiplier from unrelated awards or other drift.
    double tolerance = Math.max(.00005, Math.min(.015, (petBoost - 1) / 4));
    double corrected;
    if (Math.abs(slope - 1) <= tolerance) corrected = 1;
    else if (Math.abs(slope / petBoost - 1) <= tolerance) corrected = petBoost;
    else { agreement = 0; return false; }
    if (Double.compare(candidate, corrected) == 0) agreement++;
    else agreement = 1;
    candidate = corrected;
    if (agreement < 3 || Double.compare(corrected, factor) == 0) return false;
    factor = corrected;
    restartWindow();
    return true;
  }
}
