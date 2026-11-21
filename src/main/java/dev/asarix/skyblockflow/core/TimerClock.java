package dev.asarix.skyblockflow.core;

/** Monotonic countdown; a stalled client emits one alarm, never a catch-up burst. */
public final class TimerClock {
  private long deadline;
  private boolean running;
  public void start(long now, long duration) { deadline = now + duration; running = true; }
  public void stop() { running = false; }
  public boolean running() { return running; }
  public long remaining(long now) { return running ? Math.max(0, deadline - now) : 0; }
  public boolean tick(long now, long duration, boolean loop) {
    if (!running || now < deadline) return false;
    if (loop) deadline = now + duration; else running = false;
    return true;
  }
}
