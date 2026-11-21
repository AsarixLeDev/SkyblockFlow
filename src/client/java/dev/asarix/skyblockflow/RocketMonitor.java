package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;

public final class RocketMonitor {
  private final SkyblockFlowClient app;
  private int seconds = -1;
  private UUID bar;
  private long removedAt, lastAlarm;
  private long rocketInteraction;

  void interacted(Entity entity) {
    if (RocketOxygen.rocket(entity.getName().getString())) rocketInteraction = System.nanoTime();
  }

  private Entity vehicle;
  private Object menu;
  private long revision;
  private boolean warned, rocketSeat;

  RocketMonitor(SkyblockFlowClient app) {
    this.app = app;
  }

  void gauge(UUID id, String value) {
    int n = RocketOxygen.seconds(value);
    if (n >= 0) {
      bar = id;
      if (seconds != n) revision = -1;
      seconds = n;
      removedAt = 0;
    } else removed(id);
  }

  void removed(UUID id) {
    if (id.equals(bar)) {
      bar = null;
      removedAt = System.nanoTime();
    }
  }

  void reset() {
    seconds = -1;
    bar = null;
    removedAt = lastAlarm = rocketInteraction = 0;
    vehicle = null;
    menu = null;
    warned = rocketSeat = false;
  }

  void denial(String text) {
    if (RocketOxygen.denial(text)) warn("Check your suit and oxygen tank");
  }

  private boolean low() {
    return seconds >= 0
        && seconds < RocketOxygen.LAUNCH_RESERVE
        && (bar != null || System.nanoTime() - removedAt < 5_000_000_000L);
  }

  private void warn(String detail) {
    var now = System.nanoTime();
    if (warned || lastAlarm != 0 && now - lastAlarm < 3_000_000_000L || !app.alertsAllowed())
      return;
    warned = true;
    lastAlarm = now;
    app.notifyGeneral("Rocket oxygen", "Rocket oxygen", detail, new SoundSettings());
  }

  void tick(Minecraft mc) {
    if (mc.player == null) return;
    var current = mc.player.getVehicle();
    var screen = mc.gui.screen();
    if (current != vehicle) {
      vehicle = current;
      warned = false;
      rocketSeat =
          current != null
              && (RocketOxygen.rocket(current.getName().getString())
                  || rocketInteraction != 0
                      && System.nanoTime() - rocketInteraction < 3_000_000_000L);
    }
    boolean changed = screen != menu;
    if (changed) {
      menu = screen;
      revision = -1;
      if (current == null) warned = false;
    }
    if (rocketSeat && !warned && low()) warn(seconds + " s of oxygen · 60 s needed");
    if (warned
        || !(screen instanceof AbstractContainerScreen<?> chest)
        || revision == app.inventoryRevision) return;
    revision = app.inventoryRevision;
    // Container implementations attach 36 main-inventory/hotbar slots; skip all player slots
    // explicitly.
    if (chest.getMenu().slots.size() != 45) return;
    String title = JobXp.normalize(chest.getTitle().getString());
    boolean named = title.contains("destination"), station = false, centre = false, blocked = false;
    for (var slot : chest.getMenu().slots) {
      if (slot.container == mc.player.getInventory() || slot.getItem().isEmpty()) continue;
      var stack = slot.getItem();
      var name = JobXp.normalize(stack.getHoverName().getString());
      station |= name.contains("station spatiale");
      centre |= name.contains("centre spatial");
      var lore = stack.get(DataComponents.LORE);
      if (lore != null) {
        var lines = new StringBuilder(name);
        for (var l : lore.lines()) lines.append(' ').append(l.getString());
        blocked |= RocketOxygen.blocked(lines.toString());
      } else blocked |= RocketOxygen.blocked(name);
    }
    if ((station && centre || named && (station || centre || blocked)) && (blocked || low()))
      warn(low() ? seconds + " s of oxygen · 60 s needed" : "Check your suit and oxygen tank");
  }
}
