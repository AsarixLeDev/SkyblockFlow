package dev.asarix.skyblockflow.core;

public final class ConnectionGrace {
  private long joinedAt = -1;

  public void joined(long now) {
    joinedAt = now;
  }

  public void disconnected() {
    joinedAt = -1;
  }

  public boolean allows(long now, long duration) {
    return remaining(now, duration) == 0;
  }

  public long remaining(long now, long duration) {
    return joinedAt < 0 ? 0 : Math.max(0, duration - Math.max(0, now - joinedAt));
  }
}
