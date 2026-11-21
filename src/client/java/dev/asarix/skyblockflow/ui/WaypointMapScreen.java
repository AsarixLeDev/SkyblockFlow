package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.WaypointMapCanvas;
import dev.asarix.skyblockflow.core.WaypointData;
import dev.asarix.skyblockflow.core.WaypointData.Entry;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

public final class WaypointMapScreen extends FlowScreen {
  private final WaypointData.Set set;
  public final WaypointMapCanvas map;
  private String selected = "";
  private boolean includeHidden, initialized, dragging;
  private double clickX, clickY;

  public WaypointMapScreen(SkyblockFlowClient app, Screen parent, WaypointData.Set set) {
    super(app, parent, set.name + " · map");
    this.set = set;
    map = new WaypointMapCanvas(app.waypoints, "inspector", 256);
  }

  private List<Entry> points() {
    return app.waypoints.data().entries(set, includeHidden);
  }

  private Entry selection() {
    return points().stream().filter(e -> e.point().id.equals(selected)).findFirst().orElse(null);
  }

  @Override
  protected void init() {
    super.init();
    button(width - 88, 10, 74, "Back", this::onClose);
    int w = (width - 48) / 4;
    button(16, 72, w, "Fit set", this::fit);
    button(
        20 + w,
        72,
        w,
        "My position",
        () -> {
          if (minecraft.player == null || !set.world.equals(app.waypoints.scope())) {
            notice = "This set is in another world";
            return;
          }
          map.centerX = minecraft.player.getX();
          map.centerZ = minecraft.player.getZ();
          map.invalidate();
        });
    button(
        24 + w * 2,
        72,
        w,
        includeHidden ? "Hide hidden" : "Inspect hidden",
        () -> {
          includeHidden = !includeHidden;
          rebuildWidgets();
        });
    button(
        28 + w * 3,
        72,
        w,
        "Copy with offset",
        () -> minecraft.gui.setScreen(new WaypointForms.Copy(app, this, set, 0, 0, 0)));
    map.x = 16;
    map.y = 104;
    map.w = width - 32;
    map.h = Math.max(36, height - 188);
    button(
        16,
        height - 56,
        (width - 40) / 2,
        "Edit selected",
        () -> {
          Entry e = selection();
          if (e == null) {
            notice = "Click a checkpoint first";
            return;
          }
          minecraft.gui.setScreen(new WaypointForms.PointEdit(app, this, e.category(), e.point()));
        });
    button(
        24 + (width - 40) / 2, height - 56, (width - 40) / 2, "Paste set here", this::paste, true);
    if (!initialized) {
      initialized = true;
      fit();
    }
  }

  public void fit() {
    var entries = points();
    if (entries.isEmpty()) {
      if (minecraft.player != null && set.world.equals(app.waypoints.scope())) {
        map.centerX = minecraft.player.getX();
        map.centerZ = minecraft.player.getZ();
      }
      map.scale = 1;
      return;
    }
    int x1 = entries.stream().mapToInt(e -> e.point().x).min().orElse(0),
        x2 = entries.stream().mapToInt(e -> e.point().x).max().orElse(0),
        z1 = entries.stream().mapToInt(e -> e.point().z).min().orElse(0),
        z2 = entries.stream().mapToInt(e -> e.point().z).max().orElse(0);
    map.centerX = (x1 + (double) x2) / 2 + .5;
    map.centerZ = (z1 + (double) z2) / 2 + .5;
    map.scale =
        Math.clamp(
            Math.max(
                (x2 - (double) x1 + 32) / Math.max(1, map.w),
                (z2 - (double) z1 + 32) / Math.max(1, map.h)),
            .125,
            256);
    map.invalidate();
  }

