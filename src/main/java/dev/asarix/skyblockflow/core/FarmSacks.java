package dev.asarix.skyblockflow.core;

import java.util.List;
import java.util.regex.Pattern;

/** Reads a farm sack's filter without treating its amount or tier as an item name. */
public final class FarmSacks {
  private FarmSacks() {}
  private static final Pattern FILTER = Pattern.compile("^(?:[•·▸►]\\s*)?(?:filtre|filter)\\s*:\\s*(.+)$");

  public static String filter(String model, String name, List<String> lore) {
    String label = JobXp.normalize(name).replaceAll("[^a-z0-9 ]", " ").replaceAll(" +", " ").strip();
    if (!model.matches("(?:oraxen:)?bag[-_]farms(?:_t[1-5])?")
        && !label.matches("sac de farm(?: t[1-5])?")) return "";
    for (String line : lore) {
      var match = FILTER.matcher(JobXp.normalize(line).strip());
      if (!match.matches()) continue;
      String item = match.group(1).strip();
      if (List.of("aucun", "aucune", "none", "vide", "empty", "tous", "all", "non defini").contains(item)) return "";
      return item;
    }
    return "";
  }

  public static String nameKey(String name) {
    return JobXp.normalize(name).replaceAll("[^a-z0-9]", "");
  }
}
