package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.WaypointData;
import dev.asarix.skyblockflow.core.WaypointData.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;

public final class WaypointForms {
  public static final class Name extends FormScreen {
    private final String initial;
    private final Consumer<String> use;

    public Name(SkyblockFlowClient a, Screen p, String title, String initial, Consumer<String> use) {
      super(a, p, title);
      this.initial = initial;
      this.use = use;
    }

    @Override
    protected void buildForm() {
      field("name", "Name", initial, 0);
    }

    @Override
    protected void commit() {
      use.accept(WaypointData.validName(value("name")));
      onClose();
    }
  }

  public static final class Delete extends FormScreen {
    private final String detail;
    private final Runnable remove;

    public Delete(SkyblockFlowClient a, Screen p, String title, String detail, Runnable remove) {
      super(a, p, title);
      this.detail = detail;
      this.remove = remove;
    }

    @Override
    protected boolean readOnly() {
      return true;
    }

    @Override
    protected String description() {
      return "Confirm deletion";
    }

    @Override
    protected void buildForm() {
      label(detail, 0, 0);
      action(
          "Delete permanently",
          0,
          38,
          bodyW,
          () -> {
            remove.run();
            onClose();
          });
    }

    @Override
    protected void commit() {
      onClose();
    }
  }

  public static final class Copy extends FormScreen {
    private final WaypointData.Set source;
    private final int dx, dy, dz;

    public Copy(SkyblockFlowClient a, Screen p, WaypointData.Set s, int x, int y, int z) {
      super(a, p, "Copy set with offset");
      source = s;
      dx = x;
      dy = y;
      dz = z;
    }

    @Override
    protected String description() {
      return "Create a separate copy. Categories, colors and auto-hide bounds move together.";
    }

    @Override
    protected void buildForm() {
      field("name", "New set name", source.name + " copy", 0);
      int w = (bodyW - 16) / 3;
      field("x", "Offset X", "" + dx, 0, 48, w);
      field("y", "Offset Y", "" + dy, w + 8, 48, w);
      field("z", "Offset Z", "" + dz, (w + 8) * 2, 48, w);
      label(source.count() + " checkpoints will be copied into your current world.", 0, 98);
      label("Terrain is sampled at the destination; cached terrain is not moved.", 0, 120);
    }

    @Override
    protected void commit() {
      if (app.waypoints.scope().isEmpty())
        throw new IllegalArgumentException("Join the destination world first");
      var copy =
          WaypointData.copy(
              source,
              value("name"),
              app.waypoints.scope(),
              (int) number("x", -30000000, 30000000),
              (int) number("y", -30000000, 30000000),
              (int) number("z", -30000000, 30000000));
      copy.location = app.waypoints.worldLabel();
      if (app.waypoints.data().sets.size() >= 256
          || copy.count()
                  + app.waypoints.data().sets.stream().mapToInt(WaypointData.Set::count).sum()
              > 50000) throw new IllegalArgumentException("Waypoint/set limit reached");
      app.waypoints.data().sets.add(copy);
      app.waypoints.changed();
      minecraft.gui.setScreen(new WaypointSetScreen(app, parent, copy));
    }
  }

  public static final class Range extends FormScreen {
    private final WaypointData.Set set;
    private boolean enabled;

    public Range(SkyblockFlowClient a, Screen p, WaypointData.Set s) {
      super(a, p, "Set coordinate range");
      set = s;
      enabled = s.bounds.enabled;
    }

    @Override
    protected String description() {
      return "Outside this box the set is hidden and excluded from routing. Block bounds are"
          + " inclusive.";
    }

