package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.RecipeCatalog.*;
import java.util.*;

/** Assigns inventory ingredients to a 3×3 table without mixing different items in one cell. */
public final class CraftingGridPlan {
  public record Stack(String item, int amount, int limit) {}
  public record Target(int slot, String item, int amount) {}
  private CraftingGridPlan() {}

  public static Optional<List<Target>> plan(Recipe recipe, Collection<Stack> inventory, boolean max) {
    if (recipe == null || recipe.station() != Station.VANILLA || !recipe.hasGrid() || recipe.cells().size() > 9)
      return Optional.empty();
    var available = new HashMap<String,Integer>();
    var limits = new HashMap<String,Integer>();
    for (var stack : inventory) {
      available.merge(stack.item, stack.amount, Integer::sum);
      limits.merge(stack.item, stack.limit, Math::min);
    }
    var cells = new ArrayList<>(recipe.cells());
    var slots = new HashSet<Integer>();
    for (var cell : cells) if (cell.x() < 0 || cell.x() >= 3 || cell.y() < 0 || cell.y() >= 3
        || !slots.add(1 + cell.y()*3 + cell.x())) return Optional.empty();
    cells.sort(Comparator.comparingInt(c -> c.ingredient().choices().size()));
    for (int batches = max ? 64 : 1; batches >= 1; batches--) {
      var result = new ArrayList<Target>();
      if (assign(cells, 0, batches, available, limits, result, new int[]{10000})) return Optional.of(List.copyOf(result));
    }
    return Optional.empty();
  }

  private static boolean assign(List<Cell> cells, int index, int batches, Map<String,Integer> available,
      Map<String,Integer> limits, List<Target> result, int[] budget) {
    if (index == cells.size()) return true;
    if (--budget[0] <= 0) return false;
    var cell = cells.get(index); int amount = cell.ingredient().amount()*batches;
    for (String item : cell.ingredient().choices()) {
      int count = available.getOrDefault(item,0);
      if (count < amount || amount > limits.getOrDefault(item,0)) continue;
      available.put(item,count-amount); result.add(new Target(1+cell.y()*3+cell.x(),item,amount));
      if (assign(cells,index+1,batches,available,limits,result,budget)) return true;
      result.removeLast(); available.put(item,count);
    }
    return false;
  }
}
