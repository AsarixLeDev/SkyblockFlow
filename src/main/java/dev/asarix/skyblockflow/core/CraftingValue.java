package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.RecipeCatalog.*;
import java.math.*;
import java.util.*;
import java.util.function.Function;

/** Minimum ingredient replacement cost. Buying the requested finished item is excluded. */
public final class CraftingValue {
  private CraftingValue() {}
  public record Part(String item, BigDecimal amount, BigDecimal cost) {}
  public record Value(BigDecimal unit, List<Part> parts) {}
  private record Fraction(BigInteger n, BigInteger d) implements Comparable<Fraction> {
    Fraction {
      if (d.signum() <= 0 || n.signum() < 0) throw new IllegalArgumentException();
      var gcd = n.gcd(d); n = n.divide(gcd); d = d.divide(gcd);
      if (n.bitLength() + d.bitLength() > 4096) throw new ArithmeticException("Cost limit");
    }
    static Fraction of(long n) { return new Fraction(BigInteger.valueOf(n), BigInteger.ONE); }
    static Fraction of(BigDecimal n) {
      return n.scale() < 0 ? new Fraction(n.unscaledValue().multiply(BigInteger.TEN.pow(-n.scale())), BigInteger.ONE)
          : new Fraction(n.unscaledValue(), BigInteger.TEN.pow(n.scale()));
    }
    Fraction add(Fraction b) { return new Fraction(n.multiply(b.d).add(b.n.multiply(d)), d.multiply(b.d)); }
    Fraction times(Fraction b) { return new Fraction(n.multiply(b.n), d.multiply(b.d)); }
    Fraction divided(long count) { return new Fraction(n, d.multiply(BigInteger.valueOf(count))); }
    BigDecimal decimal() { return new BigDecimal(n).divide(new BigDecimal(d), MathContext.DECIMAL128); }
    public int compareTo(Fraction b) { return n.multiply(b.d).compareTo(b.n.multiply(d)); }
  }
  private record Leaf(String item, Fraction amount, Fraction cost) {}
  private record Cost(Fraction unit, List<Leaf> leaves) {}

  public static Optional<Value> calculate(RecipeCatalog catalog, String output, Function<String, BigDecimal> buy) {
    if (catalog.produces(output).isEmpty()) return Optional.empty();
    try {
      var reachable = new LinkedHashSet<String>();
      var queue = new ArrayDeque<String>();
      for (var recipe : catalog.produces(output)) for (var ingredient : recipe.ingredients()) queue.addAll(ingredient.choices());
      while (!queue.isEmpty()) {
        String item = queue.removeFirst();
        if (item.equals(output) || !reachable.add(item)) continue;
        if (reachable.size() > 4096) return Optional.empty();
        for (var recipe : catalog.produces(item)) for (var ingredient : recipe.ingredients()) queue.addAll(ingredient.choices());
      }
      var costs = new HashMap<String, Cost>();
      for (String item : reachable) {
        var price = buy.apply(item);
        if (price != null && price.signum() >= 0) {
          var unit = Fraction.of(price);
          costs.put(item, new Cost(unit, List.of(new Leaf(item, Fraction.of(1), unit))));
        }
      }
      var recipes = catalog.recipes().stream().filter(r -> reachable.contains(r.output())).toList();
      boolean settled = false;
      for (int pass = 0; pass <= reachable.size(); pass++) {
        boolean changed = false;
        for (var recipe : recipes) {
          var candidate = cost(recipe, costs);
          var prior = costs.get(recipe.output());
          if (candidate != null && (prior == null || candidate.unit.compareTo(prior.unit) < 0)) {
            costs.put(recipe.output(), candidate); changed = true;
          }
        }
        if (!changed) { settled = true; break; }
      }
      if (!settled) return Optional.empty();
      Cost best = null;
      for (var recipe : catalog.produces(output)) {
        var candidate = cost(recipe, costs);
        if (candidate != null && (best == null || candidate.unit.compareTo(best.unit) < 0)) best = candidate;
      }
      if (best == null) return Optional.empty();
      var merged = new LinkedHashMap<String, Leaf>();
      for (var leaf : best.leaves) merged.merge(leaf.item, leaf,
          (a,b) -> new Leaf(a.item, a.amount.add(b.amount), a.cost.add(b.cost)));
      return Optional.of(new Value(best.unit.decimal(), merged.values().stream()
          .map(l -> new Part(l.item, l.amount.decimal(), l.cost.decimal())).toList()));
    } catch (ArithmeticException failure) { return Optional.empty(); }
  }

  private static Cost cost(Recipe recipe, Map<String, Cost> costs) {
    Fraction total = Fraction.of(0);
    var leaves = new ArrayList<Leaf>();
    for (var ingredient : recipe.ingredients()) {
      Cost cheapest = null;
      for (String choice : ingredient.choices()) {
        var candidate = costs.get(choice);
        if (candidate != null && (cheapest == null || candidate.unit.compareTo(cheapest.unit) < 0)) cheapest = candidate;
      }
      if (cheapest == null) return null;
      Fraction amount = Fraction.of(ingredient.amount()).divided(recipe.amount());
      total = total.add(cheapest.unit.times(amount));
      for (var leaf : cheapest.leaves) leaves.add(new Leaf(leaf.item, leaf.amount.times(amount), leaf.cost.times(amount)));
      if (leaves.size() > 8192) return null;
    }
    // Merge at every level so shared raw ingredients cannot create exponential detail lists.
    var merged = new LinkedHashMap<String, Leaf>();
    for (var leaf : leaves) merged.merge(leaf.item, leaf,
        (a,b) -> new Leaf(a.item, a.amount.add(b.amount), a.cost.add(b.cost)));
    return new Cost(total, List.copyOf(merged.values()));
  }
}
