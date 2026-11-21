package dev.asarix.skyblockflow.core;

import java.util.*;

/** Excalia Saison 3 pet rules, verified against the wiki on 2026-11-14. */
public final class PetCatalog {
  private PetCatalog() {}
  public record Food(String item, int seconds) {}
  public static final Map<String, String> NAMES = new LinkedHashMap<>();
  static {
    NAMES.put("pepite", "Pépite"); NAMES.put("copeau", "Copeau"); NAMES.put("epi", "Épi");
    NAMES.put("croc", "Croc"); NAMES.put("bulle", "Bulle"); NAMES.put("osselet", "Osselet");
    for (String name : List.of("Mars", "Terre", "Vénus", "Pluton", "Uranus", "Soleil", "Saturne", "Jupiter"))
      NAMES.put(JobXp.normalize(name), name);
  }
  public static String type(String text) {
    String normalized = JobXp.normalize(text);
    for (String id : NAMES.keySet())
      if (java.util.regex.Pattern.compile("(?<![a-z])" + id + "(?![a-z])").matcher(normalized).find()) return id;
    return "";
  }
  public static List<Food> foods(String type) {
    return switch (type) {
      case "pepite", "mars" -> List.of(new Food("cooked_beef", 50), new Food("bread", 35));
      case "copeau", "venus" -> List.of(new Food("bread", 50), new Food("apple", 35));
      case "epi", "terre" -> List.of(new Food("baked_potato", 50), new Food("bread", 35));
      case "croc", "pluton" -> List.of(new Food("cooked_beef", 50), new Food("cooked_porkchop", 35));
      case "bulle", "uranus" -> List.of(new Food("cooked_salmon", 900), new Food("cooked_cod", 420));
      case "osselet", "jupiter" -> List.of(new Food("golden_carrot", 120), new Food("cooked_beef", 35));
      case "soleil" -> List.of(new Food("golden_carrot", 120), new Food("apple", 35));
      case "saturne" -> List.of(new Food("bread", 50), new Food("cooked_mutton", 35));
      default -> List.of();
    };
  }
  public static boolean boosts(String type, int level, String job) {
    if (level < 1) return false;
    return switch (type) {
      case "pepite", "mars" -> job.equals("mineur");
      case "copeau", "venus" -> job.equals("bucheron");
      case "epi", "terre" -> job.equals("fermier");
      case "croc", "pluton" -> job.equals("chasseur");
      case "bulle", "uranus" -> job.equals("pecheur");
      case "soleil" -> job.equals("bucheron") || level >= 25 && job.equals("fermier");
      case "saturne" -> job.equals("mineur") || level >= 25 && job.equals("pecheur");
      case "jupiter" -> level >= 25 && job.equals("mineur") || level >= 50 && job.equals("bucheron")
          || level >= 75 && job.equals("fermier") || level >= 100 && job.equals("chasseur");
      // The wiki explicitly says Osselet's adventurer XP bonus is not active.
      default -> false;
    };
  }
  public static long xpAtLevel(int level) {
    return Math.round(5000 * (Math.pow(1.0475, Math.clamp(level, 1, 100) - 1) - 1) / .0475);
  }
  public static long xpToNext(int level) {
    return level >= 100 ? 0 : xpAtLevel(Math.max(1, level) + 1) - xpAtLevel(level);
  }
}
