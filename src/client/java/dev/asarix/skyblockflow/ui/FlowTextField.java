package dev.asarix.skyblockflow.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Keep Minecraft's editing/cursor behavior with a quieter field surface. */
public final class FlowTextField extends EditBox {
  public FlowTextField(Font font, int x, int y, int width, int height, Component caption) {
    super(font, x, y, width, height, caption);
    setTextShadow(false);
    setTextColor(FlowScreen.TEXT);
  }

  @Override
  public int getInnerWidth() {
    return Math.max(1, getWidth() - 12);
  }

  @Override
  public void onClick(MouseButtonEvent e, boolean twice) {
    super.onClick(new MouseButtonEvent(e.x() - 2, e.y(), e.buttonInfo()), twice);
  }

  @Override
  public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    Ui.rounded(
        g,
        getX(),
        getY(),
        getWidth(),
        getHeight(),
        4,
        isFocused() ? FlowScreen.ACCENT : FlowScreen.BORDER);
    Ui.rounded(g, getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2, 3, 0xff111a26);
    if (!isFocused() && getValue().matches("-?[0-9]+(?:\\.[0-9]+)?")) {
      Ui.text(g, Ui.trim(getValue(), getInnerWidth()), getX() + 6, getY() + (getHeight() - 9) / 2, FlowScreen.TEXT);
      return;
    }
    setBordered(false);
    g.pose().pushMatrix();
    g.pose().translate(6, (getHeight() - 8) / 2);
    super.extractWidgetRenderState(g, mx - 6, my - (getHeight() - 8) / 2, dt);
    g.pose().popMatrix();
    setBordered(true);
  }
}
