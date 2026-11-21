package dev.asarix.skyblockflow.core;

import java.util.*;

/** A checkpoint is released after exactly X subsequent distinct checkpoint visits. */
public final class WaypointVisits {
  private final LinkedHashMap<String, Long> visited = new LinkedHashMap<>();
  private final Set<String> inside = new HashSet<>();
  private long serial;

  public boolean consumed(String id, int history) {
    Long at = visited.get(id);
    return at != null && serial - at < history;
  }

  public boolean visit(String id, int history) {
    if (inside.contains(id) || consumed(id, history)) return false;
    inside.add(id);
    visited.put(id, ++serial);
    visited.entrySet().removeIf(e -> serial - e.getValue() >= history);
    return true;
  }

  public void outside(Set<String> currentlyInside) {
    inside.retainAll(currentlyInside);
  }

  public void clearConsumed() {
    visited.clear();
    serial = 0;
  }

  public void clear() {
    visited.clear();
    inside.clear();
    serial = 0;
  }

  public int count(int history) {
    return (int) visited.keySet().stream().filter(id -> consumed(id, history)).count();
  }
}
