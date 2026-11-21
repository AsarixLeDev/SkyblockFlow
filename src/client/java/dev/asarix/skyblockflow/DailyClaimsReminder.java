package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.nio.file.*;
import java.time.ZonedDateTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

public final class DailyClaimsReminder {
  private final SkyblockFlowClient app;
  private final Path file;
  private final DailyClaims reminder;
  private long nextCheck;
  private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(15_000);

  public DailyClaimsReminder(SkyblockFlowClient app) {
    this.app = app;
    file = app.store.directory().resolve("daily-claims-date.txt");
    String saved = "";
    try { if (Files.exists(file)) saved = Files.readString(file).strip(); }
    catch (java.io.IOException e) { app.error("Could not read daily reminder: " + e.getMessage()); }
    reminder = new DailyClaims(saved);
  }

  public void tick() {
    long now = System.nanoTime();
    if (now < nextCheck) return;
    nextCheck = now + 1_000_000_000L;
    var config = app.store.config;
    var style = config.alarmStyles.get(AlarmKind.DAILY_CLAIMS);
    if (!reminder.due(ZonedDateTime.now(java.time.ZoneId.of("Europe/Paris")), config.tools.dailyClaims
        && config.enabled(Feature.ALARMS) && (style == null || style.enabled))) return;
    try {
      Files.createDirectories(file.getParent());
      Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temporary, reminder.savedDay());
      try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    } catch (java.io.IOException e) { app.error("Could not save daily reminder: " + e.getMessage()); }
    var mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null)
      SystemToast.add(mc.gui.toastManager(), TOAST, Component.literal("DO YOUR DAILY CLAIMS"),
          Component.literal("Five minutes until the Paris reset"));
    app.notifyGeneral("Daily claims", "DO YOUR DAILY CLAIMS", "", new SoundSettings());
  }
}
