package dev.asarix.skyblockflow;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;
import net.minecraft.client.Minecraft;

public final class TimerService {
  private final SkyblockFlowClient app;
  private final Map<String, TimerClock> clocks = new HashMap<>();
  public TimerService(SkyblockFlowClient app) { this.app = app; }
  public TimerClock clock(String id) { return clocks.computeIfAbsent(id, k -> new TimerClock()); }
  public long now() { return System.nanoTime() / 1000000; }
  public void start(TimerSettings timer) {
    if (Minecraft.getInstance().player == null) throw new IllegalStateException("Join a world to start a timer");
    clock(timer.id).start(now(), timer.durationMs); app.event("Timer started · " + timer.name);
  }
  public void stop(String id) { clock(id).stop(); }
  public void reset() { clocks.clear(); }
  public void tick() {
    if (!app.store.config.enabled(Feature.TIMERS)) { reset(); return; }
    for (var timer : app.store.config.timers)
      if (clock(timer.id).tick(now(), timer.durationMs, timer.loop))
        app.notifyGeneral("Timer", timer.name, "Time is up!" + (timer.loop ? " · Timer restarted" : ""), timer);
  }
}
