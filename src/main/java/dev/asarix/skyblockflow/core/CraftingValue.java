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
  private record Branch(Cost source, Fraction amount) {}
  // Plans share immutable subtrees; ingredient details are expanded only for the final result.
  private record Cost(long order, Fraction unit, String item, List<Branch> branches) {}
  private static final class Budget {
    final long deadline = System.nanoTime() + 250_000_000L;
    int operations;
    long sequence;
    void step() {
      checkCancelled();
      if (++operations > 100_000 || System.nanoTime() > deadline) throw new ArithmeticException("Cost budget");
    }
  }

  public static Optional<Value> calculate(RecipeCatalog catalog, String output, Function<String, BigDecimal> buy) {
    return calculate(catalog, output, inputs(catalog, output), buy);
  }

  public static Set<String> inputs(RecipeCatalog catalog, String output) {
    var reachable = new LinkedHashSet<String>();
    var queue = new ArrayDeque<String>();
    queue.add(output);
    while (!queue.isEmpty()) {
      checkCancelled();
      for (var recipe : catalog.produces(queue.removeFirst())) {
        for (var ingredient : recipe.ingredients()) for (String item : ingredient.choices()) {
          if (!item.equals(output) && reachable.add(item)) {
            if (reachable.size() > 4096) return Set.of();
            queue.addLast(item);
          }
        }
      }
    }
    return reachable;
  }

  private static void checkCancelled() {
    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
  }

  public static Optional<Value> calculate(RecipeCatalog catalog, String output, Set<String> reachable,
                                          Function<String, BigDecimal> buy) {
    if (reachable.isEmpty()) return Optional.empty();
    try {
      var budget = new Budget();
      var costs = new HashMap<String, Cost>();
      for (String item : reachable) {
        budget.step();
        var price = buy.apply(item);
        if (price != null && price.signum() >= 0)
          costs.put(item, new Cost(++budget.sequence, Fraction.of(price), item, List.of()));
      }
      var pending = new ArrayDeque<Recipe>();
      var queued = new HashSet<String>();
      for (String item : reachable) for (var recipe : catalog.produces(item))
        if (queued.add(recipe.id())) pending.addLast(recipe);
      while (!pending.isEmpty()) {
        budget.step();
        var recipe = pending.removeFirst();
        queued.remove(recipe.id());
        var candidate = improvement(recipe, costs, costs.get(recipe.output()), budget);
        if (candidate == null) continue;
        costs.put(recipe.output(), candidate);
        for (var dependent : catalog.uses(recipe.output())) {
          if (reachable.contains(dependent.output()) && queued.add(dependent.id())) pending.addLast(dependent);
        }
      }
      Cost best = null;
      for (var recipe : catalog.produces(output)) {
        budget.step();
        var candidate = improvement(recipe, costs, best, budget);
        if (candidate != null) best = candidate;
      }
      if (best == null) return Optional.empty();
      return Optional.of(new Value(best.unit.decimal(), expand(best, budget)));
    } catch (ArithmeticException failure) { return Optional.empty(); }
  }

  private static Cost improvement(Recipe recipe, Map<String, Cost> costs, Cost prior, Budget budget) {
    Fraction total = Fraction.of(0);
    var branches = new ArrayList<Branch>(recipe.ingredients().size());
    for (var ingredient : recipe.ingredients()) {
      Cost cheapest = null;
      for (String choice : ingredient.choices()) {
        budget.step();
        var candidate = costs.get(choice);
        if (candidate != null && (cheapest == null || candidate.unit.compareTo(cheapest.unit) < 0)) cheapest = candidate;
      }
      if (cheapest == null) return null;
      Fraction amount = Fraction.of(ingredient.amount()).divided(recipe.amount());
      total = total.add(cheapest.unit.times(amount));
      if (prior != null && total.compareTo(prior.unit) >= 0) return null;
      branches.add(new Branch(cheapest, amount));
    }
    return new Cost(++budget.sequence, total, null, List.copyOf(branches));
  }

  private static List<Part> expand(Cost root, Budget budget) {
    var amounts = new IdentityHashMap<Cost, Fraction>();
    var pending = new PriorityQueue<Cost>(Comparator.comparingLong(Cost::order).reversed());
    var quantities = new TreeMap<String, Fraction>();
    var totals = new HashMap<String, Fraction>();
    amounts.put(root, Fraction.of(1)); pending.add(root);
    // A plan references only older nodes. Process newest first to merge shared demand before expansion.
    while (!pending.isEmpty()) {
      budget.step();
      var node = pending.remove();
      var amount = amounts.remove(node);
      if (node.item != null) {
        quantities.merge(node.item, amount, Fraction::add);
        totals.merge(node.item, node.unit.times(amount), Fraction::add);
      } else for (var branch : node.branches) {
        budget.step();
        var required = amount.times(branch.amount);
        if (!amounts.containsKey(branch.source)) pending.add(branch.source);
        amounts.merge(branch.source, required, Fraction::add);
      }
    }
    var parts = new ArrayList<Part>(quantities.size());
    quantities.forEach((item, amount) -> parts.add(new Part(item, amount.decimal(), totals.get(item).decimal())));
    return List.copyOf(parts);
  }
}
