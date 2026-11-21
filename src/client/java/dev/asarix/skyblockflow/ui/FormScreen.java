package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.Models.Region;
import dev.asarix.skyblockflow.core.SongRecommendations;
import java.util.*;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Scrollable forms keep fields usable at Minecraft's smallest GUI size. */
public abstract class FormScreen extends FlowScreen {
  protected final Map<String, String> values = new HashMap<>();

  private record Item(AbstractWidget widget, int y, String page) {}

  private record Label(String text, int x, int y, int color, String page, int width) {}

  private final LinkedHashMap<String, Integer> pages = new LinkedHashMap<>();
  private String buildingPage = "", activePage = "";
  private final Map<String, EditBox> fields = new HashMap<>();

  protected int formTop() {
    return pages.size() > 1 ? 106 : TOP;
  }

  protected void page(String name) {
    buildingPage = name;
    pages.putIfAbsent(name, 0);
  }

  protected boolean onPage(String name) {
    return activePage.equals(name);
  }

  protected String description() {
    return "Changes save automatically when you close this menu.";
  }

  @Override
  protected int footerReservedWidth() {
    return 180;
  }

  protected String saveLabel() {
    return readOnly() ? "Done" : "Save changes";
  }

  protected String savedNotice() {
    return "Changes saved";
  }

  protected void selectPage(String name) {
    activePage = name;
    scroll = 0;
  }

  protected boolean readOnly() {
    return false;
  }

  private IntConsumer keyCapture;
  private final Map<String, String> captions = new HashMap<>();
  private final List<Item> body = new ArrayList<>();
  private final List<Label> labels = new ArrayList<>();
  protected int bodyX, bodyW, scroll, contentHeight;
  protected static final int TOP = 72;

  protected FormScreen(SkyblockFlowClient app, Screen parent, String title) {
    super(app, parent, title);
  }

  @Override
  protected final void init() {
    super.init();
    committed = false;
    body.clear();
    labels.clear();
    fields.clear();
    pages.clear();
    buildingPage = "";
    contentHeight = 0;
    bodyX = Math.max(22, (width - 500) / 2);
    bodyW = Math.min(500, width - 44);
    button(width - 184, height - 28, 72, "Back", this::onClose);
    button(
        width - 106,
        height - 28,
        92,
        saveLabel(),
        () -> {
          try {
            saving = true;
            try {
              commit();
            } finally {
              saving = false;
            }
            if (!readOnly()
                && minecraft.gui.screen() != this
                && parent instanceof FlowScreen screen) screen.notice = savedNotice();
          } catch (Exception e) {
            notice = e.getMessage();
          }
        },
        true);
    buildForm();
    if (!pages.containsKey(activePage)) activePage = pages.keySet().stream().findFirst().orElse("");
    if (pages.size() > 1) {
      int x = bodyX, w = (bodyW - 4 * (pages.size() - 1)) / pages.size();
      for (String name : pages.keySet()) {
        button(
            x,
            72,
            w,
            name,
            () -> {
              activePage = name;
              scroll = 0;
              notice = "";
              rebuildWidgets();
            },
            name.equals(activePage));
        x += w + 4;
      }
    }
    layout();
  }

