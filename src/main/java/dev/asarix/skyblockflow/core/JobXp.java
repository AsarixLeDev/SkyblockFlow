package dev.asarix.skyblockflow.core;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Excalia season 3 wiki curves, with inferred late-level adjustments for three jobs. */
public final class JobXp {
  public static final Map<String, String> NAMES = new LinkedHashMap<>();

  static {
    NAMES.put("mineur", "Mineur");
    NAMES.put("bucheron", "Bûcheron");
    NAMES.put("fermier", "Fermier");
    NAMES.put("chasseur", "Chasseur");
    NAMES.put("artisan", "Artisan");
    NAMES.put("aventurier", "Aventurier");
    NAMES.put("pecheur", "Pêcheur");
  }

  private static final String NUMBER = "([0-9][0-9 .,']*)";
  private static final String XP_TOTAL =
      "(?:niveau|niv\\.)\\s*(\\d{1,3})(?!\\d)[^0-9]*"
          + NUMBER + "\\s*/\\s*" + NUMBER + "\\s*xp(?:\\s*\\(\\s*\\+\\s*" + NUMBER + "\\s*\\))?";
  private static final Pattern BAR = Pattern.compile(
      "(?<![a-z])(mineur|bucheron|fermier|chasseur|artisan|aventurier|pecheur|peche)\\b[^0-9]*"
          + XP_TOTAL);
  private static final Pattern ICON_BAR = Pattern.compile("([\uf730-\uf736])[^a-z0-9]*" + XP_TOTAL);
  private static final Pattern FISHING_PREFIX = Pattern.compile("^\\s*\\[peche]\\s*");
  private static final Pattern FISHING_TOTAL = Pattern.compile(XP_TOTAL);
  private static final Pattern XP_GAIN = Pattern.compile("\\+\\s*" + NUMBER + "\\s*xp");

  public record Reading(String job, int level, double xp, double required, double gain) {}

  /**
   * Labels verified against Excalia's downloaded Oraxen font atlas, November 11, 2026. These are
   * whole-word images, not letters. Decode before Unicode normalization.
   */
  public static String decodeGlyphs(String text) {
    StringBuilder result = new StringBuilder();
    for (int cp : text.codePoints().toArray()) {
      String word =
          switch (cp) {
            case 0xf740 -> "mineur";
            case 0xf741 -> "bucheron";
            case 0xf742 -> "fermier";
            case 0xf743 -> "chasseur";
            case 0xf744 -> "artisan";
            case 0xf745 -> "aventurier";
            case 0xf746 -> "niveau";
            case 0xf747 -> "xp";
            case 0xf749 -> "pecheur";
            default -> null;
          };
      if (word == null) result.appendCodePoint(cp);
      else result.append(' ').append(word).append(' ');
    }
    return result.toString();
  }

  public static String normalize(String text) {
    // Decode before NFKD, which would decompose the server's small-cap Q (ǫ) to O.
    String result = ServerText.regularLetters(decodeGlyphs(text).replaceAll("§[0-9a-fk-orxA-FK-ORX]", ""));
    return Normalizer.normalize(result, Normalizer.Form.NFKD)
        .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
  }

  public static Reading parse(String text) {
    if (text == null || text.length() > 4096) return null;
    String plain = normalize(text).replaceAll("\\p{Cf}", "");
    Matcher m = BAR.matcher(plain);
    if (m.find()) {
      String label = m.group(1);
      return reading(label.equals("peche") ? "pecheur" : label, m, 2, 0);
    }
    m = ICON_BAR.matcher(plain);
    if (!m.find()) return null;
    // Excalia's Oraxen atlas also has standalone icons without the whole-word label.
    String job = switch (m.group(1).charAt(0)) {
      case 0xf730 -> "mineur";
      case 0xf731 -> "bucheron";
      case 0xf732 -> "fermier";
      case 0xf733 -> "chasseur";
      case 0xf734 -> "artisan";
      case 0xf735 -> "aventurier";
      case 0xf736 -> "pecheur";
      default -> throw new IllegalArgumentException("Invalid job icon");
    };
    return reading(job, m, 2, 0);
  }

