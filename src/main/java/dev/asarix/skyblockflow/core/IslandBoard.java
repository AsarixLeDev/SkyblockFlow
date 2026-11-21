package dev.asarix.skyblockflow.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Reads normalized, line-oriented item text. Only the labelled puissance field is a score. */
public final class IslandBoard {
  private static final String NUMBER = "[0-9]+(?:[ '][0-9]{3})*(?:[.,][0-9]+)?(?: *[kmb])?";
  private static final String PREFIX = "(?m)^ *[•▸»✦|┃]* *";
  public static final String DEFAULT_PATTERN =
      "(?ms)\\A"
          + "(?=.*?^ *[•▸»✦|┃]* *(?:rang *[:#] *#?|classement *: *#?|#)(?<rank>[0-9]+)\\b)"
          + "(?=.*?^ *[•▸»✦|┃]* *(?:chef *:|proprietaire *:|ile de) *(?<leader>[a-z0-9_]{1,16}) *$)"
          + "(?=.*?^ *[•▸»✦|┃]* *puissance *(?:de l['’]ile *)?: *(?<power>"
          + NUMBER
          + ") *$)";
  private static final Pattern MEMBERS = Pattern.compile(PREFIX + "membres? *: *([a-z0-9_, ]+) *$");
  private static final Pattern GAIN =
      Pattern.compile("^ton ile gagne *\\+([0-9]+(?: [0-9]{3})*) de puissance *!$");
  private static final Pattern OWN_VALUE =
      Pattern.compile(
          "^(?:puissance de (?:ton|votre) ile|(?:ton|votre) ile a une puissance de) *:? *("
              + NUMBER
              + ") *[.!]?$");
  private static final Pattern PREVIEW =
      Pattern.compile(
          PREFIX + "puissance de l['’]ile *: *(" + NUMBER + ") *(?:→|->) *(" + NUMBER + ") *$");

  public record Entry(int rank, String leader, long power, long precision, boolean member) {
    public boolean own(String self) {
      return member || leader.equalsIgnoreCase(self);
    }
  }

  public record Proposal(long before, long after) {}

  public static Pattern compile(String expression) {
    if (expression == null || expression.isBlank() || expression.length() > 1024)
      throw new IllegalArgumentException("Enter a pattern of 1–1024 characters");
    var p = Pattern.compile(expression);
    if (!p.namedGroups().keySet().containsAll(Set.of("rank", "leader", "power")))
      throw new IllegalArgumentException("Use named groups rank, leader and power");
    return p;
  }

  private static BigDecimal decimal(String raw) {
    String s = raw.strip().replace(" ", "").replace("'", "").replace(',', '.');
    char end = s.charAt(s.length() - 1);
    int exponent =
        switch (end) {
          case 'k' -> 3;
          case 'm' -> 6;
          case 'b' -> 9;
          default -> 0;
        };
    return new BigDecimal(exponent == 0 ? s : s.substring(0, s.length() - 1))
        .scaleByPowerOfTen(exponent);
  }

  private static final class MatchBudget extends RuntimeException {}

  private static final class LimitedText implements CharSequence {
    private final String text;
    private int remaining = 120000;

    LimitedText(String text) {
      this.text = text;
    }

    public int length() {
      return text.length();
    }

    public char charAt(int i) {
      if (--remaining < 0) throw new MatchBudget();
      return text.charAt(i);
    }

    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }

