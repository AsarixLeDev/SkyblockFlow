package dev.asarix.skyblockflow.core;

import java.text.Normalizer;
import java.util.*;

/** Server-independent ingredients, recipe indexes and inventory-aware material planning. */
public final class RecipeCatalog {
  public enum Station { VANILLA, CUSTOM, OTHER }

  public record Ingredient(List<String> choices, int amount) {
    public Ingredient {
      choices = List.copyOf(choices);
      if (amount < 1 || choices.isEmpty()) throw new IllegalArgumentException("Invalid ingredient");
    }

    public Ingredient(String id, int amount) {
      this(List.of(id), amount);
    }
  }

  public record Cell(int x, int y, Ingredient ingredient) {}

  public record Recipe(String id, String output, int amount, Station station, int size,
                       List<Ingredient> ingredients, List<Cell> cells, String note, boolean expandable) {
    public Recipe {
      ingredients = List.copyOf(ingredients);
      cells = List.copyOf(cells);
      if (amount < 1 || size < 1 || size > 6 || ingredients.isEmpty())
        throw new IllegalArgumentException("Invalid recipe: " + id);
    }

    public boolean hasGrid() {
      return !cells.isEmpty();
    }
  }

  public record Material(String item, long needed, long present) {
    public boolean complete() { return needed <= present; }
  }

  public record Checklist(List<Material> materials, boolean limited) {
    public Checklist {
      materials = materials.stream().sorted(Comparator.comparing(Material::complete)).toList();
    }

    public static int rows(int height) { return Math.max(1, (height - 34) / 19); }

    public int offset(int height, int requested) {
      return Math.clamp(requested, 0, Math.max(0, materials.size() - rows(height)));
    }
  }

  private final Map<String, Recipe> recipes = new LinkedHashMap<>();
  private final Map<String, List<Recipe>> outputs = new HashMap<>();
  private final Map<String, List<Recipe>> uses = new HashMap<>();

  public RecipeCatalog(Collection<Recipe> values) {
    for (Recipe recipe : values) {
      if (recipes.putIfAbsent(recipe.id, recipe) != null) continue;
      outputs.computeIfAbsent(recipe.output, key -> new ArrayList<>()).add(recipe);
      var items = new HashSet<String>();
      for (var ingredient : recipe.ingredients) items.addAll(ingredient.choices);
      for (String item : items) uses.computeIfAbsent(item, key -> new ArrayList<>()).add(recipe);
    }
  }

  public Recipe get(String id) { return recipes.get(id); }

  public Collection<Recipe> recipes() { return Collections.unmodifiableCollection(recipes.values()); }

  public List<Recipe> produces(String item) { return outputs.getOrDefault(item, List.of()); }

  public List<Recipe> uses(String item) { return uses.getOrDefault(item, List.of()); }

  public Checklist calculate(Recipe recipe, int batches, Map<String, Long> inventory) {
    var plan = new Plan(inventory);
    for (Ingredient ingredient : recipe.ingredients) {
      long needed = (long) ingredient.amount * Math.clamp(batches, 1, 9999);
      plan.require(ingredient, needed, new HashSet<>(Set.of(recipe.output)));
    }
    var materials = new ArrayList<Material>();
    plan.lines.forEach((id, line) -> materials.add(new Material(id, line[0], line[1])));
    return new Checklist(List.copyOf(materials), plan.limited);
  }

  private static boolean raw(String id) {
    return id.startsWith("oraxen:particule_") || id.startsWith("minecraft:")
        && (id.endsWith("_ingot") || id.endsWith("_log") || id.endsWith("_wood")
            || id.endsWith("_stem") || id.endsWith("_hyphae"));
  }

  private final class Plan {
    final Map<String, Long> inventory;
    final Map<String, Long> surplus = new HashMap<>();
    final Map<String, long[]> lines = new LinkedHashMap<>();
    int remaining = 30_000;
    boolean limited;

    Plan(Map<String, Long> inventory) { this.inventory = new HashMap<>(inventory); }

    void require(Ingredient ingredient, long amount, Set<String> path) {
      // Different acceptable stacks can cover different cells of the same ingredient.
      for (String option : ingredient.choices) {
        amount -= take(surplus, option, amount);
        long present = take(inventory, option, amount);
        if (present > 0) line(option, present, present);
        amount -= present;
        if (amount == 0) return;
      }
      String chosen = ingredient.choices.stream().filter(id -> !path.contains(id))
          .findFirst().orElse(ingredient.choices.getFirst());
      require(chosen, amount, path);
    }

    void require(String item, long amount, Set<String> path) {
      if (--remaining < 0 || amount > 1_000_000_000L) {
        limited = true;
        line(item, amount, 0);
        return;
      }
      amount -= take(surplus, item, amount);
      if (amount == 0) return;
      long present = take(inventory, item, amount);
      if (present > 0) line(item, present, present);
      long missing = amount - present;
      if (missing == 0) return;
      Recipe recipe = null;
      if (!raw(item) && !path.contains(item) && path.size() < 32) {
        for (Recipe candidate : produces(item))
          if (candidate.expandable && candidate.ingredients.stream().noneMatch(
                  ingredient -> ingredient.choices.stream().allMatch(id -> id.equals(item) || path.contains(id)))) {
            recipe = candidate;
            break;
          }
      }
      if (recipe == null) {
        limited |= path.size() >= 32;
        line(item, missing, 0);
        return;
      }
      path.add(item);
      long batches = Math.ceilDiv(missing, recipe.amount);
      for (Ingredient ingredient : recipe.ingredients)
        require(ingredient, batches * ingredient.amount, path);
      path.remove(item);
      surplus.merge(item, batches * recipe.amount - missing, Long::sum);
    }

    private long take(Map<String, Long> supply, String item, long demand) {
      long available = supply.getOrDefault(item, 0L), used = Math.min(available, demand);
      if (used > 0) supply.put(item, available - used);
      return used;
    }

    private void line(String item, long needed, long present) {
      long[] amounts = lines.computeIfAbsent(item, key -> new long[2]);
      amounts[0] += needed;
      amounts[1] += present;
    }
  }

  public static String searchKey(String text) {
    return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "").replace('_', ' ').strip();
  }
}