  private void paste() {
    Entry e = selection();
    if (e == null) {
      notice = "Click the checkpoint that should line up with the block under your feet";
      return;
    }
    try {
      var here = app.waypoints.standingBlock();
      var p = e.point();
      minecraft.gui.setScreen(
          new WaypointForms.Copy(
              app,
              this,
              set,
              Math.subtractExact(here.x(), p.x),
              Math.subtractExact(here.y(), p.y),
              Math.subtractExact(here.z(), p.z)));
    } catch (Exception ex) {
      notice = ex.getMessage();
    }
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (super.mouseClicked(e, twice)) return true;
    if (e.button() != 0 || !inside(e.x(), e.y(), map.x, map.y, map.w, map.h)) return false;
    clickX = e.x();
    clickY = e.y();
    dragging = true;
    return true;
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    if (!dragging) return false;
    map.centerX -= dx * map.scale;
    map.centerZ -= dy * map.scale;
    map.invalidate();
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent e) {
    if (dragging) {
      dragging = false;
      if (Math.hypot(e.x() - clickX, e.y() - clickY) < 5) {
        var candidates =
            points().stream()
                .filter(
                    p ->
                        Math.hypot(
                                map.screenX(p.point().x + .5) - e.x(),
                                map.screenY(p.point().z + .5) - e.y())
                            <= 9)
                .sorted(Comparator.comparingInt(p -> p.point().y))
                .toList();
        if (!candidates.isEmpty()) {
          int i = -1;
          for (int k = 0; k < candidates.size(); k++)
            if (candidates.get(k).point().id.equals(selected)) i = k;
          selected = candidates.get((i + 1) % candidates.size()).point().id;
          notice =
              candidates.size() > 1
                  ? "Stacked checkpoints: click again to cycle heights"
                  : "Checkpoint selected";
        } else selected = "";
      }
      return true;
    }
    return super.mouseReleased(e);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    if (!inside(x, y, map.x, map.y, map.w, map.h)) return super.mouseScrolled(x, y, sx, sy);
    double beforeX = map.worldX(x), beforeZ = map.worldZ(y);
    map.scale = Math.clamp(map.scale * Math.pow(1.25, -sy), .125, 256);
    map.centerX += beforeX - map.worldX(x);
    map.centerZ += beforeZ - map.worldZ(y);
    map.invalidate();
    return true;
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (e.key() == GLFW.GLFW_KEY_DELETE) {
      var selected = selection();
      if (selected != null) {
        selected.category().points.remove(selected.point());
        app.waypoints.changed();
        this.selected = "";
        return true;
      }
    }
    return super.keyPressed(e);
  }

  @Override
  public void removed() {
    map.close();
    super.removed();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(
        g, "Drag to pan · Scroll to zoom · Click a checkpoint to select it · Delete removes it");
    map.draw(
        g,
        set.world,
        points(),
        selected,
        true,
        set.world.equals(app.waypoints.scope()) && app.waypoints.data().routes);
    if (set.bounds.enabled) {
      int x1 = map.screenX(Math.min(set.bounds.x1, set.bounds.x2)),
          x2 = map.screenX(Math.max(set.bounds.x1, set.bounds.x2) + 1.0),
          z1 = map.screenY(Math.min(set.bounds.z1, set.bounds.z2)),
          z2 = map.screenY(Math.max(set.bounds.z1, set.bounds.z2) + 1.0);
      g.enableScissor(map.x, map.y, map.x + map.w, map.y + map.h);
      g.outline(x1, z1, Math.max(1, x2 - x1), Math.max(1, z2 - z1), 0xaaffc578);
      g.disableScissor();
    }
    Entry e = selection();
    String info =
        e == null
            ? set.count()
                + " checkpoints · "
                + String.format(Locale.ROOT, "%.2f", map.scale)
                + " blocks/pixel"
            : e.category().name;
    if (e != null) info = e.label() + " · " + e.point().pos().text() + " · " + e.category().name;
    text(g, cut(info, width - 32), 16, height - 76, e == null ? MUTED : e.color());
    renderChrome(g, mx, my, dt);
    if (inside(mx, my, 24 + (width - 40) / 2, height - 56, (width - 40) / 2, 22))
      g.setTooltipForNextFrame(Ui.label("I'm on this block. paste the entire set here"), mx, my);
    else if (inside(mx, my, map.x, map.y, map.w, map.h))
      for (Entry p : points())
        if (Math.hypot(map.screenX(p.point().x + .5) - mx, map.screenY(p.point().z + .5) - my)
            < 7) {
          g.setTooltipForNextFrame(Ui.label(p.label() + " · " + p.point().pos().text()), mx, my);
          break;
        }
  }
}
