package dev.asarix.skyblockflow.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

public final class ShopPrices {
  private static final Pattern PRICE =
      Pattern.compile("([0-9](?:[0-9.,' \\p{Zs}]*[0-9])?)\\s*(\\$|(?:sky)?dollars?|excacoins?|votecoins?|Ⓔ)");
  private static final Set<String> JOB_SHOPS =
      Set.of("mineur", "bucheron", "fermier", "chasseur", "aventurier", "blocs", "spawners", "pecheur");

  public record LiveQuote(String unit, BigDecimal buy, BigDecimal sell) {}

  private ShopPrices() {}

  public static String shop(String title) {
    if (!title.contains("shop") && !title.contains("boutique") && !title.contains("marchand")) return "";
    for (String key : JOB_SHOPS) if (title.contains(key)) return key;
    if (title.contains("vote")) return "vote";
    if (title.contains("excacoin")) return "boutique";
    return "";
  }

  public static List<LiveQuote> live(List<String> lore) {
    Map<String, BigDecimal[]> byUnit = new LinkedHashMap<>();
    for (String part : lore) {
      String text = JobXp.normalize(part);
      if (text.contains("base") || text.contains("niveau") || text.contains("stack")
          || text.contains("lot") || text.contains("bonus")) continue;
      boolean buy = text.contains("acheter") || text.contains("achat") || text.contains("buy");
      boolean sell = text.contains("vendre") || text.contains("vente") || text.contains("sell");
      if (buy == sell) continue;
      var match = PRICE.matcher(text);
      if (!match.find()) continue;
      String unit = switch (match.group(2)) {
        case "Ⓔ", "excacoins", "excacoin" -> "Excacoins";
        case "votecoins", "votecoin" -> "Votecoins";
        default -> "Skydollars";
      };
      String number = match.group(1).replaceAll("[\\p{Zs} ']", "");
      if (number.contains(",") && number.contains(".")) {
        number = number.lastIndexOf(',') > number.lastIndexOf('.')
            ? number.replace(".", "").replace(',', '.') : number.replace(",", "");
      } else if (number.indexOf(',') != number.lastIndexOf(',')) number = number.replace(",", "");
      else number = number.replace(',', '.');
      try {
        byUnit.computeIfAbsent(unit, ignored -> new BigDecimal[2])[buy ? 0 : 1] =
            new BigDecimal(number);
      } catch (NumberFormatException ignored) { }
    }
    return byUnit.entrySet().stream()
        .map(entry -> new LiveQuote(entry.getKey(), entry.getValue()[0], entry.getValue()[1])).toList();
  }

  public static String line(String source, BigDecimal buy, BigDecimal sell, String unit, int count) {
    var join = new StringJoiner(" · ");
    join.add(source);
    if (buy != null) join.add("Buy " + format(buy, unit, count));
    if (sell != null) join.add("Sell " + format(sell, unit, count));
    return join.toString();
  }

  private static String format(BigDecimal price, String unit, int count) {
    String suffix = unit.equals("Skydollars") ? "$" : unit;
    String one = Numbers.decimal(price, 0, Math.max(2, price.stripTrailingZeros().scale())) + " " + suffix;
    return count <= 1 ? one : one + " ×" + count + " = "
        + Numbers.decimal(price.multiply(BigDecimal.valueOf(count)), 0, 3) + " " + suffix;
  }
}
