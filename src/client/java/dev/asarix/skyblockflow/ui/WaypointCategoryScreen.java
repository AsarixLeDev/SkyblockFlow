package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.WaypointData;
import net.minecraft.client.gui.screens.Screen;

public final class WaypointCategoryScreen extends FormScreen {
  private final WaypointData.Set set;
  private final WaypointData.Category category;

  public WaypointCategoryScreen(
      SkyblockFlowClient app, Screen parent, WaypointData.Set set, WaypointData.Category c) {
    super(app, parent, c.name);
    this.set = set;
    category = c;
  }

  @Override
  protected boolean readOnly() {
    return true;
  }

  @Override
  protected String description() {
    return "Enable creation, close this menu, then right-click a block to add/remove its marker.";
  }

  @Override
  protected void buildForm() {
    toggle(
        "Waypoint creation",
        app.waypoints.editingCategory.equals(category.id),
        0,
        () -> app.waypoints.edit(category));
    toggle(
        "Show category",
        category.shown,
        34,
        () -> {
          category.shown = !category.shown;
          app.waypoints.changed();
        });
    action(
        "Category color · #" + String.format("%06X", category.color),
        0,
        68,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new ColorPickerScreen(
                    app,
                    this,
                    "Category color",
                    category.color,
                    color -> {
                      category.color = color;
                      app.waypoints.changed();
                    })));
    action(
        "Rename category",
        0,
        102,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new WaypointForms.Name(
                    app,
                    this,
                    "Rename category",
                    category.name,
                    name -> {
                      category.name = name;
                      app.waypoints.changed();
                    })));
    action(
        "Inspect checkpoints on map",
        0,
        136,
        bodyW,
        () -> minecraft.gui.setScreen(new WaypointMapScreen(app, this, set)));
    action(
        "+ Add coordinates manually",
        0,
        170,
        bodyW,
        () -> minecraft.gui.setScreen(new WaypointForms.PointEdit(app, this, category, null)));
    label("Right-click again on the same block to remove its waypoint.", 0, 206);
    label("Creation mode cancels normal block use until you turn it off.", 0, 228);
    label("Creation stops automatically when you leave the world.", 0, 250);
    action(
        "Delete category…",
        0,
        286,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new WaypointForms.Delete(
                    app,
                    parent,
                    "Delete " + category.name + "?",
                    category.points.size() + " checkpoints will be removed.",
                    () -> {
                      set.categories.remove(category);
                      if (app.waypoints.editingCategory.equals(category.id))
                        app.waypoints.editingCategory = "";
                      app.waypoints.changed();
                    })));
  }

  @Override
  protected void commit() {
    onClose();
  }
}
