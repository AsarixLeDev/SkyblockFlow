package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.ConfigStore;
import dev.asarix.skyblockflow.core.Models.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.util.Util;

public final class SoundPickerScreen extends FlowScreen {
  private final SoundSettings draft;
  private SoundKind kind;
  private String selected = "", query = "";
  private List<String> all = List.of(), filtered = List.of();
  private int scroll;
  private boolean picked;

  public SoundPickerScreen(SkyblockFlowClient app, Screen parent, SoundSettings draft) {
    super(app, parent, "Sounds");
    this.draft = draft;
    kind = draft.soundKind == SoundKind.MP3 ? SoundKind.MP3 : SoundKind.VANILLA;
    selected = draft.sound;
  }

  @Override
  protected void init() {
    super.init();
    button(width - 76, 10, 62, "Back", this::onClose);
    button(
        14,
        42,
        84,
        "Vanilla",
        () -> {
          kind = SoundKind.VANILLA;
          scroll = 0;
          rebuildWidgets();
        },
        kind == SoundKind.VANILLA);
    button(
        104,
        42,
        84,
        "MP3 files",
        () -> {
          kind = SoundKind.MP3;
          scroll = 0;
          rebuildWidgets();
        },
        kind == SoundKind.MP3);
    button(
        194,
        42,
        80,
        "Silent",
        () -> {
          kind = SoundKind.SILENT;
          draft.soundKind = SoundKind.SILENT;
          onClose();
        });
    edit(14, 78, width - 28, query)
        .setResponder(
            q -> {
              query = q;
              filter();
            });
    int w = (width - 40) / 3;
    button(
        14,
        height - 60,
        w,
        "Use sound",
        () -> {
          if (selected.isEmpty() || !all.contains(selected)) {
            notice = "Select a sound first";
            return;
          }
          draft.soundKind = kind;
          draft.sound = selected;
          if (draft instanceof CelebrationSettings celebration && kind == SoundKind.MP3)
            app.sounds.analyzeSong(celebration, celebration.edit);
          onClose();
        },
        true);
    // Play just the selected sound, without Minecraft's generic button click over it.
    var preview = new FlowButton(20 + w, height - 60, w, 22, "Preview", () -> {
      if (!all.contains(selected)) {
        notice = "Select a sound first";
        return;
      }
      SoundSettings sample = ConfigStore.copy(draft, SoundSettings.class);
      sample.soundKind = kind;
      sample.sound = selected;
      app.sounds.play(sample);
    }, false) {
      @Override
      public void playDownSound(SoundManager manager) {}
    };
    addWidget(preview);
    chrome.add(preview);
    button(
        26 + 2 * w,
        height - 60,
        w,
        "Open folder",
        () -> Util.getPlatform().openPath(app.store.sounds()));
    refresh();
  }

  private void refresh() {
    all = kind == SoundKind.MP3 ? app.sounds.files() : app.sounds.vanillaSounds();
    filter();
  }

  private void filter() {
    String q = query.toLowerCase(Locale.ROOT);
    filtered = all.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains(q)).toList();
    scroll = 0;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll =
        Math.clamp(
            scroll - (int) (sy * 26), 0, Math.max(0, filtered.size() * 24 - (height - 68 - 108)));
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (super.mouseClicked(e, twice)) return true;
    if (e.button() == 0 && inside(e.x(), e.y(), 14, 108, width - 28, height - 176)) {
      int i = (int) (e.y() - 108 + scroll) / 24;
      if (i < filtered.size()) {
        selected = filtered.get(i);
        picked = true;
        return true;
      }
    }
    return false;
  }

  @Override
  public void onFilesDrop(List<Path> paths) {
    notice = "Importing MP3 files...";
    CompletableFuture.runAsync(
        () -> {
          int count = 0;
          String failure = "";
          for (Path p : paths)
            try {
              app.sounds.importFile(p);
              count++;
            } catch (Exception e) {
              failure = e.getMessage();
            }
          String result =
              count + " MP3 file(s) imported" + (failure.isBlank() ? "" : " · " + failure);
          minecraft.execute(
              () -> {
                kind = SoundKind.MP3;
                notice = result;
                rebuildWidgets();
              });
        });
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(
        g,
        kind == SoundKind.MP3
            ? "Drop .mp3 files onto this screen, or add them to the sounds folder."
            : "Search any vanilla sound ID, choose one, then preview it.");
    g.enableScissor(14, 108, width - 14, height - 68);
    for (int i = 0; i < filtered.size(); i++) {
      int y = 108 + i * 24 - scroll;
      if (y + 22 < 108 || y > height - 68) continue;
      String s = filtered.get(i);
      g.fill(14, y, width - 14, y + 22, s.equals(selected) ? 0xff304b60 : CARD);
      text(g, cut(s, width - 48), 23, y + 7, s.equals(selected) ? ACCENT : TEXT);
    }
    if (filtered.isEmpty())
      text(g, kind == SoundKind.MP3 ? "Drop an MP3 here to import it." : "No matching sounds.",
          20, 119, MUTED);
    g.disableScissor();
    renderChrome(g, mx, my, dt);
  }

  @Override
  public void onClose() {
    app.sounds.stopPreview();
    if (kind == SoundKind.SILENT) draft.soundKind = kind;
    else if (picked && all.contains(selected)) {
      draft.soundKind = kind;
      draft.sound = selected;
    }
    app.save();
    minecraft.gui.setScreen(parent);
  }
}
