package dev.asarix.skyblockflow.core;

import java.util.List;
import java.util.regex.Pattern;

public final class SpawnerMenu {
  private static final Pattern RANGE_NAME =
      Pattern.compile(
          "\\b(?:portee (?:du )?joueur|player (?:activation )?range|required player range)\\b");
  private static final Pattern CURRENT =
      Pattern.compile(
          "\\b(?:actuel(?:le)?|current(?:"
              + " range)?|maintenant)\\s*[:：]\\s*(\\d{1,4})\\s*(?:blocs?|blocks?)\\b");

  private SpawnerMenu() {}

  public static boolean isSpawnerMenu(String title) {
    return JobXp.normalize(title).contains("spawner");
  }

  public static Integer radius(String name, List<String> lore) {
    if (!RANGE_NAME.matcher(JobXp.normalize(name)).find()
        && lore.stream().noneMatch(line -> RANGE_NAME.matcher(JobXp.normalize(line)).find())) {
      return null;
    }
    for (String line : lore) {
      var match = CURRENT.matcher(JobXp.normalize(line));
      if (match.find()) {
        int range = Integer.parseInt(match.group(1));
        if (validRadius(range)) return range;
      }
    }
    return null;
  }

  public static boolean validRadius(int range) {
    return range > 0 && range <= 1024;
  }
}