  protected void mask(String key) {
    var field = fields.get(key);
    if (field != null)
      field.addFormatter(
          (text, offset) ->
              net.minecraft.util.FormattedCharSequence.forward(
                  "•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
  }

  public record SearchControl(String caption, String page, int y) {}

  public java.util.List<SearchControl> searchControls() {
    var result = new java.util.ArrayList<SearchControl>();
    for (var item : body) {
      String caption = item.widget.getMessage().getString();
      if (item.widget instanceof EditBox) {
        for (var entry : fields.entrySet())
          if (entry.getValue() == item.widget)
            caption = captions.getOrDefault(entry.getKey(), caption);
      }
      if (!caption.isBlank())
        result.add(new SearchControl(caption.replaceAll(": (ON|OFF)$", ""), item.page, item.y));
    }
    return result;
  }

  public void reveal(SearchControl control) {
    activePage = control.page();
    scroll = Math.max(0, control.y() - 28);
    layout();
    notice = "Found: " + control.caption();
  }

  protected abstract void buildForm();

  protected abstract void commit();

  private boolean saving, saveOnly, committed;

  protected boolean autoSave() {
    return !readOnly();
  }

  protected final void closeAfterSave() {
    closeAfterSave(parent);
  }

  protected final void closeAfterSave(Screen next) {
    committed = true;
    app.save();
    if (!saveOnly) minecraft.gui.setScreen(next);
  }

  @Override
  public void removed() {
    super.removed();
    minecraft.schedule(
        () -> {
          Screen current = minecraft.gui.screen();
          if (current instanceof net.minecraft.client.gui.screens.ConfirmScreen) return;
          for (Screen ancestor = current;
              ancestor instanceof FlowScreen flow;
              ancestor = flow.parent) if (ancestor == this) return;
          if (current == this) return;
          for (Screen ancestor = this;
              ancestor instanceof FlowScreen flow;
              ancestor = flow.parent) {
            if (ancestor instanceof FormScreen form
                && !form.committed
                && !form.saving
                && form.autoSave()) {
              form.saving = true;
              form.saveOnly = true;
              try {
                form.commit();
              } catch (RuntimeException invalid) {
                app.error("Unsaved invalid settings: " + invalid.getMessage());
              } finally {
                form.saving = false;
                form.saveOnly = false;
              }
            }
          }
        });
  }

  @Override
  public void onClose() {
    if (saving || !autoSave()) {
      closeAfterSave();
      return;
    }
    saving = true;
    try {
      commit();
    } catch (RuntimeException e) {
      notice = e.getMessage();
    } finally {
      saving = false;
    }
  }

  protected void label(String s, int x, int y) {
    labels.add(new Label(s, x, y, MUTED, buildingPage, bodyW - x));
    pages.merge(buildingPage, y + 16, Math::max);
    contentHeight = Math.max(contentHeight, y + 16);
  }

  protected void section(String title, int y) {
    labels.add(new Label(title, 0, y, TEXT, buildingPage, bodyW));
    pages.merge(buildingPage, y + 24, Math::max);
  }

  protected void field(String key, String caption, String initial, int x, int y, int w) {
    var suggestion = recommendation(key);
    if (suggestion != null) {
      recommendButton(
          x + w - 22,
          y + 13,
          () -> recommendation(key),
          () -> {
            var current = recommendation(key);
            if (current != null) {
              values.put(key, current.text());
              rebuildWidgets();
            }
          });
      w -= 28;
    }
    captions.put(key, caption);
    labels.add(new Label(caption, x, y, MUTED, buildingPage, w));
    EditBox box =
        new FlowTextField(font, bodyX + x, TOP + y + 13, w, 22, Component.literal(caption));
    box.setMaxLength(2048);
    box.setTextShadow(false);
    box.setTextColor(TEXT);
    box.setValue(values.getOrDefault(key, initial));
    values.putIfAbsent(key, initial);
    box.setResponder(v -> values.put(key, v));
    addWidget(box);
    body.add(new Item(box, y + 13, buildingPage));
    fields.put(key, box);
    pages.merge(buildingPage, y + 39, Math::max);
    contentHeight = Math.max(contentHeight, y + 39);
  }

  protected SongRecommendations.Value recommendation(String key) {
    return null;
  }

  protected void recommendButton(
      int x, int y, java.util.function.Supplier<SongRecommendations.Value> value, Runnable apply) {
    FlowButton b =
        new FlowButton(
            bodyX + x,
            TOP + y,
            22,
            22,
            "Recommend",
            () -> {
              try {
                apply.run();
              } catch (Exception e) {
                notice = e.getMessage();
              }
            },
            false) {
          @Override
          protected void extractWidgetRenderState(
              GuiGraphicsExtractor g, int mx, int my, float dt) {
            super.extractWidgetRenderState(g, mx, my, dt);
            if (isHoveredOrFocused()) {
              var v = value.get();
              g.setTooltipForNextFrame(
                  Ui.label(
                      "Set to recommended value"
                          + (v == null
                              ? " · Analyze an MP3 first"
                              : " · " + v.text() + "\n" + v.reason())),
                  mx,
                  my);
            }
          }
        };
    addWidget(b);
    body.add(new Item(b, y, buildingPage));
    pages.merge(buildingPage, y + 30, Math::max);
  }

  protected void recommendedToggle(
      String caption,
      boolean enabled,
      int y,
      Runnable change,
      boolean recommended,
      Runnable apply) {
    action(
        caption + ": " + (enabled ? "ON" : "OFF"),
        0,
        y,
        bodyW - 28,
        () -> {
          change.run();
          rebuildWidgets();
        });
    recommendButton(
        bodyW - 22,
        y,
        () ->
            new SongRecommendations.Value(
                recommended ? "ON" : "OFF",
                "Suggested starting preference; your choice is saved only when you save the form."),
        () -> {
          apply.run();
          rebuildWidgets();
        });
  }

  protected void field(String key, String caption, String initial, int y) {
    field(key, caption, initial, 0, y, bodyW);
  }

  protected void action(String title, int x, int y, int w, Runnable action) {
    FlowButton b =
        new FlowButton(
            bodyX + x,
            TOP + y,
            w,
            22,
            title,
            () -> {
              try {
                action.run();
              } catch (Exception e) {
                notice = e.getMessage();
              }
            },
            false);
    addWidget(b);
    body.add(new Item(b, y, buildingPage));
    pages.merge(buildingPage, y + 30, Math::max);
    contentHeight = Math.max(contentHeight, y + 30);
  }

  protected void keyField(String caption, int key, int y, IntConsumer setter) {
    String name =
        key < 0
            ? "Unbound"
            : com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                .getOrCreate(key)
                .getDisplayName()
                .getString();
    action(
        caption + ": " + name,
        0,
        y,
        bodyW,
        () -> {
          keyCapture = setter;
          notice = "Press a key · Backspace unbinds · Esc cancels";
        });
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (keyCapture != null) {
      if (e.key() != GLFW.GLFW_KEY_ESCAPE) {
        if (e.key() == GLFW.GLFW_KEY_F11) {
          notice = "F11 is reserved for visibility";
          return true;
        }
        keyCapture.accept(e.key() == GLFW.GLFW_KEY_BACKSPACE ? -1 : e.key());
      }
      keyCapture = null;
      notice = "";
      rebuildWidgets();
      return true;
    }
    return super.keyPressed(e);
  }

  protected void toggle(String caption, boolean enabled, int y, Runnable change) {
    action(
        caption + ": " + (enabled ? "ON" : "OFF"),
        0,
        y,
        bodyW,
        () -> {
          change.run();
          rebuildWidgets();
        });
  }

  protected String value(String key) {
    return values.getOrDefault(key, "");
  }

  protected long number(String key, long min, long max) {
    try {
      long n = Long.parseLong(value(key).trim());
      if (n < min || n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      revealField(key);
      throw new IllegalArgumentException(
          captions.getOrDefault(key, key) + ": enter a number from " + min + " to " + max);
    }
  }

  protected float decimal(String key, float min, float max) {
    try {
      float n = Float.parseFloat(value(key).trim());
      if (!Float.isFinite(n) || n < min || n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      revealField(key);
      throw new IllegalArgumentException(
          captions.getOrDefault(key, key) + ": enter a number from " + min + " to " + max);
    }
  }

  protected double coordinate(String key) {
    try {
      double n = Double.parseDouble(value(key).strip());
      if (!Double.isFinite(n) || Math.abs(n) > 30000000) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      revealField(key);
      throw new IllegalArgumentException(
          captions.getOrDefault(key, key) + ": enter a coordinate between -30000000 and 30000000");
    }
  }

  private void revealField(String key) {
    var field = fields.get(key);
    for (Item i : body)
      if (i.widget == field) {
        activePage = i.page;
        scroll = Math.max(0, i.y - 30);
        rebuildWidgets();
        setFocused(fields.get(key));
        return;
      }
  }

  protected void regionFields(Region r, int y) {
    int gap = 8, w = (bodyW - 2 * gap) / 3;
    field("x1", "Start X", Integer.toString(r.x1), 0, y, w);
    field("y1", "Start Y", Integer.toString(r.y1), w + gap, y, w);
    field("z1", "Start Z", Integer.toString(r.z1), 2 * (w + gap), y, w);
    field("x2", "End X", Integer.toString(r.x2), 0, y + 44, w);
    field("y2", "End Y", Integer.toString(r.y2), w + gap, y + 44, w);
    field("z2", "End Z", Integer.toString(r.z2), 2 * (w + gap), y + 44, w);
    action(
        "Use my current block",
        0,
        y + 92,
        Math.min(bodyW, 190),
        () -> {
          if (minecraft.player == null) {
            notice = "Join a world to capture your position";
            return;
          }
          var pos = minecraft.player.blockPosition();
          values.put("x1", "" + pos.getX());
          values.put("x2", "" + pos.getX());
          values.put("y1", "" + pos.getY());
          values.put("y2", "" + pos.getY());
          values.put("z1", "" + pos.getZ());
          values.put("z2", "" + pos.getZ());
          rebuildWidgets();
        });
    label("Inclusive block coordinates. Equal start/end = one block.", 0, y + 122);
  }

  protected Region readRegion() {
    Region r = new Region();
    r.x1 = (int) number("x1", -30000000, 30000000);
    r.y1 = (int) number("y1", -30000000, 30000000);
    r.z1 = (int) number("z1", -30000000, 30000000);
    r.x2 = (int) number("x2", -30000000, 30000000);
    r.y2 = (int) number("y2", -30000000, 30000000);
    r.z2 = (int) number("z2", -30000000, 30000000);
    return r;
  }

  protected int formBottom() {
    return height - 42;
  }

  private void layout() {
    contentHeight = pages.getOrDefault(activePage, 0);
    scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - (formBottom() - formTop())));
    for (Item i : body) {
      i.widget.setY(formTop() + i.y - scroll);
      i.widget.visible =
          i.page.equals(activePage)
              && i.widget.getY() + i.widget.getHeight() > formTop()
              && i.widget.getY() < formBottom();
    }
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll -= (int) (sy * 28);
    layout();
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
    if (e.y() < formTop() || e.y() > formBottom()) {
      for (AbstractWidget w : chrome) if (w.mouseClicked(e, doubleClick)) return true;
      return false;
    }
    return super.mouseClicked(e, doubleClick);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g, description());
    Ui.surface(g, bodyX - 10, formTop() - 6, bodyW + 20, formBottom() + 6 - formTop(), PANEL, BORDER);
    g.enableScissor(bodyX - 5, formTop(), bodyX + bodyW + 5, formBottom());
    for (Label l : labels)
      if (l.page.equals(activePage)) {
        text(
            g,
            cut(l.text, Math.min(l.width, bodyW - l.x)),
            bodyX + l.x,
            formTop() + l.y - scroll,
            l.color);
        if (Ui.width(l.text) > Math.min(l.width, bodyW - l.x)
            && inside(
                mx, my, bodyX + l.x, formTop() + l.y - scroll, Math.min(l.width, bodyW - l.x), 11))
          g.setTooltipForNextFrame(Ui.label(l.text), mx, my);
      }
    for (Item i : body) if (i.widget.visible) i.widget.extractRenderState(g, mx, my, dt);
    g.disableScissor();
    renderChrome(g, mx, my, dt);
    Ui.scrollBar(g, bodyX + bodyW + 5, formTop(), formBottom() - formTop(), contentHeight, scroll);
  }
}
