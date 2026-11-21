package dev.asarix.skyblockflow.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** Shared visual primitives; this font is confined to SkyblockFlow displays. */
public final class Ui {
  private static final FontDescription FONT =
      new FontDescription.Resource(Identifier.fromNamespaceAndPath("skyblockflow", "ui"));

  private Ui() {}

  public static Component label(String value) {
    return Component.literal(dev.asarix.skyblockflow.core.Numbers.text(value)).withStyle(s -> s.withFont(FONT).withoutShadow());
  }

  public static int width(String value) {
    return Minecraft.getInstance().font.width(label(value));
  }

  public static String trim(String value, int available) {
    return trim(value, available, Ui::width);
  }

  static String trim(
      String value, int available, java.util.function.ToIntFunction<String> measure) {
    if (available <= 0) return "";
    if (measure.applyAsInt(value) <= available) return value;
    int lo = 0, hi = value.codePointCount(0, value.length());
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (measure.applyAsInt(value.substring(0, value.offsetByCodePoints(0, mid)) + "…")
          <= available) lo = mid;
      else hi = mid - 1;
    }
    return lo == 0 ? "" : value.substring(0, value.offsetByCodePoints(0, lo)) + "…";
  }

  public static void text(GuiGraphicsExtractor g, String value, int x, int y, int color) {
    g.text(Minecraft.getInstance().font, label(value), x, y, color, false);
  }

  public static void rounded(
      GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
    if (w <= 0 || h <= 0) return;
    int r = Math.min(radius, Math.min(w / 2, h / 2));
    g.fill(x + r, y, x + w - r, y + h, color);
    for (int i = 0; i < r; i++) {
      int inset = (int) Math.ceil(r - Math.sqrt(r * r - Math.pow(r - i - .5, 2)));
      g.fill(x + inset, y + i, x + r, y + i + 1, color);
      g.fill(x + w - r, y + i, x + w - inset, y + i + 1, color);
      g.fill(x + inset, y + h - i - 1, x + r, y + h - i, color);
      g.fill(x + w - r, y + h - i - 1, x + w - inset, y + h - i, color);
    }
    g.fill(x, y + r, x + r, y + h - r, color);
    g.fill(x + w - r, y + r, x + w, y + h - r, color);
  }

  public static void surface(
      GuiGraphicsExtractor g, int x, int y, int w, int h, int color, int border) {
    rounded(g, x, y + 2, w, h, 7, 0x35000000);
    rounded(g, x, y, w, h, 7, border);
    rounded(g, x + 1, y + 1, w - 2, h - 2, 6, color);
  }

  public static void toggle(GuiGraphicsExtractor g, int x, int y, boolean on) {
    rounded(g, x, y, 28, 14, 7, on ? FlowScreen.ACCENT : 0xff455162);
    rounded(g, x + (on ? 16 : 2), y + 2, 10, 10, 5, on ? 0xff10251f : 0xffd0d7df);
  }

  public static void scrollBar(
      GuiGraphicsExtractor g, int x, int top, int view, int total, int scroll) {
    if (total <= view || view <= 0) return;
    rounded(g, x, top, 3, view, 1, FlowScreen.PANEL);
    int thumb = Math.max(16, view * view / total);
    rounded(
        g,
        x,
        top + (view - thumb) * scroll / Math.max(1, total - view),
        3,
        thumb,
        1,
        FlowScreen.MUTED);
  }

  public static String readable(Enum<?> value) {
    String s = value.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
