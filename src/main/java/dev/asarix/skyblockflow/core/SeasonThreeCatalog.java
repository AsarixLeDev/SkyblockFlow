package dev.asarix.skyblockflow.core;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SeasonThreeCatalog {
  public record ShopQuote(String shop, String unit, BigDecimal buy, BigDecimal sell) {}
  public record Item(String name, String model, String description, List<ShopQuote> prices) {}
  public record Data(Map<String, Item> items, List<RecipeCatalog.Recipe> recipes) {}

  public static Data load() {
    var path = "/assets/skyblockflow/season-3.json";
    try (var stream = SeasonThreeCatalog.class.getResourceAsStream(path)) {
      if (stream == null) throw new IllegalStateException("Missing Season 3 recipe data");
      try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        return new Gson().fromJson(reader, Data.class);
      }
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Could not read Season 3 recipe data", failure);
    }
  }
}
