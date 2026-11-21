package dev.asarix.skyblockflow.core;

import java.util.*;
import java.util.regex.*;

public final class PetTracker {
  public static final int MAX_ICON_LENGTH = 65536;

  public enum State {
    UNKNOWN,
    NONE,
    EQUIPPED
  }

  public static class Snapshot {
    public int version = 1;
    public State state = State.UNKNOWN;
    public String type = "", name = "", rarity = "Unknown";
    public String iconItemJson = "";
    public Map<String, Double> jobBoostPercent = new LinkedHashMap<>();
    public int level = -1;
    public double totalXp = -1;
    public long foodSeconds = -1, observedAt, foodObservedAt;
    public boolean foodLowerBound, foodPaused;

    public long foodLeft(long now) {
      return foodSeconds < 0
          ? -1
          : Math.max(0, foodSeconds - (foodPaused ? 0 : Math.max(0, now - foodObservedAt) / 1000));
    }

    public void pauseFood(long now) {
      if (foodPaused) return;
      foodSeconds = foodLeft(now);
      foodObservedAt = now;
      foodPaused = true;
    }

    public void resumeFood(long now) {
      if (!foodPaused) return;
      foodObservedAt = now;
      foodPaused = false;
    }

    public String identity() {
      return state + ":" + type + ":" + rarity;
    }
  }

  public Snapshot data = new Snapshot();
  private final Map<String, Double> missedXp = new HashMap<>();
  private final Map<String, Long> warned = new HashMap<>();
  private long foodCredit, foodCreditAt;

  public void observe(Snapshot reading, long now) {
    if (reading == null) return;
    boolean same =
        data.state == State.EQUIPPED
            && reading.state == State.EQUIPPED
            && data.type.equals(reading.type)
            && (data.rarity.equals(reading.rarity)
                || data.rarity.equals("Unknown")
                || reading.rarity.equals("Unknown"));
    if (!same) foodCredit = 0;
    else if (reading.foodSeconds >= 0 && reading.foodObservedAt == 0 && data.foodLeft(now) >= 0) {
      long increase = reading.foodSeconds - data.foodLeft(now);
      if (increase > 1) {
        foodCredit = (now - foodCreditAt <= 2000 ? foodCredit : 0) + increase;
        foodCreditAt = now;
      }
    }
    if (reading.iconItemJson == null || reading.iconItemJson.length() > MAX_ICON_LENGTH)
      reading.iconItemJson = "";
    if (same) {
      if (reading.jobBoostPercent.isEmpty() && (reading.level < 0 || reading.level == data.level))
        reading.jobBoostPercent.putAll(data.jobBoostPercent);
      if (reading.iconItemJson.isEmpty()) reading.iconItemJson = data.iconItemJson;
      if (reading.rarity.equals("Unknown")) reading.rarity = data.rarity;
      if (reading.totalXp < 0) reading.totalXp = data.totalXp;
      if (reading.level < 0) reading.level = data.level;
      if (reading.foodSeconds < 0) {
        reading.foodSeconds = data.foodSeconds;
        reading.foodObservedAt = data.foodObservedAt;
        reading.foodLowerBound = data.foodLowerBound;
      }
    }
    if (!same || reading.foodSeconds > 0) {
      missedXp.clear();
      warned.clear();
    }
    reading.observedAt = now;
    if (reading.foodSeconds >= 0 && reading.foodObservedAt == 0) reading.foodObservedAt = now;
    data = reading;
  }

  public boolean hungryMessage(String text, long now) {
    if (!JobXp.normalize(text).contains("votre pet a faim")) return false;
    if (data.state == State.EQUIPPED) {
      data.foodSeconds = 0;
      data.foodObservedAt = now;
      data.foodLowerBound = false;
      foodCredit = 0;
    }
    return data.state == State.EQUIPPED;
  }

  private static final Pattern FED =
      Pattern.compile(
          "(?<![0-9+])\\+([0-9][0-9 ']*)\\s*s\\s+de\\s+saturation\\s*\\(\\s*([0-9]+)\\b[^\\r"
              + "\\n"
              + "]*\\)");