  public static Reading fishingMessage(String text) {
    if (text == null || text.length() > 4096 || !text.startsWith("[")) return null;
    String plain = normalize(text).replaceAll("\\p{Cf}", "");
    Matcher prefix = FISHING_PREFIX.matcher(plain);
    if (!prefix.find()) return null;
    Matcher total = FISHING_TOTAL.matcher(plain).region(prefix.end(), plain.length());
    if (!total.find()) return null;
    Matcher gain = XP_GAIN.matcher(plain).region(prefix.end(), plain.length());
    double statedGain = 0;
    if (gain.find()) {
      try {
        statedGain = number(gain.group(1));
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return reading("pecheur", total, 1, statedGain);
  }

  private static Reading reading(String job, Matcher m, int start, double statedGain) {
    try {
      int level = Integer.parseInt(m.group(start));
      double xp = number(m.group(start + 1)), required = number(m.group(start + 2));
      double gain = m.group(start + 3) == null ? statedGain : number(m.group(start + 3));
      if (level > 200
          || required < 0
          || (required == 0 && level != 200)
          || xp < 0
          || (level < 200 && xp > required + .01)
          || !Double.isFinite(xp + required + gain)
          || required > 1e12
          || gain > 1e12) return null;
      return new Reading(job, level, xp, required, gain);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static double number(String s) {
    s = s.strip().replace(" ", "").replace("'", "");
    // With both separators, the rightmost is decimal; otherwise comma is decimal.
    if (s.contains(",") && s.contains(".")) {
      if (s.lastIndexOf(',') > s.lastIndexOf('.')) s = s.replace(".", "").replace(',', '.');
      else s = s.replace(",", "");
    } else s = s.replace(',', '.');
    return Double.parseDouble(s);
  }

  public static long requirement(String job, int level) {
    if (level < 0 || level >= 200) return 0;
    // Fishing has its own published curve: /wiki/s3-peche/le-niveau-de-pecheur-1-200.
    if (job.equals("pecheur")) return (long) Math.floor(100 * Math.pow(level, 1.6));
    if (job.equals("artisan")) return (long) Math.floor(10 + .033153 * Math.pow(level, 3));
    double a, b, c;
    switch (job) {
      case "mineur" -> {
        a = 1.9343;
        b = 2765;
        c = 56.0057;
      }
      case "bucheron" -> {
        a = 1.9345;
        b = 2765;
        c = 56.0053;
      }
      case "fermier" -> {
        a = 1.936;
        b = 2767;
        c = 56.0015;
      }
      case "chasseur" -> {
        a = 1.9345;
        b = 2765;
        c = 1.1876;
      }
      case "aventurier" -> {
        a = 1.9336;
        b = 2764;
        c = 2.1625;
      }
      default -> {
        return -1;
      }
    }
    double xp =
        1 + a * Math.pow(level, 1.7281) + (level >= 100 ? b + c * Math.pow(level - 100, 2) : 0);
    if (level > 150) {
      xp += inferredCoefficient(job) * Math.pow(level - 150, 4);
    }
    return (long) Math.floor(xp);
  }

  // Inferred on 2026-11-12. The wiki omits this term; unobserved levels are estimates.
  static double inferredCoefficient(String job) {
    return switch (job) {
      case "chasseur" -> .0551; // Levels 183, 184, 185: 92006, 100641, 110043 XP.
      case "bucheron" -> .214793; // Level 176: 439098 XP. Retain all six decimal places.
      case "fermier" -> .1650; // Level 154: 177783 XP. One observation allows a wider range.
      default -> 0;
    };
  }

  // Prefix sums share the same level boundaries as the tracker and its ETA calculation.
  private static final class Curves {
    static final java.util.concurrent.CompletableFuture<Map<String,long[]>> TOTALS =
        java.util.concurrent.CompletableFuture.supplyAsync(Curves::build);
    static Map<String,long[]> build() {
      var result = new HashMap<String,long[]>();
      for (var id : NAMES.keySet()) {
        var values = new long[201];
        for (int i=0; i<200; i++) values[i+1] = values[i] + requirement(id,i);
        result.put(id,values);
      }
      return result;
    }
  }
  public static boolean curvesReady() { return Curves.TOTALS.isDone(); }
  public static long totalAt(String id, int level) {
    return Curves.TOTALS.join().get(id)[Math.clamp(level,0,200)];
  }
  public static double levelProgress(String job, int level, double raw) {
    if (level == 200) return 0;
    long required = requirement(job, level);
    if (raw <= required) return raw;
    double converted = raw - totalAt(job, level);
    return converted >= 0 && converted <= required ? converted : Double.NaN;
  }

  public static int milestone(int level) {
    return Math.min(200, (level / 5 + 1) * 5);
  }

  public static double remaining(String job, int level, double xp, double required, int target) {
    if (level >= target) return 0;
    double remaining = Math.max(0, required - xp);
    for (int l = level + 1; l < Math.min(200, target); l++) {
      long cost = requirement(job, l);
      if (cost < 0) return Double.NaN;
      remaining += cost;
    }
    return remaining;
  }

  public static String eta(double remaining, double perMinute) {
    if (!Double.isFinite(remaining)) return "Curve unavailable";
    if (remaining <= 0) return "00:00:00";
    if (!Double.isFinite(perMinute) || perMinute <= 0) return "Collecting XP…";
    return duration((long) Math.ceil(remaining / perMinute * 60000));
  }

  public static String duration(long millis) {
    long seconds = Math.max(0, millis) / 1000;
    return String.format(
        Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
  }

  public static String amount(double xp) {
    return Numbers.fixed(xp, 2);
  }

  public static String name(String job) {
    return NAMES.getOrDefault(job, job);
  }
}
