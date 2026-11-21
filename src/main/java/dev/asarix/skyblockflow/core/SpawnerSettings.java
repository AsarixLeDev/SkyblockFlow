package dev.asarix.skyblockflow.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SpawnerSettings {
  public boolean warnings = true, ignoreSpawnerObstructions;
  public boolean highlight;
  public boolean glowThroughBlocks;
  public boolean spheres;
  public boolean spheresOutOfRangeOnly;
  public int sphereOpacity = 10;
  public Map<String, Integer> menuRanges = new LinkedHashMap<>();

  public void normalize() {
    sphereOpacity = Math.clamp(sphereOpacity, 0, 100);
    if (menuRanges == null) menuRanges = new LinkedHashMap<>();
    menuRanges.values().removeIf(r -> r == null || !SpawnerMenu.validRadius(r));
    trim();
  }

  public boolean remember(String key, int radius) {
    if (Integer.valueOf(radius).equals(menuRanges.get(key))) return false;
    menuRanges.remove(key);
    menuRanges.put(key, radius);
    trim();
    return true;
  }

  private void trim() {
    while (menuRanges.size() > 2048) {
      var oldest = menuRanges.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
  }
}
