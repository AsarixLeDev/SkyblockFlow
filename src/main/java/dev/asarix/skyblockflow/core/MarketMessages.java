package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.ExcaliaApiData.*;
import java.math.*;
import java.util.regex.Pattern;

/** Recognizes server transaction receipts; ordinary chat and server-shop purchases are ignored. */
public final class MarketMessages {
  private static final Pattern PURCHASE = Pattern.compile("^\\|\\s+([0-9][0-9 ]*) (.+?) for ([0-9][0-9., ]*)$");
  private static final Pattern LEFT = Pattern.compile("^the shop only has ([0-9][0-9 ]*) (.+) left!$");
  private static final Pattern ISLAND = Pattern.compile("^\\[skyllia]:\\s*you have been teleported to the island of ([a-z0-9_]{1,16})\\.$");
  private long receiptUntil, lastPurchaseAt;
  private String owner = "", shop = "";
  private Offer lastPurchase;

  public void reset() { owner = shop = ""; receiptUntil = lastPurchaseAt = 0; lastPurchase = null; }

  public void context(String owner, String shop) {
    this.owner = owner; this.shop = shop; lastPurchase = null;
  }

  public void accept(String message, Market market, MarketStock stock, long now) {
    if (message == null || message.length() > 1024) return;
    String text = JobXp.normalize(message).strip();
    var island = ISLAND.matcher(text);
    if (island.matches()) { context(island.group(1), ""); receiptUntil = 0; return; }
    if (text.equals("| successfully purchased:")) { receiptUntil = now + 2000; return; }
    if (text.matches("\\+-{5,}\\+")) { receiptUntil = 0; return; }
    if (market == null) return;
    var purchase = PURCHASE.matcher(text);
    if (purchase.matches() && now <= receiptUntil) {
      try {
        long amount = Long.parseLong(purchase.group(1).replace(" ", ""));
        if (amount <= 0) return;
        BigDecimal unit = new BigDecimal(purchase.group(3).replace(" ", "").replace(",", ""))
            .divide(BigDecimal.valueOf(amount), 8, RoundingMode.HALF_UP).stripTrailingZeros();
        var offer = stock.identify(market, purchase.group(2), unit, owner, shop);
        if (offer == null && shop.isEmpty()) offer = stock.identify(market, purchase.group(2), unit, "", "");
        if (offer != null) { stock.purchased(market, offer, amount, now); lastPurchase = offer; lastPurchaseAt = now; }
      } catch (NumberFormatException | ArithmeticException ignored) { }
      return;
    }
    var left = LEFT.matcher(text);
    if (left.matches()) {
      Offer offer = lastPurchase != null && now - lastPurchaseAt <= 30_000
          && FarmSacks.nameKey(left.group(2)).equals(FarmSacks.nameKey(lastPurchase.name())) ? lastPurchase : null;
      if (offer == null && (!owner.isEmpty() || !shop.isEmpty()))
        offer = stock.identify(market, left.group(2), null, owner, shop);
      try { stock.remaining(market, offer, Long.parseLong(left.group(1).replace(" ", "")), now); }
      catch (NumberFormatException ignored) { }
    }
  }
}
