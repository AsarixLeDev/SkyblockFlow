package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.ConfigStore;
import dev.asarix.skyblockflow.core.Models.OverlayColors;
import net.minecraft.client.gui.screens.Screen;

public final class OverlayColorsScreen extends FormScreen {
  private final OverlayColors original, draft;

  public OverlayColorsScreen(SkyblockFlowClient app, Screen parent, OverlayColors colors) {
    super(app, parent, "Overlay colors");
    original = colors;
    draft = ConfigStore.copy(colors, OverlayColors.class);
  }

  @Override
  protected void buildForm() {
    String[] names = {"Background", "Accent & progress", "Text", "Secondary text"};
    int[] colors = {draft.background, draft.accent, draft.text, draft.muted};
    for (int i = 0; i < names.length; i++) {
      final int index = i;
      action(
          names[i] + " · " + String.format("#%06X", colors[i] & 0xffffff),
          0,
          i * 38,
          bodyW,
          () ->
              minecraft.gui.setScreen(
                  new ColorPickerScreen(
                      app,
                      this,
                      names[index],
                      colors[index],
                      color -> {
                        switch (index) {
                          case 0 -> draft.background = color;
                          case 1 -> draft.accent = color;
                          case 2 -> draft.text = color;
                          case 3 -> draft.muted = color;
                        }
                      })));
    }
    label("Opacity is configured separately on the overlay page.", 0, 166);
  }

  @Override
  protected void commit() {
    original.background = draft.background;
    original.accent = draft.accent;
    original.text = draft.text;
    original.muted = draft.muted;
    closeAfterSave();
  }
}
