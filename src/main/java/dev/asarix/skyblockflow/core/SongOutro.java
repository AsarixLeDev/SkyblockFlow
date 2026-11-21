package dev.asarix.skyblockflow.core;

/** Smooth return after the final annotated/detected hit, with time measured at playback speed. */
public final class SongOutro {
  public record Timing(long startMs, long endMs) {
    public double progress(long ageMs) {
      return BeatEnvelope.ease((ageMs - startMs) / (double) Math.max(1, endMs - startMs));
    }
  }

  public static Timing fallback(long celebrationDuration) {
    return new Timing(Math.max(0, celebrationDuration - 650), celebrationDuration);
  }

  public static Timing timing(long songDuration, long lastHit, long celebrationDuration) {
    // A deliberately shortened celebration still gets a short exit before its cutoff.
    if (songDuration > celebrationDuration + 50 || lastHit < 0)
      return fallback(celebrationDuration);
    // Leave a short visual tail if the final hit is right at the audio's end.
    return new Timing(lastHit, Math.max(songDuration, lastHit + 650));
  }

  public static float angle(float from, float to, double progress) {
    double delta = ((to - from) % 360 + 540) % 360 - 180;
    return (float) (from + delta * progress);
  }
}
