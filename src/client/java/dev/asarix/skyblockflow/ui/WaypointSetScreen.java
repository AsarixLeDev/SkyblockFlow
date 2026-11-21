package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.WaypointData;
import net.minecraft.client.gui.screens.Screen;

public final class WaypointSetScreen extends FormScreen {
  private final WaypointData.Set set;

  public WaypointSetScreen(SkyblockFlowClient app, Screen parent, WaypointData.Set set) {
    super(app, parent, set.name);
    this.set = set;
  }

  @Override
  protected boolean readOnly() {
    return true;
  }

  @Override
  protected String description() {
    return "Categories inherit visibility from this set. Changes save immediately.";
  }

  @Override
  protected void buildForm() {
    page("Categories");
    action(
        "Open set map",
        0,
        0,
        bodyW / 2 - 4,
        () -> minecraft.gui.setScreen(new WaypointMapScreen(app, this, set)));
    action(
        "+ Add category",
        bodyW / 2 + 4,
        0,
        bodyW / 2 - 4,
        () ->
            minecraft.gui.setScreen(
                new WaypointForms.Name(
                    app,
                    this,
                    "New category",
                    "",
                    name -> {
                      if (set.categories.size() >= 256)
                        throw new IllegalArgumentException("Maximum 256 categories per set");
                      var c = new WaypointData.Category();
                      c.name = name;
                      c.color =
                          new int[] {0x79dfbe, 0xffc578, 0x9bb6ff, 0xff8393, 0xb59bff}
                              [set.categories.size() % 5];
                      set.categories.add(c);
                      app.waypoints.changed();
                    })));
    label(set.count() + " checkpoints · one creation category at a time", 0, 34);
    int y = 64;
    for (var c : set.categories) {
      action(
          c.name + "  ·  " + c.points.size() + " points",
          0,
          y,
          bodyW - 100,
          () -> minecraft.gui.setScreen(new WaypointCategoryScreen(app, this, set, c)));
      action(
          c.shown ? "Shown" : "Hidden",
          bodyW - 94,
          y,
          94,
          () -> {
            c.shown = !c.shown;
            app.waypoints.changed();
            rebuildWidgets();
          });
      action(
          app.waypoints.editingCategory.equals(c.id)
              ? "Stop waypoint creation"
              : "Create by right-clicking blocks",
          0,
          y + 28,
          bodyW,
          () -> {
            app.waypoints.edit(c);
            rebuildWidgets();
          });
      y += 70;
    }
    if (set.categories.isEmpty())
      label("Add a category to choose a color and start marking blocks.", 0, y);
    page("Set options");
    toggle(
        "Show set",
        set.shown,
        0,
        () -> {
          set.shown = !set.shown;
          app.waypoints.changed();
        });
    action(
        "Rename set",
        0,
        34,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new WaypointForms.Name(
                    app,
                    this,
                    "Rename set",
                    set.name,
                    name -> {
                      set.name = name;
                      app.waypoints.changed();
                    })));
    action(
        "Copy set with offset…",
        0,
        68,
        bodyW,
        () -> minecraft.gui.setScreen(new WaypointForms.Copy(app, this, set, 0, 0, 0)));
    action(
        "Auto-hide outside coordinate range…",
        0,
        102,
        bodyW,
        () -> minecraft.gui.setScreen(new WaypointForms.Range(app, this, set)));
    label(
        set.bounds.enabled
            ? "Range enabled: "
                + set.bounds.x1
                + ", "
                + set.bounds.y1
                + ", "
                + set.bounds.z1
                + " → "
                + set.bounds.x2
                + ", "
                + set.bounds.y2
                + ", "
                + set.bounds.z2
            : "No coordinate range restriction",
        0,
        136);
    toggle(
        "Allow flying routes",
        set.allowFlight,
        164,
        () -> {
          set.allowFlight = !set.allowFlight;
          app.waypoints.changed();
        });
    label("Off forces walking even if Consider flying is enabled globally.", 0, 198);
    action(
        "Route through this set",
        0,
        226,
        bodyW,
        () -> {
          var d = app.waypoints.data();
          d.routeSet = set.id;
          d.routes = true;
          app.waypoints.changed();
          notice = "Routes enabled for " + set.name;
        });
    label("Saved in: " + set.location, 0, 264);
    action(
        "Delete set…",
        0,
        300,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new WaypointForms.Delete(
                    app,
                    parent,
                    "Delete " + set.name + "?",
                    set.count() + " checkpoints and all categories will be removed.",
                    () -> {
                      app.waypoints.data().sets.remove(set);
                      app.waypoints.editingCategory = "";
                      app.waypoints.changed();
                    })));
  }

  @Override
  public void extractRenderState(
      net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float dt) {
    super.extractRenderState(g, mx, my, dt);
    if (onPage("Categories")) {
      g.enableScissor(bodyX, formTop(), bodyX + bodyW, height - 42);
      int y = formTop() + 64 - scroll;
      for (var category : set.categories) {
        g.fill(bodyX + 7, y + 5, bodyX + 10, y + 17, 0xff000000 | category.color);
        y += 70;
      }
      g.disableScissor();
    }
  }

  @Override
  protected void commit() {
    onClose();
  }
}
