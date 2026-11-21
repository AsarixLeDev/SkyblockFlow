package dev.asarix.skyblockflow.core;

import java.util.*;

/** Hides only bounded stats replies to lookups initiated by the measurement flow. */
public final class JobStatsReplyFilter {
  private final Map<String, Long> pending = new LinkedHashMap<>();
  private String active = "";
  private int rows;

  public void reset() { pending.clear(); active = ""; rows = 0; }

  public void expect(String player, long now, long timeout) {
    pending.entrySet().removeIf(e -> e.getValue() < now);
    if (pending.size() >= 8) pending.remove(pending.keySet().iterator().next());
    pending.put(player, now + Math.clamp(timeout, 2000, 60000) + 5000);
  }

  public boolean capture(String message, long now) {
    pending.entrySet().removeIf(e -> e.getValue() < now);
    if (!pending.containsKey(active)) { active = ""; rows = 0; }
    if (pending.isEmpty() || message == null || message.length() > 16384) return false;
    boolean any = false, all = true;
    for (String raw : message.split("\\n", -1)) {
      String plain = raw.replaceAll("§[0-9a-fk-orxA-FK-ORX]", "").strip();
      String player = pending.keySet().stream().filter(p -> JobMessages.statsHeader(plain, p))
          .findFirst().orElse("");
      if (!player.isEmpty()) { active = player; rows = 0; any = true; continue; }
      boolean separator = plain.isEmpty() || plain.matches("[─━═_\\-= ]+");
      if (separator) {
        any = true;
        if (!active.isEmpty() && rows > 0 && !plain.isEmpty()) {
          pending.remove(active); active = ""; rows = 0;
        }
      } else if (!active.isEmpty() && rows < 16 && JobMessages.statsLine(raw)) {
        rows++; any = true;
      } else {
        // Another player's header or an unrelated announcement cannot become part of this reply.
        active = ""; rows = 0; all = false;
      }
    }
    return any && all;
  }
}
