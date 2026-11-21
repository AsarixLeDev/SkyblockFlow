package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;

public final class AlertEditorScreen extends FormScreen {
  private final AlertRule original, draft;
  private String testResult = "";

  public AlertEditorScreen(SkyblockFlowClient app, Screen parent, AlertRule original) {
    super(app, parent, original == null ? "New alert" : "Edit alert");
    this.original = original;
    draft = original == null ? new AlertRule() : ConfigStore.copy(original, AlertRule.class);
  }

  @Override
  protected void buildForm() {
    page("Match");
    field("name", "Alert name", draft.name, 0);
    action(
        "Enabled: " + (draft.enabled ? "ON" : "OFF"),
        0,
        46,
        bodyW / 2 - 4,
        () -> {
          draft.enabled = !draft.enabled;
          rebuildWidgets();
        });
    action(
        "Mode: " + Ui.readable(draft.mode) + "  >",
        bodyW / 2 + 4,
        46,
        bodyW / 2 - 4,
        () -> {
          draft.mode = draft.mode == MatchMode.TEMPLATE ? MatchMode.REGEX : MatchMode.TEMPLATE;
          rebuildWidgets();
        });
    field("pattern", "Chat pattern", draft.pattern, 82);
    label(
        draft.mode == MatchMode.TEMPLATE
            ? "Example: Je vends [item]  · Templates match the whole message."
            : "Example: ^Je vends (?<item>.+)$  · Java regex, find-match.",
        0,
        124);
    action(
        "Case sensitive: " + (draft.caseSensitive ? "ON" : "OFF"),
        0,
        147,
        bodyW / 2 - 4,
        () -> {
          draft.caseSensitive = !draft.caseSensitive;
          rebuildWidgets();
        });
    action(
        "Match: " + (draft.matchFullChat ? "FULL CHAT" : "MESSAGE BODY"),
        bodyW / 2 + 4,
        147,
        bodyW / 2 - 4,
        () -> {
          draft.matchFullChat = !draft.matchFullChat;
          rebuildWidgets();
        });
    page("Alert");
    field("title", "Title text", draft.title, 0);
    field("subtitle", "Subtitle text", draft.subtitle, 48);
    label("Reuse [item], [player], [sender], [message] or regex [1], [2]...", 0, 94);
    action(
        "Sound: " + draft.soundKind + " · Choose...",
        0,
        122,
        bodyW - 83,
        () -> {
          try {
            read();
            minecraft.gui.setScreen(new SoundPickerScreen(app, this, draft));
          } catch (Exception e) {
            notice = e.getMessage();
          }
        });
    action(
        "Preview",
        bodyW - 77,
        122,
        77,
        () -> {
          try {
            read();
            app.sounds.play(draft);
          } catch (Exception e) {
            notice = e.getMessage();
          }
        });
    label(draft.sound, 0, 152);
    int w = (bodyW - 16) / 3;
    field("volume", "Volume 0–1", "" + draft.volume, 0, 176, w);
    field("pitch", "Pitch .5–2", "" + draft.pitch, w + 8, 176, w);
    field("cooldown", "Cooldown ms", "" + draft.cooldownMs, 2 * (w + 8), 176, w);
    label("Pitch affects vanilla sounds. Cooldown only limits repeated notifications.", 0, 222);
    page("Test");
    field("test", "Test message", "Je vends des diamants", 0);
    field("sender", "Test sender", "Asarix", 48);
    action(
        "Test matching + sound",
        0,
        96,
        bodyW,
        () -> {
          try {
            read();
            Map<String, String> vars =
                new AlertPattern(draft).match(value("test"), value("sender"));
            testResult =
                vars == null ? "No match" : "Match: " + AlertPattern.render(draft.title, vars);
            if (vars != null) app.sounds.play(draft);
            notice = testResult;
            rebuildWidgets();
          } catch (Exception e) {
            notice = e.getMessage();
          }
        });
    label(testResult.isEmpty() ? "Preview the matching text and sound." : testResult, 0, 132);
    label("Signed chat supplies [sender]. Plain server messages may have no sender.", 0, 154);
    if (original != null)
      action(
          "Delete this alert",
          0,
          188,
          bodyW,
          () -> {
            app.store.config.alerts.remove(original);
            app.reloadPatterns();
            app.save();
            closeAfterSave();
          });
  }

  private void read() {
    draft.name = value("name").strip();
    draft.pattern = value("pattern");
    draft.title = value("title");
    draft.subtitle = value("subtitle");
    draft.volume = decimal("volume", 0, 1);
    draft.pitch = decimal("pitch", .5f, 2);
    draft.cooldownMs = number("cooldown", 0, 3600000);
    if (draft.name.isBlank()) throw new IllegalArgumentException("Enter an alert name");
  }

  @Override
  protected void commit() {
    read();
    new AlertPattern(draft);
    if (original == null) app.store.config.alerts.add(draft);
    else {
      int i = app.store.config.alerts.indexOf(original);
      if (i >= 0) app.store.config.alerts.set(i, draft);
    }
    app.reloadPatterns();
    if (app.save()) closeAfterSave();
    else notice = app.lastError;
  }
}
