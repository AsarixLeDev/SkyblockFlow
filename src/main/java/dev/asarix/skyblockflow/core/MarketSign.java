package dev.asarix.skyblockflow.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The four lines of an empty player-shop sign, in server order. */
public record MarketSign(String owner, String item, BigDecimal price) {
  private static final Set<String> EMPTY = Set.of("out of stock", "rupture de stock", "stock epuise");
  private static final Pattern PRICE = Pattern.compile(
      "^[€$£]?\\s*([0-9][0-9., ']*[0-9]|[0-9])\\s*[€$£]?\\s*(each|(?:/\\s*)?(?:l['’]\\s*)?unite|/\\s*item)$");

  public static MarketSign parse(List<String> lines) {
    if (lines.size() != 4 || lines.stream().anyMatch(s -> s == null || s.length() > 256)) return null;
    var plain = lines.stream().map(s -> JobXp.normalize(s).strip()).toList();
    if (!plain.get(0).matches("[a-z0-9_]{1,16}") || !EMPTY.contains(plain.get(1))
        || FarmSacks.nameKey(plain.get(2)).isEmpty()) return null;
    var price = PRICE.matcher(plain.get(3));
    if (!price.matches()) return null;
    String number = price.group(1).replace(" ", "").replace("'", "");
    if (number.contains(",") && number.contains(".")) {
      number = number.lastIndexOf(',') > number.lastIndexOf('.')
          ? number.replace(".", "").replace(',', '.') : number.replace(",", "");
    } else if (price.group(2).equals("each") && number.matches("[0-9]{1,3}(,[0-9]{3})+")) {
      number = number.replace(",", "");
    } else number = number.replace(',', '.');
    try {
      BigDecimal unit = new BigDecimal(number);
      if (unit.signum() <= 0 || unit.compareTo(new BigDecimal("100000000")) > 0) return null;
      return new MarketSign(plain.get(0), lines.get(2).strip(), unit);
    } catch (NumberFormatException ignored) { return null; }
  }
}
