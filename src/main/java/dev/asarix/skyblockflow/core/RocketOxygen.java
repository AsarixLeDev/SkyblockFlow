package dev.asarix.skyblockflow.core;

import java.util.regex.*;

/** Excalia Saison 3 documents a mm:ss oxygen boss bar and a 60-second launch reserve. */
public final class RocketOxygen {
  public static final int LAUNCH_RESERVE = 60;
  private static final Pattern GAUGE =
      Pattern.compile("\\boxygene\\s*[:·]?\\s*(\\d{1,3}):([0-5]\\d)\\s*/\\s*\\d{1,3}:[0-5]\\d");

  public static int seconds(String text) {
    var m = GAUGE.matcher(JobXp.normalize(text));
    return m.find() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : -1;
  }

  public static boolean blocked(String text) {
    var s = JobXp.normalize(text).replace('’', '\'');
    return (s.contains("sans oxygene") || s.contains("pas d'oxygene la-bas"))
        && s.contains("combinaison complete")
        && s.contains("reservoir rempli");
  }

  public static boolean denial(String text) {
    if (!text.contains("oxy") && !text.contains("Oxy") && !text.contains("ᴏ")) return false;
    var s = JobXp.normalize(text).replace('’', '\'').strip();
    return s.matches("^[^a-z0-9]{0,6}pas d'oxygene la-bas.*") && blocked(s);
  }

  public static boolean rocket(String text) {
    var s = JobXp.normalize(text).strip();
    return s.matches("(?s).*(?:\\bfusee(?: excalia)? (?:ii?|[12])\\b|^fusee$).*");
  }
}
