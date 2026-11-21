package dev.asarix.skyblockflow.core;

import java.time.*;

/** One reminder per local calendar day, including a late start during its final five minutes. */
public final class DailyClaims {
  private LocalDate lastDay;

  public DailyClaims(String savedDay) {
    try { lastDay = LocalDate.parse(savedDay); }
    catch (RuntimeException ignored) { }
  }

  public boolean due(ZonedDateTime now, boolean enabled) {
    if (!enabled || now.toLocalTime().isBefore(LocalTime.of(23, 55))
        || lastDay != null && !now.toLocalDate().isAfter(lastDay)) return false;
    lastDay = now.toLocalDate();
    return true;
  }

  public String savedDay() { return lastDay == null ? "" : lastDay.toString(); }
}
