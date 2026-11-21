package dev.asarix.skyblockflow.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.ConfigStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

/** F9 command bindings and F10 coordinate sounds share the same list controls. */
public final class FeatureListScreen extends FlowScreen {
  private final boolean coordinateSounds;
  private int selected, scroll;
  private boolean deleteArmed;
  private static final int TOP = 110, ROW = 52;

  public FeatureListScreen(SkyblockFlowClient app, Screen parent, boolean coordinateSounds) {
    super(app, parent, coordinateSounds ? "Coordinate sounds" : "Command keybinds");
    this.coordinateSounds = coordinateSounds;
  }

  private int size() {
    return coordinateSounds
        ? app.store.config.coordinateAlerts.size()
        : app.store.config.commandBindings.size();
  }

  @Override
  protected void init() {
    super.init();
    selected = Math.clamp(selected, 0, Math.max(0, size() - 1));
    button(width - 76, 10, 62, "Done", this::onClose);
    button(
        14,
        42,
        94,
        "Keybinds",
        () -> minecraft.gui.setScreen(new FeatureListScreen(app, parent, false)),
        !coordinateSounds);
    button(
        114,
        42,
        106,
        "Coord sounds",
        () -> minecraft.gui.setScreen(new FeatureListScreen(app, parent, true)),
        coordinateSounds);
    int gap = 6, w = (width - 28 - 3 * gap) / 4;
    button(14, 76, w, coordinateSounds ? "+ Sound" : "+ Keybind", () -> edit(true), true);
    button(14 + w + gap, 76, w, "Edit", () -> edit(false));
    button(
        14 + 2 * (w + gap),
        76,
        w,
        "On / off",
        () -> {
          if (size() == 0) return;
          if (coordinateSounds) {
            var a = app.store.config.coordinateAlerts.get(selected);
            a.enabled = !a.enabled;
            app.coordinates.forget(a.id);
          } else {
            var b = app.store.config.commandBindings.get(selected);
            b.enabled = !b.enabled;
            try {
              ConfigStore.validateCommandBinding(b, app.store.config.commandBindings);
            } catch (IllegalArgumentException e) {
              b.enabled = !b.enabled;
              notice = e.getMessage();
              return;
            }
          }
          app.save();
        });
    button(
        14 + 3 * (w + gap),
        76,
        w,
        deleteArmed ? "Confirm?" : "Delete",
        () -> {
          if (size() == 0) return;
          if (!deleteArmed) {
            deleteArmed = true;
          } else {
            if (coordinateSounds)
              app.coordinates.forget(app.store.config.coordinateAlerts.remove(selected).id);
            else app.store.config.commandBindings.remove(selected);
            deleteArmed = false;
            app.save();
          }
          rebuildWidgets();
        });
    clampScroll();
  }

  private void edit(boolean create) {
    if (!create && size() == 0) return;
    minecraft.gui.setScreen(
        coordinateSounds
            ? new CoordinateAlertEditorScreen(
                app, this, create ? null : app.store.config.coordinateAlerts.get(selected))
            : new CommandBindingEditorScreen(
                app, this, create ? null : app.store.config.commandBindings.get(selected)));
  }

  private void clampScroll() {
    scroll = Math.clamp(scroll, 0, Math.max(0, size() * ROW - (height - 38 - TOP)));
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll -= (int) (sy * 30);
    clampScroll();
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (super.mouseClicked(e, twice)) return true;
    if (e.button() != 0 || !inside(e.x(), e.y(), 14, TOP, width - 28, height - 38 - TOP))
      return false;
    int index = (int) (e.y() - TOP + scroll) / ROW;
    if (index >= size()) return false;
    selected = index;
    deleteArmed = false;
    if (twice) edit(false);
    else rebuildWidgets();
    return true;
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (e.key() == GLFW.GLFW_KEY_ENTER) {
      edit(false);
      return true;
    }
    return super.keyPressed(e);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g, "");
    g.enableScissor(14, TOP, width - 14, height - 38);
    for (int i = 0; i < size(); i++) {
      int y = TOP + i * ROW - scroll;
      if (y + 44 < TOP || y > height - 38) continue;
      String name, summary;
      boolean enabled;
      if (coordinateSounds) {
        var a = app.store.config.coordinateAlerts.get(i);
        name = a.name;
        enabled = a.enabled;
        summary =
            a.region.summary() + " · " + (a.dimension.isEmpty() ? "Any dimension" : a.dimension);
      } else {
        var b = app.store.config.commandBindings.get(i);
        name = b.name;
        enabled = b.enabled;
        summary =
            InputConstants.Type.KEYSYM.getOrCreate(b.keyCode).getDisplayName().getString()
                + " → /"
                + ConfigStore.commandText(b.command);
      }
      g.fill(14, y, width - 14, y + 44, i == selected ? 0xff24394f : CARD);
      g.fill(14, y, 17, y + 44, enabled ? ACCENT : MUTED);
      text(g, cut(name + " · " + (enabled ? "ON" : "OFF"), width - 52), 26, y + 8, TEXT);
      text(g, cut(summary, width - 52), 26, y + 26, MUTED);
    }
    if (size() == 0)
      text(
          g,
          coordinateSounds
              ? "Click + Sound to add a target."
              : "Click + Keybind to assign a command.",
          22,
          TOP + 12,
          MUTED);
    g.disableScissor();
    renderChrome(g, mx, my, dt);
  }
}
