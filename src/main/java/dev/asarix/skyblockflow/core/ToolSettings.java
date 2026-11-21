package dev.asarix.skyblockflow.core;

import java.util.*;

public final class ToolSettings {
  public boolean calculator = true, shopValues = true, clock = true, fancyClock = true;
  public boolean minerals = true, artisan, ancientDebris, esports;
  public boolean esportsHideInactive = true, esportsShowUpdated = false;
  public boolean dailyClaims = true;
  public int artisanTiers = 3;
  public Set<String> tracked =
      new LinkedHashSet<>(
          List.of(
              "coal",
              "iron",
              "copper",
              "gold",
              "redstone",
              "lapis",
              "diamond",
              "emerald",
              "quartz",
              "ancient_debris"));
  public Map<String, HudSettings.Placement> layouts = new LinkedHashMap<>();
  public RecipeSettings recipes = new RecipeSettings();
  public EnergyPlan energy = new EnergyPlan();

  public HudSettings.Placement layout(String key) {
    return layouts.computeIfAbsent(
        key,
        k -> {
          var p = new HudSettings.Placement();
          p.x = k.equals("esports") ? .5 : .02;
          p.y =
              switch (k) {
                case "clock" -> .03;
                case "minerals" -> .15;
                case "artisan" -> .56;
                default -> .70;
              };
          return p;
        });
  }

  public void normalize() {
    artisanTiers = Math.clamp(artisanTiers, 1, 12);
    if (tracked == null) tracked = new LinkedHashSet<>();
    if (layouts == null) layouts = new LinkedHashMap<>();
    layouts.values().removeIf(Objects::isNull);
    for (var p : layouts.values()) {
      p.x = Double.isFinite(p.x) ? Math.clamp(p.x, 0, 1) : 0;
      p.y = Double.isFinite(p.y) ? Math.clamp(p.y, 0, 1) : 0;
      p.scale = Float.isFinite(p.scale) ? Math.clamp(p.scale, .5f, 3) : 1;
    }
    if (recipes == null) recipes = new RecipeSettings();
    recipes.normalize();
    if (energy == null) energy = new EnergyPlan();
    energy.normalize();
  }
}