    @Override
    protected void buildForm() {
      toggle("Auto-hide outside range", enabled, 0, () -> enabled = !enabled);
      int w = (bodyW - 16) / 3;
      var b = set.bounds;
      field("x1", "Start X", "" + b.x1, 0, 38, w);
      field("y1", "Start Y", "" + b.y1, w + 8, 38, w);
      field("z1", "Start Z", "" + b.z1, (w + 8) * 2, 38, w);
      field("x2", "End X", "" + b.x2, 0, 84, w);
      field("y2", "End Y", "" + b.y2, w + 8, 84, w);
      field("z2", "End Z", "" + b.z2, (w + 8) * 2, 84, w);
      action("Use my block as start", 0, 134, bodyW / 2 - 4, () -> capture("1"));
      action("Use my block as end", bodyW / 2 + 4, 134, bodyW / 2 - 4, () -> capture("2"));
      action(
          "Fit around checkpoints (+16 blocks)",
          0,
          170,
          bodyW,
          () -> {
            var entries = app.waypoints.data().entries(set, true);
            if (entries.isEmpty()) throw new IllegalArgumentException("Add checkpoints first");
            values.put(
                "x1", "" + (entries.stream().mapToInt(e -> e.point().x).min().orElse(0) - 16));
            values.put(
                "x2", "" + (entries.stream().mapToInt(e -> e.point().x).max().orElse(0) + 16));
            values.put(
                "y1", "" + (entries.stream().mapToInt(e -> e.point().y).min().orElse(0) - 16));
            values.put(
                "y2", "" + (entries.stream().mapToInt(e -> e.point().y).max().orElse(0) + 16));
            values.put(
                "z1", "" + (entries.stream().mapToInt(e -> e.point().z).min().orElse(0) - 16));
            values.put(
                "z2", "" + (entries.stream().mapToInt(e -> e.point().z).max().orElse(0) + 16));
            rebuildWidgets();
          });
    }

    private void capture(String n) {
      Pos p = app.waypoints.playerBlock();
      values.put("x" + n, "" + p.x());
      values.put("y" + n, "" + p.y());
      values.put("z" + n, "" + p.z());
      rebuildWidgets();
    }

    @Override
    protected void commit() {
      Bounds b = new Bounds();
      b.enabled = enabled;
      b.x1 = (int) number("x1", -30000000, 30000000);
      b.x2 = (int) number("x2", -30000000, 30000000);
      b.y1 = (int) number("y1", -30000000, 30000000);
      b.y2 = (int) number("y2", -30000000, 30000000);
      b.z1 = (int) number("z1", -30000000, 30000000);
      b.z2 = (int) number("z2", -30000000, 30000000);
      set.bounds = b;
      app.waypoints.changed();
      onClose();
    }
  }

  public static final class PointEdit extends FormScreen {
    private final Category category;
    private final Point point;

    public PointEdit(SkyblockFlowClient a, Screen p, Category c, Point point) {
      super(a, p, point == null ? "Add checkpoint" : "Edit checkpoint");
      category = c;
      this.point = point;
    }

    @Override
    protected void buildForm() {
      Pos p =
          point == null
              ? (minecraft.player == null ? new Pos(0, 64, 0) : app.waypoints.standingBlock())
              : point.pos();
      field("name", "Optional label", point == null ? "" : point.name, 0);
      int w = (bodyW - 16) / 3;
      field("x", "Block X", "" + p.x(), 0, 48, w);
      field("y", "Block Y", "" + p.y(), w + 8, 48, w);
      field("z", "Block Z", "" + p.z(), (w + 8) * 2, 48, w);
      if (point != null)
        action(
            "Delete checkpoint",
            0,
            104,
            bodyW,
            () -> {
              category.points.remove(point);
              app.waypoints.changed();
              onClose();
            });
    }

    @Override
    protected void commit() {
      String name = value("name").strip();
      if (name.length() > 80)
        throw new IllegalArgumentException("Labels can have up to 80 characters");
      Pos p =
          new Pos(
              (int) number("x", -30000000, 30000000),
              (int) number("y", -30000000, 30000000),
              (int) number("z", -30000000, 30000000));
      if (category.points.stream().anyMatch(q -> q != point && q.pos().equals(p)))
        throw new IllegalArgumentException("This category already has a waypoint on that block");
      Point use = point == null ? new Point(p) : point;
      if (point == null) {
        if (app.waypoints.data().sets.stream().mapToInt(WaypointData.Set::count).sum() >= 50000)
          throw new IllegalArgumentException("Waypoint limit reached");
        category.points.add(use);
      }
      use.x = p.x();
      use.y = p.y();
      use.z = p.z();
      use.name = name;
      app.waypoints.changed();
      onClose();
    }
  }
}
