package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import net.minecraft.client.gui.screens.Screen;

public final class AlarmHub extends FormScreen {
  public AlarmHub(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Alarms");
  }

  protected boolean readOnly() {
    return true;
  }

  protected String description() {
    return "Choose what you see and hear when an alarm fires.";
  }

  protected void buildForm() {
    int i = 0, cols = bodyW >= 350 ? 2 : 1, w = (bodyW - 6 * (cols - 1)) / cols;
    action(
        "Ancient debris detection",
        0,
        0,
        bodyW,
        () -> minecraft.gui.setScreen(new ToolsScreen(app, this, "Ancient debris")));
    i += cols;
    for (var kind : AlarmKind.values()) {
      action(
          kind.label,
          i % cols * (w + 6),
          i / cols * 30,
          w,
          () -> minecraft.gui.setScreen(new Editor(app, this, kind)));
      i++;
    }
  }

  protected void commit() {
    onClose();
  }

  static final class Editor extends FormScreen {
    private final AlarmKind kind;
    private final AlarmPresentation draft;
    private final String key;

    Editor(SkyblockFlowClient app, Screen parent, AlarmKind kind) {
      this(app, parent, kind, "", kind.label);
    }

    Editor(SkyblockFlowClient app, Screen parent, AlarmKind kind, String key, String label) {
      super(app, parent, label);
      this.key = key;
      this.kind = kind;
      var defaults = new AlarmPresentation();
      defaults.enabled = kind != AlarmKind.RANK;
      draft =
          ConfigStore.copy(
              app.store.config.namedAlarmStyles.getOrDefault(
                  key, app.store.config.alarmStyles.getOrDefault(kind, defaults)),
              AlarmPresentation.class);
    }

    protected void buildForm() {
      page("Display");
      toggle("Enable alarm", draft.enabled, 0, () -> draft.enabled = !draft.enabled);
      field("title", "Title", draft.title, 32);
      field("sub", "Subtitle", draft.subtitle, 76);
      field("bar", "Action bar", draft.actionBar, 120);
      label(
          "Use {title} or {subtitle} for the original alert text. Leave a field blank to hide it.",
          0,
          164);
      if (kind == AlarmKind.CHAT) label("Choose each alert’s sound in Chat Alerts.", 0, 182);
      if (kind == AlarmKind.ROCKET)
        label(
            "Warn when boarding with less than 60 seconds of air, or when the server blocks launch"
                + " for missing gear.",
            0,
            198);
      if (key.isEmpty()
          && (kind == AlarmKind.CHAT || kind == AlarmKind.COORDINATES || kind == AlarmKind.TIMER)) {
        page("Individual alarms");
        int y = 0;
        java.util.List<? extends SoundSettings> alarms =
            switch (kind) {
              case CHAT -> app.store.config.alerts;
              case COORDINATES -> app.store.config.coordinateAlerts;
              case TIMER -> app.store.config.timers;
              default -> java.util.List.of();
            };
        for (var a : alarms) {
          String name =
              switch (a) {
                case AlertRule r -> r.name;
                case CoordinateAlert r -> r.name;
                case TimerSettings r -> r.name;
                default -> kind.label;
              };
          action(
              name,
              0,
              y,
              bodyW,
              () ->
                  minecraft.gui.setScreen(
                      new Editor(app, this, kind, AlarmPresentation.key(a), name)));
          y += 30;
        }
        if (alarms.isEmpty()) label("No alarms configured.", 0, 0);
      } else if (!key.isEmpty()) {
        action(
            "Use alarm type defaults",
            0,
            198,
            bodyW,
            () -> {
              app.store.config.namedAlarmStyles.remove(key);
              if (app.save()) closeAfterSave();
            });
      }
      if (kind == AlarmKind.CHAT) return;
      page("Sound");
      toggle(
          "Override trigger sound",
          draft.customSound,
          0,
          () -> draft.customSound = !draft.customSound);
      if (draft.customSound) {
        action(
            "Choose sound · " + Ui.readable(draft.soundKind),
            0,
            34,
            bodyW,
            () -> minecraft.gui.setScreen(new SoundPickerScreen(app, this, draft)));
        action(
            "Silent",
            0,
            64,
            bodyW,
            () -> {
              draft.soundKind = SoundKind.SILENT;
              rebuildWidgets();
            });
        field("v", "Volume (0–1)", String.valueOf(draft.volume), 98);
        field("p", "Pitch (0.5–2)", String.valueOf(draft.pitch), 144);
      }
    }

    protected void commit() {
      draft.title = value("title");
      draft.subtitle = value("sub");
      draft.actionBar = value("bar");
      if (kind != AlarmKind.CHAT && draft.customSound) {
        draft.volume = decimal("v", 0, 1);
        draft.pitch = decimal("p", .5f, 2);
        ConfigStore.validateSound(draft);
      }
      if (key.isEmpty()) app.store.config.alarmStyles.put(kind, draft);
      else app.store.config.namedAlarmStyles.put(key, draft);
      if (app.save()) closeAfterSave();
    }
  }
}
