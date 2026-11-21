package dev.asarix.skyblockflow.core;

import java.math.*;
import java.util.*;
import java.util.regex.*;

public final class ShopValue {
  private static final Pattern MONEY =
      Pattern.compile("([0-9][0-9 '\\u00a0.,]*[0-9]|[0-9])\\s*(?:\\$|coins?|ecus?)");

  public record Quote(BigDecimal base, String job, List<Integer> replaced) {}

  public static Quote parse(List<String> lore, String context) {
    BigDecimal price = null;
    String job = "";
    List<Integer> rows = new ArrayList<>();
    String all = JobXp.normalize(context + " " + String.join(" ", lore));
    for (String id : List.of("mineur", "bucheron", "fermier", "chasseur", "aventurier"))
      if (all.contains(id)) job = id;
    for (int i = 0; i < lore.size(); i++) {
      String line = JobXp.normalize(lore.get(i));
      if (!(line.contains("vente")
              || line.contains("vendre")
              || line.contains("sell")
              || line.contains("valeur"))
          || line.contains("achat")) continue;
      if (line.contains("stack") || line.contains("lot")) continue;
      var m = MONEY.matcher(line);
      if (!m.find()) continue;
      String n = m.group(1).replace(" ", "").replace("\u00a0", "").replace("'", "");
      if (n.contains(",") && n.contains("."))
        n =
            n.lastIndexOf(',') > n.lastIndexOf('.')
                ? n.replace(".", "").replace(',', '.')
                : n.replace(",", "");
      else n = n.replace(',', '.');
      try {
        price = new BigDecimal(n);
        rows.add(i);
      } catch (NumberFormatException ignored) {
      }
    }
    return price == null ? null : new Quote(price, job, rows);
  }

  public static BigDecimal unit(BigDecimal base, int level) {
    return base.multiply(
        BigDecimal.ONE.add(
            new BigDecimal("0.015").multiply(BigDecimal.valueOf(Math.clamp(level, 0, 200) / 10))));
  }

  public static String unitMoney(BigDecimal value) {
    return value.stripTrailingZeros().scale() > 2
        ? Numbers.decimal(value, 0, Math.max(2, value.stripTrailingZeros().scale())) + " $"
        : money(value);
  }

  public static String money(BigDecimal value) {
    return Numbers.decimal(value, 2, 2) + " $";
  }
}
