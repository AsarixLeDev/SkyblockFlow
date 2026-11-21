package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

public final class ChoiceScreen<T> extends FlowScreen {
  public record Choice<T>(String label, String description, String category, T value) {
    public Choice(String label, T value) {
      this(label, "", "", value);
    }
  }

  private final List<Choice<T>> choices;
  private final Consumer<T> chosen;
  private String query = "", category = "All";
  private int scroll, selected, top, row;

  public ChoiceScreen(
      SkyblockFlowClient app,
      Screen parent,
      String title,
      List<Choice<T>> choices,
      Consumer<T> chosen) {
    super(app, parent, title);
    this.choices = choices;
    this.chosen = chosen;
  }

  @Override
  protected void init() {
    super.init();
    button(width - 88, 10, 74, "Back", this::onClose);
    EditBox search = edit(20, 76, width - 40, query);
    search.setHint(Ui.label("Type to search…"));
    search.setTextShadow(false);
    search.setResponder(
        s -> {
          query = s;
          scroll = 0;
          selected = 0;
        });
    var groups =
        choices.stream().map(Choice::category).filter(s -> !s.isEmpty()).distinct().toList();
    top = groups.size() > 1 ? 140 : 110;
    row = choices.stream().anyMatch(c -> !c.description.isEmpty()) ? 52 : 34;
    if (groups.size() > 1) {
      var names = new ArrayList<String>();
      names.add("All");
      names.addAll(groups);
      int x = 20, w = (width - 40 - 4 * (names.size() - 1)) / names.size();
      for (String name : names) {
        button(
            x,
            106,
            w,
            name,
            () -> {
              category = name;
              scroll = 0;
              selected = 0;
              rebuildWidgets();
            },
            category.equals(name));
        x += w + 4;
      }
    }
    setInitialFocus(search);
  }

  private List<Choice<T>> filtered() {
    return choices.stream()
        .filter(c -> category.equals("All") || c.category.equals(category))
        .filter(
            c ->
                (c.label + " " + c.description)
                    .toLowerCase(Locale.ROOT)
                    .contains(query.toLowerCase(Locale.ROOT)))
        .toList();
  }

  private void clamp() {
    scroll = Math.clamp(scroll, 0, Math.max(0, filtered().size() * row - (height - 38 - top)));
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll -= (int) (sy * 32);
    clamp();
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (super.mouseClicked(e, twice)) return true;
    if (e.button() != 0 || !inside(e.x(), e.y(), 20, top, width - 40, height - top - 38))
      return false;
    int i = ((int) e.y() - top + scroll) / row;
    var list = filtered();
    if (i >= 0 && i < list.size() && ((int) e.y() - top + scroll) % row < row - 6) {
      chosen.accept(list.get(i).value);
      return true;
    }
    return false;
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    var list = filtered();
    if (e.key() == GLFW.GLFW_KEY_DOWN || e.key() == GLFW.GLFW_KEY_UP) {
      selected =
          Math.clamp(
              selected + (e.key() == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, Math.max(0, list.size() - 1));
      if (selected * row < scroll) scroll = selected * row;
      if ((selected + 1) * row > scroll + height - top - 38)
        scroll = (selected + 1) * row - (height - top - 38);
      clamp();
      return true;
    }
    if (e.key() == GLFW.GLFW_KEY_ENTER && !list.isEmpty()) {
      chosen.accept(list.get(selected).value);
      return true;
    }
    return super.keyPressed(e);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g, "Search or browse below. ↑ ↓ to choose, Enter to select.");
    clamp();
    g.enableScissor(18, top, width - 14, height - 38);
    var list = filtered();
    for (int i = 0; i < list.size(); i++) {
      int y = top + i * row - scroll;
      var c = list.get(i);
      boolean hover = inside(mx, my, 20, y, width - 40, row - 6);
      Ui.surface(
          g,
          20,
          y,
          width - 40,
          row - 6,
          hover ? 0xff2a394b : CARD,
          i == selected ? 0xff587a91 : BORDER);
      text(g, cut(c.label, width - 80), 32, y + 9, TEXT);
      text(g, "→", width - 42, y + 9, MUTED);
      if (!c.description.isEmpty()) text(g, cut(c.description, width - 68), 32, y + 27, MUTED);
      if (hover && Ui.width(c.description) > width - 68)
        g.setTooltipForNextFrame(Ui.label(c.description), mx, my);
    }
    if (list.isEmpty()) {
      text(g, "No matches", 32, top + 16, TEXT);
      text(g, "Try a different word or category.", 32, top + 36, MUTED);
    }
    g.disableScissor();
    Ui.scrollBar(g, width - 9, top, height - top - 38, list.size() * row, scroll);
    renderChrome(g, mx, my, dt);
  }

  public static <E extends Enum<E>> void choose(
      SkyblockFlowClient app, Screen parent, String title, E[] options, Consumer<E> selected) {
    open(
        new ChoiceScreen<>(
            app,
            parent,
            title,
            Arrays.stream(options).map(e -> new Choice<E>(Ui.readable(e), e)).toList(),
            v -> {
              selected.accept(v);
              open(parent);
            }));
  }

  private static void open(Screen screen) {
    net.minecraft.client.Minecraft.getInstance().gui.setScreen(screen);
  }
}
