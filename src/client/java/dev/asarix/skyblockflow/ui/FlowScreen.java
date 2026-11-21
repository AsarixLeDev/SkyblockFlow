package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public abstract class FlowScreen extends Screen {
  public static final int BG = 0xff10151e,
      PANEL = 0xff171e29,
      CARD = 0xff202a38,
      BORDER = 0xff2f3a49,
      TEXT = 0xffedf2f7,
      MUTED = 0xff9baabd,
      ACCENT = 0xff79dfbe,
      GOLD = 0xffffc578,
      RED = 0xffff8393;
  protected final SkyblockFlowClient app;
  protected final Screen parent;
  protected final List<AbstractWidget> chrome = new ArrayList<>();
  protected String notice = "";

  protected int footerReservedWidth() {
    return 0;
  }

  protected FlowScreen(SkyblockFlowClient app, Screen parent, String title) {
    super(Component.literal(title));
    this.app = app;
    this.parent = parent;
  }

  @Override
  protected void init() {
    chrome.clear();
  }

  protected FlowButton button(int x, int y, int w, String label, Runnable action) {
    return button(x, y, w, label, action, false);
  }

  protected FlowButton button(int x, int y, int w, String label, Runnable action, boolean primary) {
    FlowButton b = new FlowButton(x, y, w, 22, label, action, primary);
    addWidget(b);
    chrome.add(b);
    return b;
  }

  protected EditBox edit(int x, int y, int w, String value) {
    EditBox b = new FlowTextField(font, x, y, w, 22, Component.literal("Text field"));
    b.setMaxLength(2048);
    b.setValue(value);
    addWidget(b);
    chrome.add(b);
    return b;
  }

  protected void text(GuiGraphicsExtractor g, String text, int x, int y, int color) {
    Ui.text(g, text, x, y, color);
  }

  protected String cut(String text, int width) {
    return Ui.trim(text, width);
  }

  protected static boolean inside(double x, double y, int left, int top, int w, int h) {
    return x >= left && x < left + w && y >= top && y < top + h;
  }

  protected String heading() {
    return title.getString();
  }

  protected void background(GuiGraphicsExtractor g, String subtitle) {
    boolean classic = app.store.config.hud.legacy || minecraft.level == null;
    g.fillGradient(
        0, 0, width, height, classic ? 0xff151d2a : 0x9b112030, classic ? BG : 0xb2111722);
    g.fill(0, 0, width, 64, classic ? 0xff131b26 : 0xcb131b26);
    int titleX = width >= 500 ? 94 : 16;
    if (width >= 500) text(g, "SkyblockFlow  /", 16, 16, ACCENT);
    int titleEnd =
        chrome.stream()
            .filter(w -> w.getY() < 32 && w.getX() > titleX)
            .mapToInt(AbstractWidget::getX)
            .min()
            .orElse(width - 14);
    text(g, cut(heading(), Math.max(0, titleEnd - titleX - 10)), titleX, 16, TEXT);
    text(g, cut(subtitle, width - 32), 16, 43, MUTED);
    g.fill(0, 63, width, 64, BORDER);
    g.fill(0, height - 30, width, height, BG);
    String status =
        !notice.isEmpty() ? notice : !app.lastError.isEmpty() ? app.lastError : "Esc to go back";
    text(
        g,
        cut(status, width - 28 - footerReservedWidth()),
        14,
        height - 19,
        !notice.isEmpty() || !app.lastError.isEmpty() ? GOLD : MUTED);
  }

  protected void renderChrome(GuiGraphicsExtractor g, int mx, int my, float dt) {
    for (AbstractWidget w : chrome) w.extractRenderState(g, mx, my, dt);
    String status = !notice.isEmpty() ? notice : app.lastError;
    if (!status.isEmpty() && my >= height - 28 && mx < width - footerReservedWidth())
      g.setTooltipForNextFrame(Ui.label(status), mx, my);
  }

  @Override
  public void extractBackground(GuiGraphicsExtractor g, int x, int y, float dt) {}

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  protected void closeAfterSave() {
    minecraft.gui.setScreen(parent);
  }

  @Override
  public void onClose() {
    app.save();
    minecraft.gui.setScreen(parent);
  }

  public static class FlowButton extends AbstractWidget {
    private final Runnable action;
    private final boolean primary;

    public FlowButton(int x, int y, int w, int h, String label, Runnable action, boolean primary) {
      super(x, y, w, h, Component.literal(label));
      this.action = action;
      this.primary = primary;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
      boolean toggle =
          getMessage().getString().endsWith(": ON") || getMessage().getString().endsWith(": OFF");
      int color =
          primary
              ? (isHoveredOrFocused() ? 0xffa6eed7 : ACCENT)
              : (isHoveredOrFocused() ? 0xff2a394b : CARD);
      if (!active) color = PANEL;
      Ui.rounded(
          g, getX(), getY(), width, height, 5, isFocused() ? ACCENT : primary && active ? color : BORDER);
      Ui.rounded(g, getX() + 1, getY() + 1, width - 2, height - 2, 4, color);
      String full = getMessage().getString();
      if (full.equals("Recommend") && width <= 24) {
        int cx = getX() + width / 2, cy = getY() + height / 2;
        for (int row = -4; row <= 4; row++) {
          int half = row == 0 ? 4 : Math.abs(row) == 1 ? 2 : 0;
          g.fill(cx - half, cy + row, cx + half + 1, cy + row + 1, ACCENT);
        }
        g.fill(cx + 4, cy - 5, cx + 6, cy - 3, TEXT);
        return;
      }
      if (full.equals("Close") && width <= 32) {
        int cx = getX() + width / 2, cy = getY() + height / 2;
        for (int i = -4; i <= 4; i++) {
          g.fill(cx + i, cy + i, cx + i + 2, cy + i + 2, TEXT);
          g.fill(cx - i, cy + i, cx - i + 2, cy + i + 2, TEXT);
        }
        if (isHoveredOrFocused()) g.setTooltipForNextFrame(Ui.label(full), mx, my);
        return;
      }
      int labelPadding = height <= 16 || width <= 24 ? 6 : 16;
      if (toggle) {
        boolean on = full.endsWith(": ON");
        full = full.substring(0, full.lastIndexOf(':'));
        Ui.text(
            g,
            Ui.trim(full, width - 52),
            getX() + 10,
            getY() + (height - 9) / 2,
            active ? TEXT : MUTED);
        Ui.toggle(g, getX() + width - 38, getY() + (height - 14) / 2, on);
      } else {
        String label = Ui.trim(full, width - labelPadding);
        Ui.text(
            g,
            label,
            getX() + (width - Ui.width(label)) / 2,
            getY() + (height - 9) / 2,
            active ? primary ? BG : TEXT : MUTED);
      }
      if (isHoveredOrFocused() && Ui.width(full) > width - (toggle ? 52 : labelPadding))
        g.setTooltipForNextFrame(Ui.label(full), mx, my);
    }

    @Override
    public void onClick(MouseButtonEvent e, boolean twice) {
      action.run();
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
      if (active && (e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_SPACE)) {
        action.run();
        return true;
      }
      return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
      defaultButtonNarrationText(out);
    }
  }
}
