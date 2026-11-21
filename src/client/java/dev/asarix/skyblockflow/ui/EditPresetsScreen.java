package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** A searchable collection: open an edit directly instead of copying an anonymous snapshot. */
public final class EditPresetsScreen extends FlowScreen {
  private final EditPresetStore store;
  private final CelebrationSettingsScreen editor;
  private final String activeId;
  private List<EditPresetStore.Preset> presets = List.of();
  private final List<AbstractWidget> rowButtons = new ArrayList<>();
  private String query = "";
  private int scroll;
  private static final int TOP = 112, ROW = 88;

  public EditPresetsScreen(
      SkyblockFlowClient app, CelebrationSettingsScreen editor, String activeId) {
    super(app, editor, "Edit presets");
    this.editor = editor;
    this.activeId = activeId;
    store = new EditPresetStore(app.store.directory());
  }

  @Override
  protected void init() {
    super.init();
    try {
      presets = store.list();
    } catch (Exception e) {
      presets = List.of();
      notice = e.getMessage();
    }
    button(width - 88, 10, 74, "Back", this::onClose);
    int createWidth = Math.min(156, (width - 48) / 2);
    var search = edit(20, 76, width - 48 - createWidth, query);
    search.setHint(Ui.label("Search presets or songs..."));
    search.setResponder(
        text -> {
          query = text;
          scroll = 0;
          rebuildRows();
        });
    button(
        width - 20 - createWidth, 76, createWidth, "Save current as new", editor::saveAsNew, true);
    rebuildRows();
    setInitialFocus(search);
  }

  private List<EditPresetStore.Preset> filtered() {
    return presets.stream()
        .filter(
            p ->
                (p.name() + " " + p.settings().sound)
                    .toLowerCase(Locale.ROOT)
                    .contains(query.toLowerCase(Locale.ROOT)))
        .toList();
  }

  private void rebuildRows() {
    for (var button : rowButtons) removeWidget(button);
    rowButtons.clear();
    var list = filtered();
    scroll = Math.clamp(scroll, 0, Math.max(0, list.size() * ROW - (height - TOP - 38)));
    int available = width - 64, bw = Math.min(96, (available - 18) / 4);
    for (int i = 0; i < list.size(); i++) {
      var p = list.get(i);
      int y = TOP + i * ROW - scroll + 49;
      rowButton(32, y, bw, "Edit", () -> editor.openPreset(p), true);
      rowButton(
          38 + bw,
          y,
          bw,
          "Preview",
          () -> {
            var settings = EditPresetStore.edit(p);
            app.celebrations.show(settings, "chasseur", 200, true);
            minecraft.gui.setScreen(new CelebrationSettingsScreen.Preview(app, this, settings));
          },
          false);
      rowButton(
          44 + bw * 2,
          y,
          bw,
          "Watch",
          () -> editor.continueAfterChanges(() -> watchSaved(p.id())),
          false);
      rowButton(50 + bw * 3, y, bw, "Manage", () -> minecraft.gui.setScreen(new Manage(p)), false);
    }
  }

  private void watchSaved(String id) {
    try {
      app.shared.watch(false, store.get(id).settings(), null);
    } catch (Exception e) {
      notice = e.getMessage();
      minecraft.gui.setScreen(this);
    }
  }

  private void rowButton(int x, int y, int w, String label, Runnable action, boolean primary) {
    var b =
        button(
            x,
            y,
            w,
            label,
            () -> {
              try {
                action.run();
              } catch (Exception e) {
                notice = e.getMessage();
              }
            },
            primary);
    chrome.remove(b);
    b.visible = y >= TOP && y + 22 <= height - 38;
    rowButtons.add(b);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll -= (int) (sy * 32);
    rebuildRows();
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (e.y() < TOP || e.y() >= height - 38) {
      for (var w : chrome) if (w.mouseClicked(e, twice)) return true;
      return false;
    }
    return super.mouseClicked(e, twice);
  }

