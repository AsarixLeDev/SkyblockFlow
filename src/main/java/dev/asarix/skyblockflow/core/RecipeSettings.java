package dev.asarix.skyblockflow.core;

import java.util.LinkedHashSet;
import java.util.Set;

public final class RecipeSettings {
  public enum Inspection { RECIPE_HELPER, EVERYWHERE, OFF }
  public boolean enabled = true;
  public Inspection inspection = Inspection.RECIPE_HELPER;
  public Set<String> pins = new LinkedHashSet<>();
  public boolean includeContainer, showChecklist, inventoryDockHidden, hideBackgroundOverlays;
  public String checklistRecipe = "";
  public int batches = 1;

  public void normalize() {
    if (inspection == null) inspection = Inspection.RECIPE_HELPER;
    if (pins == null) pins = new LinkedHashSet<>();
    pins.removeIf(id -> id == null || id.isBlank() || id.length() > 400);
    while (pins.size() > 1024) pins.remove(pins.iterator().next());
    if (checklistRecipe == null || checklistRecipe.length() > 400) checklistRecipe = "";
    batches = Math.clamp(batches, 1, 9999);
  }
}
