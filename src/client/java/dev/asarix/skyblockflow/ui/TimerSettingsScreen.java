package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import net.minecraft.client.gui.screens.Screen;

public final class TimerSettingsScreen extends FormScreen {
  public TimerSettingsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Timer alarms");
  }

  @Override
  protected boolean readOnly() {
    return true;
  }

  @Override
  protected String description() {
    return "Start a one-shot or repeating timer. Leaving the server stops timers.";
  }

  @Override
  protected void buildForm() {
    action("+ New timer", 0, 0, bodyW, () -> minecraft.gui.setScreen(new Editor(app, this, null)));
    int y = 40;
    for (var timer : app.store.config.timers) {
      boolean running = app.timers.clock(timer.id).running();
      section(
          timer.name
              + " · "
              + JobXp.duration(
                  running
                      ? app.timers.clock(timer.id).remaining(app.timers.now())
                      : timer.durationMs)
              + (timer.loop ? " · Repeating" : " · Once"),
          y);
      action(
          running ? "Stop" : "Start",
          0,
          y + 24,
          bodyW / 2 - 4,
          () -> {
            if (running) app.timers.stop(timer.id);
            else app.timers.start(timer);
            rebuildWidgets();
          });
      action(
          "Edit",
          bodyW / 2 + 4,
          y + 24,
          bodyW / 2 - 4,
          () -> minecraft.gui.setScreen(new Editor(app, this, timer)));
      y += 66;
    }
    label("Sleep alarms → choose whether timers loop their alarm sound until silenced.", 0, y);
  }

  private int ticks;

  @Override
  public void tick() {
    if (++ticks % 20 == 0) rebuildWidgets();
  }

  @Override
  protected void commit() {
    onClose();
  }

  private static class Editor extends FormScreen {
    final TimerSettings original, draft;

    Editor(SkyblockFlowClient app, Screen parent, TimerSettings original) {
      super(app, parent, "Timer");
      this.original = original;
      draft =
          original == null ? new TimerSettings() : ConfigStore.copy(original, TimerSettings.class);
    }

    @Override
    protected void buildForm() {
      field("name", "Name", draft.name, 0);
      field("seconds", "Duration (seconds)", "" + draft.durationMs / 1000, 46);
      toggle("Restart timer after each alarm", draft.loop, 96, () -> draft.loop = !draft.loop);
      action(
          "Sound · " + draft.soundKind,
          0,
          130,
          bodyW,
          () -> minecraft.gui.setScreen(new SoundPickerScreen(app, this, draft)));
      field("volume", "Volume (0–1)", "" + draft.volume, 168);
      field("pitch", "Pitch (0.5–2)", "" + draft.pitch, 214);
      if (original != null)
        action(
            "Delete timer",
            0,
            268,
            bodyW,
            () -> {
              app.timers.stop(original.id);
              app.store.config.timers.remove(original);
              app.save();
              closeAfterSave();
            });
    }

    @Override
    protected void commit() {
      draft.name = value("name").strip();
      draft.durationMs = number("seconds", 1, 604800) * 1000;
      draft.volume = decimal("volume", 0, 1);
      draft.pitch = decimal("pitch", .5f, 2);
      ConfigStore.validateTimer(draft);
      if (original == null) app.store.config.timers.add(draft);
      else {
        app.store.config.timers.set(app.store.config.timers.indexOf(original), draft);
        app.timers.stop(draft.id);
      }
      if (app.save()) onClose();
    }
  }
}
