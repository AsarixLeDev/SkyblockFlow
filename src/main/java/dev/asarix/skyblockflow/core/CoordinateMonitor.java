package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CoordinateAlert;
import java.util.*;
import java.util.function.Consumer;

/** Sounds once on entry; staying inside never repeats, leaving rearms the alert. */
public final class CoordinateMonitor {
  private final Set<String> inside = new HashSet<>();

  public void reset() {
    inside.clear();
  }

  public void forget(String id) {
    inside.remove(id);
  }

  public void tick(
      List<CoordinateAlert> alerts,
      String dimension,
      double x,
      double y,
      double z,
      Consumer<CoordinateAlert> play) {
    Set<String> current = new HashSet<>();
    for (CoordinateAlert alert : alerts) {
      if (alert.enabled
          && (alert.dimension.isEmpty() || alert.dimension.equals(dimension))
          && alert.region.contains(x, y, z)) {
        current.add(alert.id);
        if (inside.add(alert.id)) play.accept(alert);
      }
    }
    inside.retainAll(current);
  }
}
