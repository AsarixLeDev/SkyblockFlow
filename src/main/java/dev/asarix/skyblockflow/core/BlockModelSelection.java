package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import java.util.*;

/** Resolves only model branches that match the selected block's own state. */
public final class BlockModelSelection {
  private BlockModelSelection() {}

  public static List<String> models(JsonObject definition, Map<String, String> state) {
    Set<String> result = new LinkedHashSet<>();
    if (definition.get("variants") instanceof JsonObject variants) {
      for (var variant : variants.entrySet()) {
        boolean matches = true;
        for (String pair : variant.getKey().split(",")) {
          if (pair.isEmpty()) continue;
          String[] terms = pair.split("=", 2);
          if (terms.length != 2 || !valueMatches(state.get(terms[0]), terms[1])) { matches = false; break; }
        }
        if (matches) collect(variant.getValue(), result, 0);
      }
    }
    if (definition.get("multipart") instanceof JsonArray parts) {
      for (var element : parts) if (element instanceof JsonObject part) {
        if (!part.has("when") || condition(part.get("when"), state, 0)) collect(part.get("apply"), result, 0);
      }
    }
    return List.copyOf(result);
  }

  private static boolean condition(JsonElement element, Map<String, String> state, int depth) {
    if (depth > 32 || !(element instanceof JsonObject object) || object.isEmpty()) return false;
    for (var entry : object.entrySet()) {
      String key = entry.getKey();
      if (key.equals("OR") || key.equals("AND")) {
        if (!(entry.getValue() instanceof JsonArray children) || children.isEmpty()) return false;
        boolean any = false, all = true;
        for (var child : children) { boolean match = condition(child, state, depth + 1); any |= match; all &= match; }
        if (!(key.equals("OR") ? any : all)) return false;
      } else if (!(entry.getValue() instanceof JsonPrimitive value)
          || !valueMatches(state.get(key), value.getAsString())) return false;
    }
    return true;
  }

  private static boolean valueMatches(String actual, String alternatives) {
    if (actual == null || alternatives.isEmpty()) return false;
    boolean negate = alternatives.startsWith("!");
    String values = negate ? alternatives.substring(1) : alternatives;
    if (values.isEmpty()) return false;
    boolean match = Arrays.asList(values.split("\\|", -1)).contains(actual);
    return negate != match;
  }

  private static void collect(JsonElement element, Set<String> result, int depth) {
    if (depth > 32 || result.size() >= 256) return;
    if (element instanceof JsonArray alternatives) {
      for (var alternative : alternatives) collect(alternative, result, depth + 1);
    } else if (element instanceof JsonObject model && model.get("model") instanceof JsonPrimitive name && name.isString()) {
      String value = name.getAsString();
      if (!value.isBlank() && value.length() <= 512) result.add(value);
    }
  }
}
