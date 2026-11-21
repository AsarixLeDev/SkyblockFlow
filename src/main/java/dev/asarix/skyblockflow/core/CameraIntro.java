package dev.asarix.skyblockflow.core;

/** Exponential approach with exact endpoints and zero velocity as it joins the normal orbit. */
public final class CameraIntro {
  private CameraIntro() {}

  public static double distance(
      double normal, double far, long ageMs, long durationMs, double curve) {
    return distance(normal, far, ageMs, durationMs, curve, false);
  }

  public static double distance(
      double normal, double far, long ageMs, long durationMs, double curve, boolean accelerates) {
    double t = Math.clamp(ageMs / (double) Math.max(1, durationMs), 0, 1);
    double remaining;
    if (accelerates) {
      double end = .85, denominator = Math.expm1(curve);
      double progress = Math.expm1(curve * t) / denominator;
      if (t > end) {
        double u = (t - end) / (1 - end), p = Math.expm1(curve * end) / denominator;
        double velocity = curve * Math.exp(curve * end) / denominator * (1 - end);
        // Match the accelerating approach, then brake smoothly into the final orbit.
        progress =
            (2 * u * u * u - 3 * u * u + 1) * p
                + (u * u * u - 2 * u * u + u) * velocity
                + (-2 * u * u * u + 3 * u * u);
      }
      remaining = 1 - progress;
    } else {
      remaining = Math.expm1(curve * (1 - t)) / Math.expm1(curve);
      remaining *= 1 - t * t;
    }
    return normal + (Math.max(normal, far) - normal) * Math.clamp(remaining, 0, 1);
  }
}
