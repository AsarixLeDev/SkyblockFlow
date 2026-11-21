package dev.asarix.skyblockflow.core;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared presentation formatting. Stored values, commands and identifiers remain unchanged. */
public final class Numbers {
  private Numbers() {}
  private static final Pattern VALUES = Pattern.compile(
      "(?<![\\p{L}\\p{N}_.])(?:[0-9]{1,3}(?:[, '\u00a0\u202f][0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?(?=$|[^\\p{L}\\p{N}_.]|(?:ms|km|px|XP|s|m|h)\\b)");
  public static String integer(long value) { return decimal(BigDecimal.valueOf(value), 0, 0); }
  public static String amount(double value) { return decimal(value, 0, 2); }
  public static String fixed(double value, int places) { return decimal(value, places, places); }
  private static String decimal(double value, int min, int max) {
    return Double.isFinite(value) ? decimal(BigDecimal.valueOf(value), min, max) : "—";
  }
  public static String decimal(BigDecimal value, int min, int max) {
    var symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
    symbols.setGroupingSeparator(' ');
    var f = new DecimalFormat("#,##0", symbols);
    f.setMinimumFractionDigits(min); f.setMaximumFractionDigits(max);
    f.setRoundingMode(java.math.RoundingMode.HALF_UP);
    return f.format(value);
  }
  public static String text(String value) {
    if (value == null || value.isEmpty()) return value;
    var m = VALUES.matcher(value);
    var out = new StringBuilder();
    while (m.find()) {
      // Calendar years in event names are identifiers, not quantities.
      if (m.group().matches("20[0-9]{2}") && (value.substring(0, m.start()).matches("(?is).*\\b(?:RLCS|season|year)\\s+") || value.substring(m.start()).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}.*"))) {
        m.appendReplacement(out, m.group());
        continue;
      }
      String n = m.group().replace(",", "").replace(" ", "").replace("'", "").replace("\u00a0", "").replace("\u202f", "");
      int dot = n.indexOf('.');
      String whole = dot < 0 ? n : n.substring(0, dot);
      StringBuilder grouped = new StringBuilder(whole);
      for (int i = whole.length() - 3; i > 0; i -= 3) grouped.insert(i, ' ');
      if (dot >= 0) grouped.append(n.substring(dot));
      m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(grouped.toString()));
    }
    return m.appendTail(out).toString();
  }
}