    public String toString() {
      return text;
    }
  }

  public static Entry parse(Pattern pattern, String item, String self) {
    if (item == null || item.length() > 2048) return null;
    String normalized = JobXp.normalize(item);
    try {
      var m = pattern.matcher(new LimitedText(normalized));
      if (!m.find()) return null;
      int rank = Integer.parseInt(m.group("rank"));
      String leader = m.group("leader");
      String amount = m.group("power");
      if (rank < 1
          || rank > 1000000
          || leader == null
          || !leader.matches("[a-z0-9_]{1,16}")
          || amount == null
          || !amount.matches(NUMBER)) return null;
      var score = decimal(amount);
      long value = score.longValueExact();
      long precision = Math.max(1, score.ulp().longValue());
      boolean member = false;
      var members = MEMBERS.matcher(normalized);
      if (members.find())
        member =
            Arrays.asList(members.group(1).trim().split("[, ]+"))
                .contains(self.toLowerCase(Locale.ROOT));
      return new Entry(rank, leader, value, precision, member);
    } catch (IllegalArgumentException | ArithmeticException | MatchBudget | StackOverflowError e) {
      return null;
    }
  }

  /** Real /is visits cards name the owner first; the printed #rank is optional. */
  public static Entry visit(Pattern pattern, String raw, String self, int position) {
    var legacy = parse(pattern, raw, self);
    if (legacy != null) return legacy;
    if (raw == null || raw.length() > 2048) return null;
    String text = JobXp.normalize(raw).replaceAll("[\\p{Co}\\p{Cf}]", "");
    String title = text.split("\n", 2)[0].strip().replaceFirst("^[^a-z0-9_]+", "");
    var owner = Pattern.compile("^([a-z0-9_]{1,16})(?:\\s+#\\s*([0-9]+))?\\s*$").matcher(title);
    var power = Pattern.compile("(?m)^[^a-z0-9\n]*puissance\\s*:\\s*([0-9][0-9 ,.'’]*[kmb]?)\\s*$").matcher(text);
    if (!owner.matches() || !power.find()) return null;
    try {
      int rank = owner.group(2) == null ? position : Integer.parseInt(owner.group(2));
      String amount = power.group(1).strip().replace('’', '\'');
      if (amount.matches("[0-9]{1,3}(?:,[0-9]{3})+")) amount = amount.replace(",", "");
      var score = decimal(amount);
      long value = score.longValueExact(), precision = Math.max(1, score.ulp().longValue());
      return rank < 1 || rank > 1000000 || value < 0 ? null : new Entry(rank, owner.group(1), value, precision, owner.group(1).equalsIgnoreCase(self));
    } catch (RuntimeException ignored) { return null; }
  }

  public static String owner(String raw) {
    if (raw == null || raw.length() > 4096) return "";
    var m = Pattern.compile("(?m)^[^a-z0-9\n]*(?:chef|leader|proprietaire)(?: de l['’]ile)?\\s*[:»]\\s*(?:\\[[^\\]\n]+]\\s*)?([a-z0-9_]{1,16})\\s*$")
        .matcher(JobXp.normalize(raw).replaceAll("[\\p{Co}\\p{Cf}]", ""));
    return m.find() ? m.group(1) : "";
  }

  public static long gain(String raw) {
    var m = GAIN.matcher(JobXp.normalize(raw).strip());
    return m.matches() ? exact(m.group(1)) : -1;
  }

  public static long ownValue(String raw) {
    var m = OWN_VALUE.matcher(JobXp.normalize(raw).strip());
    return m.matches() ? exact(m.group(1)) : -1;
  }

  public static Proposal proposal(String raw) {
    var m = PREVIEW.matcher(JobXp.normalize(raw));
    if (!m.find()) return null;
    long before = exact(m.group(1)), after = exact(m.group(2));
    return before >= 0 && after > before ? new Proposal(before, after) : null;
  }

  private static long exact(String raw) {
    try {
      var value = decimal(raw);
      return value.scale() < 0 ? -1 : value.longValueExact();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  public static boolean rejected(String raw) {
    return JobXp.normalize(raw)
        .strip()
        .replace('’', '\'')
        .matches(
            "(?:ton role n'a pas le droit de convertir|il manque des ressources pour convertir|une"
                + " conversion est deja en cours)[.! ]*");
  }

  /** Bound against a snapshot. Omitted ranks and rounded or tied entries widen the interval. */
  public static String estimate(Collection<Entry> entries, String owner, long power) {
    if (owner == null || owner.isEmpty() || power < 0 || entries.isEmpty()) return "Unknown";
    int previous =
        entries.stream()
            .filter(e -> e.leader.equals(owner))
            .mapToInt(Entry::rank)
            .findFirst()
            .orElse(0);
    int lower = 1, upper = Integer.MAX_VALUE;
    for (var e : entries) {
      if (e.leader.equals(owner)) continue;
      long tolerance = e.precision - 1;
      if (e.power - tolerance > power)
        lower = Math.max(lower, e.rank + (previous != 0 && previous > e.rank ? 1 : 0));
      if (e.power < power - tolerance)
        upper = Math.min(upper, e.rank - (previous != 0 && previous < e.rank ? 1 : 0));
    }
    if (lower > upper || lower == 1 && upper == Integer.MAX_VALUE) return "Unknown";
    if (lower == upper) return "Estimated #" + lower;
    return upper == Integer.MAX_VALUE
        ? "Estimated #" + lower + " or lower"
        : "Estimated #" + lower + "–#" + upper;
  }
}
