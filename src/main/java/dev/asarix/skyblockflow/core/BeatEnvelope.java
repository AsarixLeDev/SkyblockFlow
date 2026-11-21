package dev.asarix.skyblockflow.core;

/** Render-clock attack/release envelope. Retriggers never postpone an unfinished attack. */
public final class BeatEnvelope {
  private final long attackMs, releaseMs;
  private long start = -1, peak, release;
  private double from;
  public BeatEnvelope(long attackMs, long releaseMs) {
    this.attackMs = attackMs; this.releaseMs = releaseMs;
  }
  public void trigger(long now) {
    if (start >= 0 && now < peak) { release = Math.max(release, now); return; }
    from = value(now); start = now; peak = now + attackMs; release = peak;
  }
  public double value(long now) {
    if (start < 0 || now < start) return 0;
    if (now < peak) return from + (1 - from) * ease((now - start) / (double) attackMs);
    if (now <= release) return 1;
    return releaseMs == 0 ? 0 : 1 - ease((now - release) / (double) releaseMs);
  }
  public static double ease(double t) {
    t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t);
  }
  public static float zoomFov(float fov, double zoom) {
    return (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(fov) / 2) / Math.max(1, zoom)));
  }
}