  public boolean feedingMessage(String text, long now) {
    if (data.state != State.EQUIPPED) return false;
    Matcher m = FED.matcher(JobXp.normalize(text));
    if (!m.find()) return false;
    try {
      long added = Long.parseLong(m.group(1).replace(" ", "").replace("'", ""));
      if (added <= 0 || Long.parseLong(m.group(2)) <= 0) return false;
      long left = data.foodLeft(now);
      // The refreshed menu may arrive just before its feeding confirmation.
      long credited = now - foodCreditAt <= 2000 ? Math.min(added, foodCredit) : 0;
      foodCredit = Math.max(0, foodCredit - credited);
      data.foodLowerBound |= left < 0;
      data.foodSeconds = Math.min(14400, Math.max(0, left) + Math.min(14400, added - credited));
      if (data.foodSeconds == 14400) data.foodLowerBound = false;
      data.foodObservedAt = data.observedAt = now;
      missedXp.clear();
      warned.clear();
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public boolean gain(String job, double xp, long now, Models.PetSettings settings) {
    if (!settings.warnHungry
        || data.state != State.EQUIPPED
        || data.foodLowerBound
        || data.foodLeft(now) != 0
        || !PetCatalog.boosts(data.type, data.level, job)
        || !Double.isFinite(xp)
        || xp <= 0
        || settings.threshold(job) <= 0) return false;
    double total = missedXp.merge(job, xp, Double::sum);
    if (total < settings.threshold(job)
        || now - warned.getOrDefault(job, Long.MIN_VALUE / 2) < settings.warningCooldownMs)
      return false;
    missedXp.put(job, 0d);
    warned.put(job, now);
    return true;
  }

  /** Current equipped-pet bonus, or NaN until its active bonus/food state is known. */
  public double jobMultiplier(String job, long now) {
    if (data.state == State.NONE) return 1;
    if (data.state != State.EQUIPPED) return Double.NaN;
    if (data.foodLeft(now) == 0 && !data.foodLowerBound) return 1;
    Double percent = data.jobBoostPercent.get(job);
    if (percent == null && !PetCatalog.boosts(data.type, data.level, job)) return 1;
    if (data.foodLeft(now) <= 0) return Double.NaN;
    return percent == null || !Double.isFinite(percent) || percent < 0 || percent > 9900
        ? Double.NaN : 1 + percent / 100;
  }

  public static boolean mainMenu(String title) {
    String t = JobXp.normalize(title).strip();
    return title.indexOf('\uf663') >= 0
        || t.matches("(?:mes |vos |les )?pets(?: [»·-].*)?")
        || t.equals("/pets");
  }

  public static boolean feedMenu(String title) {
    return title.indexOf('\uf6e4') >= 0 || JobXp.normalize(title).contains("nourrir");
  }

  private static final Pattern LEVEL =
      Pattern.compile("(?:niveau|niv\\.?|level|lvl)\\s*[:»·-]?\\s*([0-9]{1,3})");
  private static final String NUMBER = "([0-9][0-9 .,']*)";

  private static double number(String value) {
    return Double.parseDouble(value.strip().replace(" ", "").replace("'", "").replace(',', '.'));
  }

  private static final Pattern FOOD_LABEL =
      Pattern.compile("\\b(?:satiete|saturation|nourriture|faim|food|remplissage)\\b");

  public static boolean foodLine(String text) {
    return FOOD_LABEL.matcher(JobXp.normalize(text)).find();
  }

  private static long foodSeconds(List<String> lore) {
    long result = -1;
    int precision = 0;
    for (String raw : lore) {
      String line = JobXp.normalize(raw);
      if (!FOOD_LABEL.matcher(line).find()) continue;
      if (line.contains("affame") || line.contains("a faim")) return 0;
      // The Nourrir button reports seconds, e.g. 4672 / 14400. Prefer this to rounded %.
      Matcher ratio = Pattern.compile(NUMBER + "\\s*/\\s*" + NUMBER).matcher(line);
      if (ratio.find()) {
        try {
          double value = number(ratio.group(1)), maximum = number(ratio.group(2));
          if (maximum == 14400 && value >= 0 && value <= maximum) {
            result = Math.round(value);
            precision = 2;
            continue;
          }
        } catch (NumberFormatException ignored) {
        }
      }
      Matcher clock =
          Pattern.compile("(?<![0-9])([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?").matcher(line);
      if (clock.find()) {
        result =
            clock.group(3) == null
                ? Long.parseLong(clock.group(1)) * 60 + Long.parseLong(clock.group(2))
                : Long.parseLong(clock.group(1)) * 3600
                    + Long.parseLong(clock.group(2)) * 60
                    + Long.parseLong(clock.group(3));
        precision = 2;
      } else {
        Matcher duration =
            Pattern.compile("([0-9]+)\\s*(heures?|h|minutes?|min|m|secondes?|sec|s)(?![a-z])")
                .matcher(line);
        long seconds = 0;
        boolean found = false;
        while (duration.find()) {
          found = true;
          long n = Long.parseLong(duration.group(1));
          seconds +=
              n
                  * (duration.group(2).startsWith("h")
                      ? 3600
                      : duration.group(2).startsWith("m") ? 60 : 1);
        }
        if (found) {
          result = seconds;
          precision = 2;
        } else if (precision < 2) {
          Matcher percent = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*%").matcher(line);
          if (percent.find()) {
            result = Math.round(144 * number(percent.group(1)));
            precision = 1;
          } else if (line.matches(".*[:»]\\s*0(?:[ /%].*)?")) {
            result = 0;
            precision = 2;
          }
        }
      }
    }
    return Math.min(14400, result);
  }

  public static Snapshot parse(String name, List<String> lore) {
    String all = JobXp.normalize(name + "\n" + String.join("\n", lore));
    String type = PetCatalog.type(all);
    if (type.isEmpty()) return null;
    Snapshot result = new Snapshot();
    result.state = State.EQUIPPED;
    result.type = type;
    result.name = PetCatalog.NAMES.get(type);
    String rarityText = JobXp.normalize(name);
    for (String line : lore) {
      String normalized = JobXp.normalize(line);
      if (normalized.contains("rarete")
          || normalized.contains("rarity")
          || normalized.strip().matches("commun|peu commun|rare|epique|legendaire"))
        rarityText += " " + normalized;
    }
    for (String rarity : List.of("legendaire", "epique", "peu commun", "rare", "commun"))
      if (Pattern.compile("(?<![a-z])" + rarity + "(?![a-z])").matcher(rarityText).find()) {
        result.rarity =
            switch (rarity) {
              case "legendaire" -> "Legendary";
              case "epique" -> "Epic";
              case "peu commun" -> "Uncommon";
              case "rare" -> "Rare";
              default -> "Common";
            };
        break;
      }
    Matcher level = LEVEL.matcher(all);
    if (level.find()) result.level = Integer.parseInt(level.group(1));
    if (result.level > 100) return null;
    result.foodSeconds = foodSeconds(lore);
    for (String raw : lore) {
      String line = JobXp.normalize(raw);
      if (line.contains("xp") || line.contains("experience")) {
        if (result.level == 100
            && line.matches(".*(?:xp|experience)\\s*[:»]?\\s*max\\s*/\\s*max.*"))
          result.totalXp = PetCatalog.xpAtLevel(100);
        Matcher amount =
            Pattern.compile(
                    "(?:xp|experience)(?:\\s+totale?s?)?\\s*[:»]?\\s*"
                        + NUMBER
                        + "(?:/\\s*"
                        + NUMBER
                        + ")?")
                .matcher(line);
        if (amount.find())
          try {
            double n = number(amount.group(1));
            if (result.level >= 1
                && result.level < 100
                && !line.contains("total")
                && (amount.group(2) != null
                        && Math.abs(number(amount.group(2)) - PetCatalog.xpToNext(result.level)) < 2
                    || n < PetCatalog.xpAtLevel(result.level)))
              n += PetCatalog.xpAtLevel(result.level);
            if (Double.isFinite(n) && n >= 0 && n <= 1e12) result.totalXp = n;
          } catch (NumberFormatException ignored) {
          }
      }
    }
    for (String raw : lore) {
      String line = JobXp.normalize(raw);
      if (!(line.contains("xp") || line.contains("experience")) || line.contains("/")) continue;
      var percent = Pattern.compile("([0-9]+(?:[.,][0-9]+)?) *%").matcher(line);
      if (!percent.find()) continue;
      double value = number(percent.group(1));
      if (value < 0 || value > 9900 || percent.find()) continue;
      boolean named = false;
      for (String job : JobXp.NAMES.keySet()) if (line.contains(job)) {
        result.jobBoostPercent.put(job, value); named = true;
      }
      if (!named && (line.contains("bonus") || line.contains("boost") || line.contains("metier")))
        for (String job : JobXp.NAMES.keySet()) if (PetCatalog.boosts(type, result.level, job))
          result.jobBoostPercent.put(job, value);
    }
    return result;
  }
}
