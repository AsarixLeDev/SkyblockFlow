package dev.asarix.skyblockflow.core;

import java.util.Random;
import dev.asarix.skyblockflow.core.Models.CelebrationEditSettings;

/** Beat-cut orbit shots. Coordinates are relative to the player's body center. */
public final class OrbitShots {
  public record Shot(double azimuth, double elevation, double distance) {
    public double x() { return Math.sin(Math.toRadians(azimuth)) * Math.cos(Math.toRadians(elevation)) * distance; }
    public double y() { return Math.sin(Math.toRadians(elevation)) * distance; }
    public double z() { return Math.cos(Math.toRadians(azimuth)) * Math.cos(Math.toRadians(elevation)) * distance; }
  }
  private final CelebrationEditSettings settings;
  private final Random random;
  private Shot from, to;
  private long changedAt;
  private int cuts;
  public OrbitShots(CelebrationEditSettings settings, double facing, long now, Random random) {
    this.settings = settings; this.random = random;
    from = to = new Shot(facing + 25, between(settings.cameraMinElevation, settings.cameraMaxElevation),
        between(settings.cameraMinDistance, settings.cameraMaxDistance));
    changedAt = now;
  }
  public int cuts() { return cuts; }
  private double between(double min, double max) { return min + random.nextDouble() * (max - min); }
  public void cut(long now) {
    from = sample(now);
    // Every cut changes side by at least 70 degrees, including when a beat interrupts a blend.
    double angle = from.azimuth + (random.nextBoolean() ? 1 : -1) * between(70, 150);
    to = new Shot(angle, between(settings.cameraMinElevation, settings.cameraMaxElevation),
        between(settings.cameraMinDistance, settings.cameraMaxDistance));
    changedAt = now; cuts++;
  }
  public Shot sample(long now) {
    double elapsed = Math.max(0, now - changedAt);
    double blend = settings.cameraBlendMs == 0 ? 1 : BeatEnvelope.ease(elapsed / settings.cameraBlendMs);
    return new Shot(from.azimuth + (to.azimuth - from.azimuth) * blend + elapsed * settings.cameraOrbitSpeed / 1000,
        from.elevation + (to.elevation - from.elevation) * blend,
        from.distance + (to.distance - from.distance) * blend);
  }
}
