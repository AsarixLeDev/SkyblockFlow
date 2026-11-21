package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.WaypointData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/** Searchable set cards keep the common actions one click from the feature browser. */
public final class WaypointScreen extends FlowScreen {
  private int scroll, top = 108, bottom, columns, cardW;
  private String query = "";

  public WaypointScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Waypoints");
  }

  private java.util.List<WaypointData.Set> sets() {
    return app.waypoints.data().sets.stream()
        .filter(
            s ->
                s.name
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains(query.toLowerCase(java.util.Locale.ROOT)))
        .toList();
  }

  @Override
  protected void init() {
    super.init();
    button(width - 88, 10, 74, "Back", this::onClose);
    int w = (width - 40) / 3;
    button(
        16,
        72,
        w,
        "+ New set",
        () ->
            minecraft.gui.setScreen(
                new WaypointForms.Name(
                    app,
                    this,
                    "Create set",
                    "",
                    name -> {
                      app.waypoints.addSet(name);
                    })));
    button(
        20 + w,
        72,
        w,
        "Map & routes",
        () -> minecraft.gui.setScreen(new WaypointSettingsScreen(app, this)));
    button(
        24 + w * 2,
        72,
        w,
        "Minimap: " + (app.waypoints.data().minimap ? "ON" : "OFF"),
        () -> {
          var d = app.waypoints.data();
          d.minimap = !d.minimap;
          app.waypoints.changed();
          rebuildWidgets();
        });
    var search = edit(16, 104, width - 32, query);
    search.setHint(Ui.label("Find a set…"));
    search.setResponder(
        s -> {
          query = s;
          scroll = 0;
        });
    top = 138;
    bottom = height - 34;
    columns = width >= 600 ? 2 : 1;
    cardW = (width - 32 - (columns - 1) * 10) / columns;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll -= (int) (sy * 34);
    clamp();
    return true;
  }

  private void clamp() {
    scroll =
        Math.clamp(
            scroll, 0, Math.max(0, (sets().size() + columns - 1) / columns * 84 - (bottom - top)));
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (super.mouseClicked(e, twice)) return true;
    if (e.button() != 0 || e.y() < top || e.y() >= bottom) return false;
    var all = sets();
    for (int i = 0; i < all.size(); i++) {
      int x = 16 + i % columns * (cardW + 10), y = top + i / columns * 84 - scroll;
      var s = all.get(i);
      if (!inside(e.x(), e.y(), x, y, cardW, 76)) continue;
      if (inside(e.x(), e.y(), x + cardW - 44, y + 10, 32, 20)) {
        s.shown = !s.shown;
        app.waypoints.changed();
      } else minecraft.gui.setScreen(new WaypointSetScreen(app, this, s));
      return true;
    }
    return false;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g, "Organize places, explore maps and follow the nearest reachable checkpoint.");
    clamp();
    g.enableScissor(14, top, width - 14, bottom);
    var all = sets();
    for (int i = 0; i < all.size(); i++) {
      var s = all.get(i);
      int x = 16 + i % columns * (cardW + 10), y = top + i / columns * 84 - scroll;
      boolean hover = inside(mx, my, x, y, cardW, 76);
      Ui.surface(g, x, y, cardW, 76, hover ? 0xff27374a : CARD, hover ? ACCENT : BORDER);
      text(g, cut(s.name, cardW - 72), x + 12, y + 12, TEXT);
      Ui.toggle(g, x + cardW - 40, y + 12, s.shown);
      text(
          g,
          s.categories.size() + " categories · " + s.count() + " checkpoints",
          x + 12,
          y + 33,
          MUTED);
      String state = s.world.equals(app.waypoints.scope()) ? "This world" : "Saved world";
      if (s.bounds.enabled) state += " · range auto-hide";
      if (!s.allowFlight) state += " · walk only";
      text(
          g,
          cut(state, cardW - 24),
          x + 12,
          y + 54,
          s.world.equals(app.waypoints.scope()) ? ACCENT : GOLD);
    }
    if (all.isEmpty()) {
      text(g, "Your places, organized", 28, top + 20, TEXT);
      text(g, "Create a set, add a category, then enable waypoint creation.", 28, top + 42, MUTED);
    }
    g.disableScissor();
    renderChrome(g, mx, my, dt);
    Ui.scrollBar(
        g, width - 8, top, bottom - top, (all.size() + columns - 1) / columns * 84, scroll);
  }
}
