package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.WaypointData.*;
import dev.asarix.skyblockflow.ui.Ui;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Vector4f;

/** HUD projection follows the actual camera, including FOV changes and third person. */
public final class WaypointRenderer implements AutoCloseable {
  private final Waypoints service;
  private final WaypointMapCanvas map;

  public WaypointRenderer(Waypoints service) {
    this.service = service;
    map = new WaypointMapCanvas(service, "hud", 128);
  }

  public void render(GuiGraphicsExtractor g) {
    var mc = Minecraft.getInstance();
    var d = service.data();
    if (mc.player == null
        || mc.level == null
        || !service.shown()
        || mc.gui.hud.isHidden()
        || service.app.celebrations.hideMinecraftHud()) return;
    if (mc.gui.screen() != null) return;
    int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
    if (d.routeStream && !service.routes.isEmpty()) {
      var route = service.routes.getFirst();
      double phase = (System.nanoTime() / 1_000_000L % 1200) / 1200.0;
      var path = route.path();
      // Small animated beads form a stream; at most 160 segments enter the HUD each frame.
      for (int i = 0; i < Math.min(path.size() - 1, 160); i++) {
        Pos a = path.get(i), b = path.get(i + 1);
        double px = a.x() + .5 + (b.x() - a.x()) * phase,
            py = a.y() + .14 + (b.y() - a.y()) * phase,
            pz = a.z() + .5 + (b.z() - a.z()) * phase;
        int[] screen = project(px, py, pz, width, height);
        if (screen == null) continue;
        int color = i % 3 == 0 ? 0xffffffff : 0xff79dfbe;
        g.fill(screen[0] - 2, screen[1] - 2, screen[0] + 3, screen[1] + 3, 0x80202a38);
        g.fill(screen[0] - 1, screen[1] - 1, screen[0] + 2, screen[1] + 2, color);
      }
    }
    if (d.worldMarkers) {
      var entries = new ArrayList<>(service.nearby(mc.player.getX(), mc.player.getZ(), 256));
      var playerBlock = service.playerBlock();
      entries.sort(Comparator.comparingDouble(e -> e.point().pos().distanceSquared(playerBlock)));
      int count = 0;
      for (Entry e : entries) {
        if (count++ >= 48) break;
        var p = e.point();
        int[] at = project(p.x + .5, p.y + 1.25, p.z + .5, width, height);
        if (at == null) continue;
        boolean consumed = service.consumed(e);
        int color = consumed ? 0xff788596 : e.color();
        g.outline(at[0] - 4, at[1] - 4, 9, 9, color);
        if (!consumed && count <= 16) {
          String label = e.label() + " · " + Math.round(p.pos().distance(playerBlock)) + "m";
          int tw = Ui.width(label);
          g.fill(at[0] - tw / 2 - 4, at[1] - 19, at[0] + tw / 2 + 4, at[1] - 7, 0xc016202d);
          Ui.text(g, label, at[0] - tw / 2, at[1] - 17, color);
        }
      }
    }
    if (d.minimap) {
      int size = Math.min(d.mapSize, Math.min(width - 24, height - 62));
      map.w = map.h = size;
      map.x = (d.mapCorner % 2 == 0) ? d.mapOffsetX : width - size - d.mapOffsetX;
      map.y = d.mapCorner < 2 ? d.mapOffsetY : height - size - 34 - d.mapOffsetY;
      map.x = Math.clamp(map.x, 2, width - size - 2);
      map.y = Math.clamp(map.y, 2, height - size - 34);
      map.centerX = mc.player.getX();
      map.centerZ = mc.player.getZ();
      map.scale = d.mapRange * 2.0 / size;
      var hud = SkyblockFlowClient.INSTANCE.store.config.hud;
      var paint =
          new dev.asarix.skyblockflow.ui.HudPaint(
              hud.theme(dev.asarix.skyblockflow.core.HudSettings.Panel.MAP), hud.legacy);
      if (!hud.legacy) paint.panel(g, map.x - 3, map.y - 3, size + 6, size + 36);
      else g.fill(map.x - 3, map.y - 3, map.x + size + 3, map.y + size + 33, 0xd9101823);
      map.draw(
          g,
          service.scope(),
          service.nearby(mc.player.getX(), mc.player.getZ(), d.mapRange + 8),
          "",
          true,
          d.routes);
      paint.text(
          g,
          mc.player.blockPosition().getX()
              + "  "
              + mc.player.blockPosition().getY()
              + "  "
              + mc.player.blockPosition().getZ(),
          map.x + 5,
          map.y + size + 5,
          hud.legacy ? 0xffecf2f8 : paint.ink());
      String footer =
          d.routes
              ? (service.routes.isEmpty()
                  ? (service.status.startsWith("No reachable")
                      ? "No route in range"
                      : "Finding a route…")
                  : String.format(
                      Locale.ROOT,
                      "%.1fm · %d consumed",
                      service.routes.getFirst().length(),
                      service.visits.count(d.consumedHistory)))
              : "Waypoints";
      paint.text(
          g,
          paint.trim(footer, size - 10),
          map.x + 5,
          map.y + size + 18,
          hud.legacy ? 0xff79dfbe : paint.secondary());
    }
    if (!service.editingCategory.isEmpty()) {
      String text =
          (service.creationAvailable()
                  ? "Waypoint creation · "
                  : "Creation paused (hidden/outside range) · ")
              + service.creationName()
              + " · right-click toggles a block";
      int tw = Math.min(width - 20, Ui.width(text));
      g.fill((width - tw) / 2 - 6, height - 63, (width + tw) / 2 + 6, height - 45, 0xe0172533);
      Ui.text(g, Ui.trim(text, tw), (width - tw) / 2, height - 58, 0xffffc578);
    }
  }

  public static int[] project(double x, double y, double z, int w, int h) {
    var s =
        Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
    Vector4f p =
        new Vector4f((float) (x - s.pos.x), (float) (y - s.pos.y), (float) (z - s.pos.z), 1);
    s.viewRotationMatrix.transform(p);
    s.projectionMatrix.transform(p);
    if (p.w <= .05) return null;
    float nx = p.x / p.w, ny = p.y / p.w;
    if (Math.abs(nx) > 1.1 || Math.abs(ny) > 1.1) return null;
    return new int[] {(int) ((nx + 1) * w / 2), (int) ((1 - ny) * h / 2)};
  }

  @Override
  public void close() {
    map.close();
  }
}
