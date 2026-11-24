package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import dev.asarix.skyblockflow.core.RecipeCatalog.*;
import java.io.*;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reads the vanilla recipe data shipped with the running Minecraft version. */
public final class VanillaRecipeCatalog {
  private static List<Recipe> cached;
  private VanillaRecipeCatalog() {}
  public static synchronized List<Recipe> load() {
    if (cached != null) return cached;
    var recipes = new ArrayList<Recipe>();
    try {
      var resource = VanillaRecipeCatalog.class.getResource("/data/minecraft/recipe/iron_block.json");
      if (resource == null) return cached = List.of();
      List<String> paths;
      if (resource.openConnection() instanceof JarURLConnection connection) {
        paths = connection.getJarFile().stream().map(java.util.jar.JarEntry::getName)
            .filter(n -> n.startsWith("data/minecraft/recipe/") && n.endsWith(".json")).sorted().limit(10000).toList();
      } else if (resource.getProtocol().equals("file")) {
        try (var files = Files.list(Path.of(resource.toURI()).getParent())) {
          paths = files.filter(p -> p.getFileName().toString().endsWith(".json"))
              .map(p -> "data/minecraft/recipe/" + p.getFileName()).sorted().limit(10000).toList();
        }
      } else paths = List.of();
      var tags = new HashMap<String,List<String>>();
      for (String path : paths) {
        try {
          var root = json(path);
          String type = root.get("type").getAsString();
          if (!type.equals("minecraft:crafting_shaped") && !type.equals("minecraft:crafting_shapeless")) continue;
          var result = root.getAsJsonObject("result");
          if (result.has("components")) continue;
          String output = result.get("id").getAsString();
          int amount = result.has("count") ? result.get("count").getAsInt() : 1;
          var cells = new ArrayList<Cell>();
          if (type.endsWith("shaped")) {
            var pattern = root.getAsJsonArray("pattern"); var keys = root.getAsJsonObject("key");
            for (int y = 0; y < pattern.size(); y++) {
              String row = pattern.get(y).getAsString();
              for (int x = 0; x < row.length(); x++) if (row.charAt(x) != ' ')
                cells.add(new Cell(x, y, new Ingredient(choices(keys.get("" + row.charAt(x)), tags, new HashSet<>()), 1)));
            }
          } else {
            var inputs = root.getAsJsonArray("ingredients");
            for (int i = 0; i < inputs.size(); i++) cells.add(new Cell(i % 3, i / 3,
                new Ingredient(choices(inputs.get(i), tags, new HashSet<>()), 1)));
          }
          if (cells.isEmpty() || cells.size() > 9) continue;
          var totals = new LinkedHashMap<List<String>, Integer>();
          for (var cell : cells) totals.merge(cell.ingredient().choices(), 1, Integer::sum);
          var ingredients = totals.entrySet().stream().map(e -> new Ingredient(e.getKey(), e.getValue())).toList();
          String id = "vanilla:" + path.substring("data/minecraft/recipe/".length(), path.length()-5);
          recipes.add(new Recipe(id, output, amount, Station.VANILLA, 3, ingredients, cells, "", true));
        } catch (RuntimeException | IOException ignored) { }
      }
    } catch (Exception ignored) { }
    return cached = List.copyOf(recipes);
  }
  private static JsonObject json(String path) throws IOException {
    try (var stream = VanillaRecipeCatalog.class.getResourceAsStream("/" + path)) {
      if (stream == null) throw new FileNotFoundException(path);
      try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }
  }
  private static List<String> choices(JsonElement input, Map<String,List<String>> tags, Set<String> visiting) throws IOException {
    if (input.isJsonArray()) {
      var result = new TreeSet<String>();
      for (var value : input.getAsJsonArray()) result.addAll(choices(value, tags, visiting));
      return List.copyOf(result);
    }
    if (input.isJsonObject()) input = input.getAsJsonObject().get("id");
    String id = input.getAsString();
    if (!id.startsWith("#")) return List.of(id);
    if (tags.containsKey(id)) return tags.get(id);
    if (!visiting.add(id) || visiting.size() > 32) return List.of();
    String[] location = id.substring(1).split(":",2);
    if (location.length != 2 || !location[0].equals("minecraft") || !location[1].matches("[a-z0-9_/]+")) return List.of();
    var values = json("data/minecraft/tags/item/" + location[1] + ".json").getAsJsonArray("values");
    var result = new TreeSet<String>();
    for (var value : values) result.addAll(choices(value, tags, visiting));
    visiting.remove(id); var resolved = List.copyOf(result); tags.put(id, resolved); return resolved;
  }
}
