package dev.asarix.skyblockflow.core;

public final class ExcaliaApiSettings {
  public boolean leaderboards = true, market = true, shopPrices = true;
  public boolean refreshActiveJob = true;
  public int activeJobMinutes = 1;

  public long activeJobIntervalMs() { return Math.clamp(activeJobMinutes, 1, 60) * 60_000L; }
}
