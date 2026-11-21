package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.Arithmetic;
import dev.asarix.skyblockflow.ui.Ui;
import net.fabricmc.fabric.api.client.screen.v1.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class InventoryCalculator {
  private static String expression = "";

  public static void register(SkyblockFlowClient app) {
    ScreenEvents.AFTER_INIT.register(
        (mc, screen, w, h) -> {
          if (!(screen instanceof AbstractContainerScreen<?>) || !app.store.config.tools.calculator)
            return;
          var bar = new Bar(mc.font, Math.max(4, (w - 340) / 2), h - 24, Math.min(w - 8, 340));
          bar.setValue(expression);
          bar.setResponder(v -> expression = v);
          Screens.getWidgets(screen).add(bar);
          ScreenKeyboardEvents.allowKeyPress(screen)
              .register(
                  (s, e) -> {
                    if (!bar.isFocused()) return true;
                    if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
                      bar.setFocused(false);
                      return false;
                    }
                    bar.keyPressed(e);
                    return false;
                  });
          ScreenKeyboardEvents.allowCharType(screen)
              .register(
                  (s, e) -> {
                    if (!bar.isFocused()) return true;
                    bar.charTyped(e);
                    return false;
                  });
          ScreenMouseEvents.allowMouseClick(screen)
              .register(
                  (s, e) -> {
                    if (e.button() == 0 && bar.isMouseOver(e.x(), e.y())) {
                      bar.setFocused(true);
                      bar.onClick(e, false);
                      return false;
                    }
                    bar.setFocused(false);
                    return true;
                  });
        });
  }

  public static final class Bar extends EditBox {
    private final net.minecraft.client.gui.Font font;
    private int offset;

    public Bar(net.minecraft.client.gui.Font font, int x, int y, int w) {
      super(font, x, y, w, 20, Component.literal("Calculator"));
      this.font = font;
      setMaxLength(512);
      setBordered(false);
    }

    private int caretWidth(String raw, int end) {
      String display = dev.asarix.skyblockflow.core.Numbers.text(raw);
      int j = 0;
      for (int i = 0; i < end && j < display.length(); i++) {
        while (j < display.length() && display.charAt(j) != raw.charAt(i)) j++;
        if (j < display.length()) j++;
      }
      return font.width(display.substring(0, j));
    }

    @Override
    public void onClick(MouseButtonEvent e, boolean twice) {
      int target = (int) e.x() - getX() - 8 + offset, at = 0;
      while (at < getValue().length() && caretWidth(getValue(), at + 1) < target) at++;
      setCursorPosition(at);
      setHighlightPos(at);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
      int x = getX(), y = getY(), w = getWidth(), inner = w - 16;
      String raw = getValue(), value = dev.asarix.skyblockflow.core.Numbers.text(raw), result = "";
      try {
        var n = Arithmetic.evaluate(raw);
        if (n != null)
          result =
              " = "
                  + Arithmetic.fit(
                      n,
                      Math.max(30, inner - Math.min(font.width(value + " = "), inner / 2)),
                      font::width);
      } catch (IllegalArgumentException e) {
        result = "  · " + e.getMessage();
      }
      Ui.rounded(g, x, y, w, 20, 5, isFocused() ? 0xff79dfbe : 0xff3b5268);
      Ui.rounded(g, x + 1, y + 1, w - 2, 18, 4, 0xee101923);
      int cursor = caretWidth(raw, getCursorPosition());
      int total = font.width(value + result);
      if (getCursorPosition() == raw.length()) offset = Math.max(0, total - inner);
      else {
        if (cursor - offset < 0) offset = cursor;
        if (cursor - offset > inner - 4) offset = cursor - inner + 4;
      }
      g.enableScissor(x + 7, y + 2, x + w - 7, y + 18);
      if (value.isEmpty())
        g.text(font, "Calculate…  (64 × 12.5) / 3", x + 8, y + 6, 0xff97a9ba, false);
      else {
        g.text(font, value, x + 8 - offset, y + 6, 0xffedf2f7, false);
        g.text(font, result, x + 8 - offset + font.width(value), y + 6, 0xff79dfbe, false);
      }
      if (isFocused() && System.currentTimeMillis() / 500 % 2 == 0)
        g.fill(x + 8 - offset + cursor, y + 4, x + 9 - offset + cursor, y + 15, 0xffffffff);
      g.disableScissor();
    }
  }
}
