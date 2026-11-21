package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.core.HudSettings.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class HudPaint {
  public final Theme theme;
  private final boolean legacy;

  public HudPaint(Theme theme) {
    this(theme, false);
  }

  public HudPaint(Theme theme, boolean legacy) {
    this.theme = theme;
    this.legacy = legacy;
  }

  public int ink() {
    return 0xff000000 | theme.colors.text;
  }

  public int secondary() {
    return 0xff000000 | theme.colors.muted;
  }

  public int accent() {
    return 0xff000000 | theme.colors.accent;
  }

  private Component label(String s) {
    return !legacy && theme.font == Face.MINECRAFT ? Component.literal(dev.asarix.skyblockflow.core.Numbers.text(s)) : Ui.label(s);
  }

  public int width(String s) {
    return Minecraft.getInstance().font.width(label(s));
  }

  public String trim(String s, int width) {
    return Ui.trim(s, width, this::width);
  }

  public void text(GuiGraphicsExtractor g, String s, int x, int y, int rgb) {
    g.text(Minecraft.getInstance().font, label(s), x, y, rgb, false);
  }

  public void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
    panel(g, x, y, w, h, 1);
  }

  public void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, double fade) {
    int highlight = ((int) (255 * fade) << 24) | (theme.colors.accent & 0xffffff);
    int border = ((int) (220 * fade) << 24) | (theme.borderColor & 0xffffff);
    int bg =
        ((int) Math.round(theme.opacity * 2.55 * fade) << 24)
            | (theme.colors.background & 0xffffff);
    int r = Math.min(theme.radius, Math.min(w, h) / 2), b = theme.borderWidth;
    if (theme.shadow > 0)
      Ui.rounded(g, x + 2, y + 3, w, h, r, (int) (theme.shadow * 2.55 * fade) << 24);
    Ui.rounded(g, x, y, w, h, r, bg);
    switch (theme.surface) {
      case GLASS ->
          g.fill(x + r, y, x + w - r, y + Math.max(1, b), (int) (56 * fade) << 24 | 0xffffff);
      case UNDERLINE -> {
        g.fill(x, y + h - b, x + w, y + h, border);
        g.fill(x, y + h - theme.accentWidth, x + Math.min(32, w), y + h, highlight);
      }
      case ROUNDED, SQUARE_OUTLINE -> outline(g, x, y, w, h, r, b, border);
      case DOUBLE_FRAME -> {
        outline(g, x, y, w, h, r, b, border);
        outline(
            g,
            x + 3,
            y + 3,
            w - 6,
            h - 6,
            Math.max(0, r - 3),
            b,
            (int) (70 * fade) << 24 | (theme.borderColor & 0xffffff));
      }
      case CORNERS -> {
        int n = Math.min(12, Math.min(w, h) / 3);
        for (int dx : new int[] {0, w - n})
          for (int dy : new int[] {0, h - b})
            g.fill(x + dx, y + dy, x + dx + n, y + dy + b, border);
        for (int dx : new int[] {0, w - b})
          for (int dy : new int[] {0, h - n})
            g.fill(x + dx, y + dy, x + dx + b, y + dy + n, border);
      }
      case GRADIENT -> {
        for (int row = 0; row < h; row++) {
          double t = (double) row / Math.max(1, h - 1);
          int c = 0;
          for (int shift : new int[] {0, 8, 16})
            c |=
                (int)
                        (((theme.colors.background >> shift) & 255) * (1 - t)
                            + ((theme.gradientColor >> shift) & 255) * t)
                    << shift;
          int inset =
              row < r
                  ? r - (int) Math.sqrt(Math.max(0, r * r - (r - row - 1) * (r - row - 1)))
                  : row >= h - r
                      ? r - (int) Math.sqrt(Math.max(0, r * r - (row - (h - r)) * (row - (h - r))))
                      : 0;
          g.fill(x + inset, y + row, x + w - inset, y + row + 1, (bg & 0xff000000) | c);
        }
        outline(g, x, y, w, h, r, b, border);
      }
      case RIBBON -> g.fill(x + r, y, x + w - r, y + theme.accentWidth, highlight);
      case MINIMAL -> g.fill(x + 8, y + h - 2, x + Math.min(w - 8, 36), y + h - 1, highlight);
    }
    if (theme.accentStripe && theme.accentWidth > 0)
      Ui.rounded(
          g,
          x + 3,
          y + 7,
          theme.accentWidth,
          Math.max(2, h - 14),
          Math.min(1, theme.accentWidth),
          highlight);
  }

  private static void outline(
      GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int b, int c) {
    if (b <= 0 || w <= 0 || h <= 0) return;
    for (int row = 0; row < h; row++) {
      int inset =
          row < r
              ? r - (int) Math.sqrt(Math.max(0, r * r - (r - row - 1) * (r - row - 1)))
              : row >= h - r
                  ? r - (int) Math.sqrt(Math.max(0, r * r - (row - (h - r)) * (row - (h - r))))
                  : 0;
      if (row < b || row >= h - b) g.fill(x + inset, y + row, x + w - inset, y + row + 1, c);
      else {
        g.fill(x + inset, y + row, x + inset + b, y + row + 1, c);
        g.fill(x + w - inset - b, y + row, x + w - inset, y + row + 1, c);
      }
    }
  }
}
