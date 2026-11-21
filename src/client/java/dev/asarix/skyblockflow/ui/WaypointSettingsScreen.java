package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.Models.Feature;
import dev.asarix.skyblockflow.core.WaypointData;
import net.minecraft.client.gui.screens.Screen;

public final class WaypointSettingsScreen extends FormScreen {
  private final WaypointData draft;
  private final boolean panelOnly;

  public WaypointSettingsScreen(SkyblockFlowClient app, Screen parent) {
    this(app, parent, false);
  }

  public WaypointSettingsScreen(SkyblockFlowClient app, Screen parent, boolean panelOnly) {
    super(app, parent, panelOnly ? "Waypoint map" : "Waypoint settings");
    this.panelOnly = panelOnly;
    draft = app.waypoints.data().settingsCopy();
  }

  @Override
  protected String description() {
    return "Map appearance, reachable routes and checkpoint cooldowns.";
  }

  @Override
  protected void buildForm() {
    if (panelOnly) {
      buildMap();
      return;
    }
    page("Map");
    toggle("Enable waypoints", draft.enabled, 0, () -> draft.enabled = !draft.enabled);
    toggle(
        "Show in-world markers",
        draft.worldMarkers,
        68,
        () -> draft.worldMarkers = !draft.worldMarkers);
    action(
        "Overlay settings  ›",
        0,
        34,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new HudOptions(app, this, dev.asarix.skyblockflow.core.HudSettings.Panel.MAP)));
    page("Routes");
    toggle("Enable routes", draft.routes, 0, () -> draft.routes = !draft.routes);
    toggle(
        "Show animated route stream",
        draft.routeStream,
        34,
        () -> draft.routeStream = !draft.routeStream);
    toggle(
        "Consider flying",
        draft.travel == WaypointData.Travel.FLY,
        68,
        () ->
            draft.travel =
                draft.travel == WaypointData.Travel.WALK
                    ? WaypointData.Travel.FLY
                    : WaypointData.Travel.WALK);
    label("Off: walking paths only. A set can also force walking in no-fly areas.", 0, 102);
    String target =
        draft.sets.stream()
            .filter(s -> s.id.equals(draft.routeSet))
            .map(s -> s.name)
            .findFirst()
            .orElse("All visible sets");
    action(
        "Route scope: " + target,
        0,
        130,
        bodyW,
        () -> {
          var ids = new java.util.ArrayList<String>();
          ids.add("");
          for (var s : draft.sets) ids.add(s.id);
          draft.routeSet = ids.get((Math.max(0, ids.indexOf(draft.routeSet)) + 1) % ids.size());
          rebuildWidgets();
        });
    field("n", "Nearest checkpoints to find (by path length)", "" + draft.nearestCount, 164);
    field(
        "history",
        "Re-enable after this many other checkpoint visits",
        "" + draft.consumedHistory,
        210);
    field("arrival", "Arrival radius (blocks)", "" + draft.arrivalRadius, 256);
    field("search", "Search radius (blocks)", "" + draft.searchRadius, 302);
    field("height", "Search height above/below player (blocks)", "" + draft.searchHeight, 348);
    label("Hidden sets/categories and consumed points are excluded.", 0, 398);
    label(
        "Only loaded terrain is routed. Unreachable points are never straight-line guesses.",
        0,
        420);
    label("History ≥ remaining checkpoints can exhaust a route; clear visits to restart.", 0, 442);
    action(
        "Clear consumed checkpoints now",
        0,
        472,
        bodyW,
        () -> {
          app.waypoints.clearConsumed();
          notice = "Consumed checkpoints cleared";
        });
    page("Keys");
    keyField("Clear consumed checkpoints", draft.clearKey, 0, key -> draft.clearKey = key);
    keyField("Toggle minimap", draft.mapKey, 36, key -> draft.mapKey = key);
    keyField("Exit waypoint creation", draft.creationKey, 72, key -> draft.creationKey = key);
    label("Unbound by default, so existing controls stay available.", 0, 110);
    label("Open the waypoint browser with /waypoints or the feature menu.", 0, 134);
  }

  private void buildMap() {
    page("Map");
    toggle("Show minimap", draft.minimap, 34, () -> draft.minimap = !draft.minimap);
    field("size", "Minimap size (GUI pixels)", "" + draft.mapSize, 108);
    field("range", "Map radius (blocks)", "" + draft.mapRange, 154);
    action(
        "Corner: "
            + new String[] {"Top left", "Top right", "Bottom left", "Bottom right"}
                [draft.mapCorner],
        0,
        204,
        bodyW,
        () -> {
          draft.mapCorner = (draft.mapCorner + 1) % 4;
          rebuildWidgets();
        });
    field("ox", "Horizontal margin", "" + draft.mapOffsetX, 240);
    field("oy", "Vertical margin", "" + draft.mapOffsetY, 286);
    label("Maps record explored surface terrain. Dark squares are unknown.", 0, 336);
    label("The set map stays available after you leave the area or server.", 0, 358);
  }

  @Override
  protected void commit() {
    var live = app.waypoints.data();
    if (panelOnly) {
      int size = (int) number("size", 90, 240),
          range = (int) number("range", 16, 256),
          ox = (int) number("ox", 0, 1000),
          oy = (int) number("oy", 0, 1000);
      live.mapSize = size;
      live.mapRange = range;
      live.mapOffsetX = ox;
      live.mapOffsetY = oy;
      live.mapCorner = draft.mapCorner;
      live.minimap = draft.minimap;
      app.waypoints.changed();
      onClose();
      return;
    }
    int n = (int) number("n", 1, 20),
        history = (int) number("history", 0, 50000),
        search = (int) number("search", 16, 128),
        height = (int) number("height", 8, 64);
    float arrival = decimal("arrival", .5f, 10);
    java.util.Set<Integer> keys = new java.util.HashSet<>();
    for (int k : new int[] {draft.clearKey, draft.mapKey, draft.creationKey})
      if (k >= 0 && !keys.add(k))
        throw new IllegalArgumentException("Choose different waypoint shortcuts");
    draft.mapSize = live.mapSize;
    draft.mapRange = live.mapRange;
    draft.mapOffsetX = live.mapOffsetX;
    draft.mapOffsetY = live.mapOffsetY;
    draft.minimap = live.minimap;
    draft.mapCorner = live.mapCorner;
    draft.nearestCount = n;
    draft.consumedHistory = history;
    draft.searchRadius = search;
    draft.searchHeight = height;
    draft.arrivalRadius = arrival;
    draft.validate();
    app.waypoints.store.data = draft;
    app.store.config.feature(Feature.WAYPOINTS).enabled = draft.enabled;
    app.waypoints.changed();
    onClose();
  }
}
