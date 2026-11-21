package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/** Compact RGB sliders, palette and hex entry shared by every overlay. */
public final class ColorPickerScreen extends FlowScreen {
  private int color, dragging = -1, left, top, track;
  private EditBox hex;
  private final IntConsumer use;
  private static final int[] PALETTE = {
    0x111c29, 0x000000, 0xffffff, 0xe7edf5, 0x79dfbe, 0x9bb6ff, 0xffc578, 0xff8393, 0x59c9fa,
    0xb59bff, 0xff91cf, 0x9baabd
  };

  public ColorPickerScreen(
      SkyblockFlowClient app, Screen parent, String title, int color, IntConsumer use) {
    super(app, parent, title);
    this.color = color & 0xffffff;
    this.use = use;
  }

  @Override
  protected void init() {
    super.init();
    left = Math.max(20, (width - 280) / 2);
    top = Math.max(72, (height - 152) / 2);
    track = Math.min(210, width - 95);
    hex = edit(left + 60, top, Math.min(150, width - left - 75), String.format("#%06X", color));
    hex.setResponder(
        value -> {
          if (value.matches("#?[0-9a-fA-F]{6}"))
            color = Integer.parseInt(value.replace("#", ""), 16);
        });
    button(width - 184, height - 28, 72, "Back", this::onClose);
    button(
        width - 106,
        height - 28,
        92,
        "Use color",
        () -> {
          if (!hex.getValue().matches("#?[0-9a-fA-F]{6}")) {
            notice = "Enter six hex digits, such as #79DFBE";
            return;
          }
          use.accept(color);
          minecraft.gui.setScreen(parent);
        },
        true);
  }

  @Override
  public void onClose() {
    if (hex != null && !hex.getValue().matches("#?[0-9a-fA-F]{6}")) {
      notice = "Enter six hex digits";
      return;
    }
    use.accept(color);
    minecraft.gui.setScreen(parent);
  }

  private boolean choose(double x, double y) {
    for (int i = 0; i < PALETTE.length; i++)
      if (inside(x, y, left + i % 6 * 32, top + 98 + i / 6 * 20, 26, 18)) {
        color = PALETTE[i];
        hex.setValue(String.format("#%06X", color));
        return true;
      }
    for (int i = 0; i < 3; i++)
      if (inside(x, y, left + 30, top + 32 + i * 22, track, 16)) {
        dragging = i;
        slide(x);
        return true;
      }
    return false;
  }

  private void slide(double x) {
    int shift = 16 - dragging * 8, n = (int) Math.clamp((x - left - 30) / track * 255, 0, 255);
    color = (color & ~(255 << shift)) | n << shift;
    hex.setValue(String.format("#%06X", color));
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    return super.mouseClicked(e, twice) || e.button() == 0 && choose(e.x(), e.y());
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    if (dragging < 0) return false;
    slide(e.x());
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent e) {
    dragging = -1;
    return super.mouseReleased(e);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g, "Pick a swatch, drag RGB sliders or enter #RRGGBB.");
    Ui.rounded(g, left, top, 44, 22, 4, 0xff000000 | color);
    for (int i = 0; i < 3; i++) {
      int y = top + 32 + i * 22, shift = 16 - i * 8;
      text(g, new String[] {"R", "G", "B"}[i], left + 5, y + 4, TEXT);
      for (int x = 0; x < track; x++)
        g.fill(
            left + 30 + x,
            y,
            left + 31 + x,
            y + 16,
            0xff000000 | (color & ~(255 << shift)) | (255 * x / track << shift));
      int x = left + 30 + (color >> shift & 255) * track / 255;
      g.outline(x - 2, y - 1, 5, 18, 0xffffffff);
    }
    for (int i = 0; i < PALETTE.length; i++)
      Ui.rounded(g, left + i % 6 * 32, top + 98 + i / 6 * 20, 26, 18, 3, 0xff000000 | PALETTE[i]);
    renderChrome(g, mx, my, dt);
  }
}
