package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.core.TooltipScroll;
import dev.asarix.skyblockflow.mixin.TooltipTextAccess;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

public final class ScrollableTooltips {
  private static final TooltipScroll SCROLL = new TooltipScroll();
  private ScrollableTooltips() {}
  public static void beginFrame() { SCROLL.beginFrame(); }

  public static boolean scroll(Screen screen, double x, double y, double dx, double dy) {
    long window = Minecraft.getInstance().getWindow().handle();
    boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
        || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    return SCROLL.scroll(screen, x, y, dx, dy, shift, System.nanoTime() / 1_000_000);
  }

  public static boolean render(GuiGraphicsExtractor g, Font font, List<ClientTooltipComponent> original,
                               int mouseX, int mouseY, ClientTooltipPositioner positioner, Identifier style) {
    if (original.isEmpty() || Minecraft.getInstance().gui.screen() == null) return false;
    int widthLimit = Math.max(20, Math.min(360, g.guiWidth() - 22));
    int originalWidth = 0, originalHeight = original.size() > 1 ? 2 : 0;
    for (var line : original) {
      originalWidth = Math.max(originalWidth, line.getWidth(font)); originalHeight += line.getHeight(font);
    }
    if (originalWidth <= widthLimit && originalHeight <= g.guiHeight() - 20) {
      SCROLL.show(Minecraft.getInstance().gui.screen(), 0, mouseX, mouseY,
          originalWidth, originalHeight, originalWidth, originalHeight, System.nanoTime() / 1_000_000);
      return false;
    }
    var lines = new ArrayList<ClientTooltipComponent>();
    long key = Objects.hashCode(style);
    boolean wrapped = false;
    for (var line : original) {
      if (line instanceof TooltipTextAccess access) {
        var text = styled(access.skyblockflow$text());
        key = key * 31 + text.hashCode();
        if (line.getWidth(font) > widthLimit) {
          // The input is already in visual order: wrap it without applying bidi a second time.
          for (var part : font.getSplitter().splitLines(text, widthLimit, Style.EMPTY))
            lines.add(ClientTooltipComponent.create(visual(part)));
          wrapped = true;
          continue;
        }
      } else key = key * 31 + Objects.hash(line.getClass(), line.getWidth(font), line.getHeight(font));
      lines.add(line);
    }
    int width = 0, height = lines.size() > 1 ? 2 : 0;
    for (var line : lines) { width = Math.max(width, line.getWidth(font)); height += line.getHeight(font); }
    int visibleWidth = Math.min(width, widthLimit);
    boolean scrollY = height > g.guiHeight() - 20;
    boolean scrollX = width > visibleWidth;
    if (!wrapped && !scrollY && !scrollX) {
      SCROLL.show(Minecraft.getInstance().gui.screen(), key, mouseX, mouseY,
          width, height, width, height, System.nanoTime() / 1_000_000);
      return false;
    }
    int footer = scrollY || scrollX ? 13 : 0;
    int visibleHeight = Math.min(height, Math.max(12, g.guiHeight() - 20 - footer));
    int boxWidth = Math.max(visibleWidth + (scrollY ? 5 : 0), footer > 0 ? 110 : 0);
    boxWidth = Math.min(boxWidth, g.guiWidth() - 12);
    var position = positioner.positionTooltip(g.guiWidth(), g.guiHeight(), mouseX, mouseY, boxWidth, visibleHeight + footer);
    int x = Math.clamp(position.x(), 6, Math.max(6, g.guiWidth() - boxWidth - 6));
    int y = Math.clamp(position.y(), 6, Math.max(6, g.guiHeight() - visibleHeight - footer - 6));
    SCROLL.show(Minecraft.getInstance().gui.screen(), key, mouseX, mouseY,
        width, height, visibleWidth, visibleHeight, System.nanoTime() / 1_000_000);
    TooltipRenderUtil.extractTooltipBackground(g, x, y, boxWidth, visibleHeight + footer, style);
    g.enableScissor(x - 1, y - 1, x + visibleWidth + 1, y + visibleHeight);
    try {
      for (int pass = 0; pass < 2; pass++) {
        int rowY = y - SCROLL.y();
        for (int i = 0; i < lines.size(); i++) {
          var line = lines.get(i);
          if (rowY + line.getHeight(font) >= y && rowY < y + visibleHeight) {
            if (pass == 0) line.extractText(g, font, x - SCROLL.x(), rowY);
            else line.extractImage(font, x - SCROLL.x(), rowY, width, height, g);
          }
          rowY += line.getHeight(font) + (i == 0 ? 2 : 0);
        }
      }
    } finally { g.disableScissor(); }
    if (scrollY) Ui.scrollBar(g, x + boxWidth - 2, y, visibleHeight, height, SCROLL.y());
    if (footer > 0)
      g.text(font, scrollX ? "Shift + scroll: sideways" : "Scroll to read more", x, y + visibleHeight + 3, 0xff9baabd, false);
    return true;
  }

  private static MutableComponent styled(FormattedCharSequence sequence) {
    var result = Component.empty(); var run = new StringBuilder(); var style = new Style[]{Style.EMPTY};
    sequence.accept((index, current, codepoint) -> {
      if (!current.equals(style[0])) {
        if (!run.isEmpty()) result.append(Component.literal(run.toString()).setStyle(style[0]));
        run.setLength(0); style[0] = current;
      }
      run.appendCodePoint(codepoint); return true;
    });
    if (!run.isEmpty()) result.append(Component.literal(run.toString()).setStyle(style[0]));
    return result;
  }

  private static FormattedCharSequence visual(FormattedText text) {
    var parts = new ArrayList<FormattedCharSequence>();
    text.visit((style, run) -> { parts.add(FormattedCharSequence.forward(run, style)); return Optional.empty(); }, Style.EMPTY);
    return FormattedCharSequence.composite(parts);
  }
}