  @Override
  public void onClose() {
    closeAfterSave();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g, "Open an edit and change its settings. Changes save when closed.");
    var list = filtered();
    g.enableScissor(18, TOP, width - 18, height - 38);
    for (int i = 0; i < list.size(); i++) {
      int y = TOP + i * ROW - scroll;
      if (y + ROW < TOP || y > height - 38) continue;
      var p = list.get(i);
      boolean active = p.id().equals(activeId);
      Ui.surface(g, 20, y, width - 40, ROW - 8, CARD, active ? ACCENT : BORDER);
      String name = p.name() + (active ? "  ·  editing" : "");
      text(g, cut(name, width - 64), 32, y + 10, active ? ACCENT : TEXT);
      String details =
          p.settings().sound + " · " + SongAnalysisScreen.time(p.settings().durationMs);
      text(g, cut(details, width - 64), 32, y + 29, MUTED);
      if (inside(mx, my, 28, y + 6, width - 56, 34))
        g.setTooltipForNextFrame(Ui.label(p.name() + " · " + details), mx, my);
    }
    if (list.isEmpty()) {
      text(
          g,
          presets.isEmpty() ? "Your edit collection starts here" : "No matching presets",
          32,
          TOP + 20,
          TEXT);
      text(
          g,
          cut(
              presets.isEmpty()
                  ? "Save current as new to keep your first song and look."
                  : "Try another name or song.",
              width - 64),
          32,
          TOP + 42,
          MUTED);
    }
    for (var b : rowButtons) if (b.visible) b.extractRenderState(g, mx, my, dt);
    g.disableScissor();
    Ui.scrollBar(g, width - 9, TOP, height - TOP - 38, list.size() * ROW, scroll);
    renderChrome(g, mx, my, dt);
  }

  @FunctionalInterface
  interface NamedAction {
    void run(String name) throws Exception;
  }

  static final class Name extends FormScreen {
    private final String initial;
    private final NamedAction action;

    Name(SkyblockFlowClient app, Screen parent, String title, String initial, NamedAction action) {
      super(app, parent, title);
      this.initial = initial;
      this.action = action;
    }

    @Override
    protected String description() {
      return "Give this edit a name. Existing presets will be kept.";
    }

    @Override
    protected boolean autoSave() {
      return false;
    }

    protected String saveLabel() {
      return "Create preset";
    }

    @Override
    protected void buildForm() {
      field("name", "Preset name", initial, 0);
      label("Includes the song, timing, camera, effects and level-up card.", 0, 56);
    }

    @Override
    protected void commit() {
      try {
        action.run(value("name"));
      } catch (Exception e) {
        notice = e.getMessage();
      }
    }

    @Override
    public void onClose() {
      closeAfterSave();
    }
  }

  private final class Manage extends FormScreen {
    private final EditPresetStore.Preset preset;

    Manage(EditPresetStore.Preset preset) {
      super(EditPresetsScreen.this.app, EditPresetsScreen.this, "Manage · " + preset.name());
      this.preset = preset;
    }

    @Override
    protected String description() {
      return "Rename or remove this preset. Its song and reviewed hits are kept.";
    }

    @Override
    protected boolean autoSave() {
      return false;
    }

    protected String saveLabel() {
      return "Rename";
    }

    @Override
    protected String savedNotice() {
      return "Preset renamed";
    }

    @Override
    protected void buildForm() {
      field("name", "Preset name", preset.name(), 0);
      label("Song: " + preset.settings().sound, 0, 56);
      action(
          "Delete preset",
          0,
          98,
          bodyW,
          () ->
              minecraft.gui.setScreen(
                  new ConfirmScreen(
                      yes -> {
                        if (!yes) {
                          minecraft.gui.setScreen(this);
                          return;
                        }
                        try {
                          store.delete(preset.id());
                          editor.presetDeleted(preset.id());
                          EditPresetsScreen.this.notice = "Deleted " + preset.name();
                          minecraft.gui.setScreen(EditPresetsScreen.this);
                        } catch (Exception e) {
                          notice = e.getMessage();
                          minecraft.gui.setScreen(this);
                        }
                      },
                      Component.literal("Delete " + preset.name() + "?"),
                      Component.literal(
                          "Only the preset is deleted. Music, hit timelines and current settings"
                              + " are kept."))));
    }

    @Override
    protected void commit() {
      try {
        store.rename(preset.id(), value("name"));
        onClose();
      } catch (Exception e) {
        notice = e.getMessage();
      }
    }

    @Override
    public void onClose() {
      closeAfterSave();
    }
  }
}
