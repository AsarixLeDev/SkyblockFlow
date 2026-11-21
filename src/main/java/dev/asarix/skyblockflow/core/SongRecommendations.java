package dev.asarix.skyblockflow.core;

import java.util.Locale;

/** Suggestions are explicit draft edits, never silently applied to saved preferences. */
public final class SongRecommendations {
  public record Value(String text, String reason) {}

  private static Value value(double v, String reason) {
    return new Value(
        String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", ""), reason);
  }

  public static Value forKey(String key, SongAnalysis song, float pitch, long offset) {
    double speed = Math.max(.5, pitch);
    double longest = song == null ? 0 : song.longestGapMs() / speed,
        shortest = song == null ? 0 : song.shortestGapMs() / speed;
    double median = song == null ? 0 : song.medianGapMs() / speed;
    return switch (key) {
      case "duration" ->
          song == null
              ? null
              : value(
                  Math.max(1, song.durationMs() / speed / 1000),
                  "Full decoded song length, adjusted for playback pitch.");
      case "release" ->
          value(
              longest > 0 ? Math.min(7200, Math.ceil(.8 * longest) / 1000) : .5,
              longest > 0
                  ? "80% of the longest interval between detected hits ("
                      + Math.round(longest)
                      + " ms). The short punch still marks each hit."
                  : "Fallback: 0.5 s. Analyze a song with at least two hits for a song-based"
                      + " value.");
      case "fadeIn" ->
          value(
              song != null && song.firstHitMs() >= 0
                  ? Math.max(0, song.firstHitMs() / speed + offset) / 1000
                  : .3,
              song != null && song.firstHitMs() >= 0
                  ? "Finish fading in at the first detected bass hit, including the sync offset."
                  : "Fallback: 0.3 s; no detected first hit is available.");
      case "fadeOut" ->
          value(
              song != null && !song.hits().isEmpty()
                  ? Math.max(
                          0, (song.durationMs() - song.hits().getLast().timeMs()) / speed - offset)
                      / 1000
                  : .65,
              song != null && !song.hits().isEmpty()
                  ? "Fade from the last detected hit to the end of the song. Use song duration for"
                      + " this timing."
                  : "Fallback: a gentle 0.65 s exit.");
      case "cooldown" ->
          value(
              shortest > 0 ? Math.clamp(shortest * .45, 20, 5000) / 1000 : .08,
              shortest > 0
                  ? "45% of the shortest detected interval, leaving room for nearby hits."
                  : "80 ms is a starting point for fast beats. Lower it if distinct hits are"
                      + " missing.");
      case "attack" ->
          value(
              shortest > 0 ? Math.min(50, shortest * .1) / 1000 : .03,
              "A quick rise keeps nearby hits distinct.");
      case "punchTime" ->
          value(
              shortest > 0 ? Math.clamp(shortest * .6, 20, 150) / 1000 : .12,
              "A short independent pulse, shorter than the closest detected hits.");
      case "precision" ->
          value(100, "100 measurements per second: a 10 ms step with overlapping audio windows.");
      case "sync" ->
          value(
              0, "Start aligned with the audio playback clock. Adjust by ear for device latency.");
      case "low" ->
          value(
              35,
              "A broad bass band is a useful starting point; very low-only bands can miss kick"
                  + " attacks.");
      case "high" -> value(180, "Include low kick harmonics as well as sub-bass.");
      case "threshold" ->
          value(
              .035,
              "Normalized onset threshold. Lower detects weaker hits; higher rejects small"
                  + " changes.");
      case "speed" ->
          value(
              median > 0 ? Math.clamp(1000 / median, .1, 20) : 2,
              "One shake cycle per typical hit interval, when available.");
      case "volume" -> value(1, "Neutral gain; extra loudness does not improve detection.");
      case "zoom" -> value(1.25, "A restrained base zoom leaves space for bass pulses.");
      case "shake" ->
          value(.25, "A gentle starting point; motion intensity is a visual preference.");
      case "roll" -> value(.12, "A gentle starting point for camera roll.");
      case "boost" -> value(2, "Double motion intensity at the peak.");
      case "punchStrength" ->
          value(.35, "A visible accent without overwhelming the longer motion envelope.");
      case "camMin" -> value(4.5, "A readable view of the player.");
      case "camMax" -> value(6.5, "A little distance variation between shots.");
      case "camLow" -> value(5, "Keep the camera above the horizon.");
      case "camHigh" -> value(35, "Moderate angle variation.");
      case "camSpeed" -> value(8, "Gentle drift between cuts.");
      case "introTime" ->
          value(
              song != null && song.firstHitMs() > 0
                  ? Math.clamp((song.firstHitMs() / speed + offset) / 1000, .2, 30)
                  : 2.5,
              "Aim to arrive at the first bass hit; clamped to 0.2–30 seconds. Fallback: 2.5"
                  + " seconds.");
      case "introDistance" ->
          value(48, "A distant establishing view; walls and loaded terrain can limit it.");
      case "introCurve" -> value(3, "A clear exponential speed change with a smooth arrival.");
      case "camBlend" -> value(0, "An instant cut emphasizes each detected hit.");
      case "tint" -> value(.08, "A subtle color grade keeps the world readable.");
      case "vignette" -> value(.22, "Soft darkening around the edges.");
      case "pulse" -> value(.06, "A restrained light accent.");
      case "bars" -> value(.08, "Small cinematic bars.");
      case "soundVolume" -> value(.8, "Comfortable starting volume; adjust for your audio setup.");
      case "pitch" -> value(1, "Original song speed and pitch.");
      default -> null;
    };
  }
}
