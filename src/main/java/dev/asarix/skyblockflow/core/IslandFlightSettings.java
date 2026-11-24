package dev.asarix.skyblockflow.core;

import java.util.*;

public final class IslandFlightSettings {
  public boolean enabled = true;
  public Map<String, Home> homes = new LinkedHashMap<>();
  public static final class Home {
    public String server = "", dimension = "";
    public double centerX, centerZ, size;
    public boolean valid() {
      return server != null && dimension != null && !dimension.isBlank() && server.length() <= 160
          && Double.isFinite(centerX) && Double.isFinite(centerZ) && Double.isFinite(size) && size >= 16 && size <= 4096;
    }
    public boolean contains(double x, double z) { return Math.abs(x-centerX) <= size/2 && Math.abs(z-centerZ) <= size/2; }
  }
  public void normalize() {
    if (homes == null) homes = new LinkedHashMap<>();
    homes.entrySet().removeIf(e -> e.getKey() == null || e.getKey().length() > 240 || e.getValue() == null || !e.getValue().valid());
    while (homes.size() > 32) homes.remove(homes.keySet().iterator().next());
  }
}
