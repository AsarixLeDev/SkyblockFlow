package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.ExcaliaApiData.*;
import java.math.BigDecimal;
import java.util.*;

/** Local observations supersede an older public snapshot, never trigger a network request. */
public final class MarketStock {
  private record Stock(long remaining, long at) {}
  private final Map<String, Stock> observed = new HashMap<>();

  private long revision;
  public long revision() { return revision; }
  public void clear() { observed.clear(); revision++; }

  public Map<String, Long> snapshot(Market market) {
    var copy = new HashMap<String, Long>();
    observed.forEach((id, stock) -> {
      if (stock.at >= market.at()) copy.put(id, stock.remaining);
    });
    return Map.copyOf(copy);
  }

  public List<Offer> sellers(Market market, String type, String name) {
    return ExcaliaApiData.best(market.selling().getOrDefault(type, List.of()).stream()
        .map(o -> current(market, o)).toList(), name, false);
  }

  public List<String> lines(Market market, String type, String name, int count) {
    var lines = new ArrayList<String>();
    ExcaliaApiData.quote(lines, sellers(market, type, name), count, false);
    ExcaliaApiData.quote(lines, ExcaliaApiData.best(market.buying().getOrDefault(type, List.of()), name, true), count, true);
    return List.copyOf(lines);
  }

  public Offer current(Market market, Offer offer) {
    Stock local = observed.get(offer.identity());
    if (local == null || offer.unlimited()) return offer;
    // fetchedAt is the upstream snapshot time, not the time this client downloaded it.
    if (market.at() > local.at) { observed.remove(offer.identity()); return offer; }
    return offer.withStock(Math.min(offer.stock(), local.remaining));
  }

  public Offer identify(Market market, String name, BigDecimal unit, String owner, String shop) {
    var candidates = market.selling().values().stream().flatMap(List::stream)
        .filter(o -> FarmSacks.nameKey(o.name()).equals(FarmSacks.nameKey(name)))
        .filter(o -> unit == null || o.price().compareTo(unit) == 0)
        .filter(o -> shop.isEmpty() || o.shop().equals(shop))
        .filter(o -> owner.isEmpty() || o.owner().equalsIgnoreCase(owner)).toList();
    // Never invalidate an arbitrary cheapest offer when two shops could have made this sale.
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  public void remaining(Market market, Offer offer, long remaining, long now) {
    if (offer == null || offer.unlimited() || remaining < 0) return;
    var previous = observed.get(offer.identity());
    if (previous != null && previous.at > now) return;
    if (observed.size() >= 2048 && !observed.containsKey(offer.identity())) clear();
    long prior = current(market, offer).stock();
    long updated = Math.min(prior, remaining);
    observed.put(offer.identity(), new Stock(updated, now));
    if (updated != prior) revision++;
  }

  public void purchased(Market market, Offer offer, long amount, long now) {
    if (offer == null || amount <= 0) return;
    remaining(market, offer, Math.max(0, current(market, offer).stock() - amount), now);
  }
}
