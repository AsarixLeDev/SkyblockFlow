package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.WaypointData.*;
import dev.asarix.skyblockflow.ui.Ui;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** One reusable texture per view; terrain uploads are capped at four per second. */
public final class WaypointMapCanvas implements AutoCloseable {
  private final Waypoints service;
  private final Identifier id;
  private DynamicTexture texture;
  private long nextUpdate;
  private String world = "";
  public double centerX, centerZ, scale = 2;
  public int x, y, w, h;
  private final int resolution;

  public WaypointMapCanvas(Waypoints service, String name, int resolution) {
    this.service = service;
    this.resolution = resolution;
    id = Identifier.fromNamespaceAndPath("skyblockflow", "map/" + name);
  }

  public void invalidate() {
    nextUpdate = 0;
  }

  public double worldX(double sx) {
    return centerX + (sx - x - w * .5) * scale;
  }

  public double worldZ(double sy) {
    return centerZ + (sy - y - h * .5) * scale;
  }

  public int screenX(double wx) {
    return (int) Math.round(x + w * .5 + (wx - centerX) / scale);
  }

  public int screenY(double wz) {
    return (int) Math.round(y + h * .5 + (wz - centerZ) / scale);
  }

  public void draw(
      GuiGraphicsExtractor g,
      String world,
      List<Entry> entries,
      String selected,
      boolean player,
      boolean route) {
    long now = System.nanoTime();
    if (texture == null) {
      texture = new DynamicTexture("Flow waypoint map", resolution, resolution, false);
      Minecraft.getInstance().getTextureManager().register(id, texture);
      nextUpdate = 0;
    }
    if (!this.world.equals(world)) {
      this.world = world;
      nextUpdate = 0;
    }
    if (now >= nextUpdate) {
      int[] pixels =
          service.cache.image(
              world, centerX, centerZ, w * scale, h * scale, resolution, resolution);
      var image = texture.getPixels();
      for (int py = 0; py < resolution; py++)
        for (int px = 0; px < resolution; px++)
          image.setPixel(px, py, pixels[py * resolution + px]);
      texture.upload();
      nextUpdate = now + 250_000_000L;
    }
    g.blit(id, x, y, x + w, y + h, 0, 1, 0, 1);
    g.enableScissor(x, y, x + w, y + h);
    if (route) {
      int segmentBudget = 800;
      for (int r = service.routes.size() - 1; r >= 0; r--) {
        int routeBudget = r == 0 ? 400 : Math.min(40, segmentBudget);
        if (routeBudget <= 0) continue;
        var path = service.routes.get(r).path();
        int color = r == 0 ? 0xffedf9fa : 0xa079dfbe;
        int start = 0;
        for (int i = 1; i < path.size(); i++) {
          boolean end = i == path.size() - 1;
          if (!end) {
            Pos a = path.get(i - 1), b = path.get(i), c = path.get(i + 1);
            end = b.x() - a.x() != c.x() - b.x() || b.z() - a.z() != c.z() - b.z();
          }
          if (end) {
            if (--routeBudget < 0) break;
            if (r > 0) segmentBudget--;
            clippedLine(
                g,
                screenX(path.get(start).x() + .5),
                screenY(path.get(start).z() + .5),
                screenX(path.get(i).x() + .5),
                screenY(path.get(i).z() + .5),
                color,
                r == 0 ? 2 : 1);
            start = i;
          }
        }
      }
    }

    int count = 0;
    for (Entry e : entries) {
      int px = screenX(e.point().x + .5), py = screenY(e.point().z + .5);
      if (px < x - 4 || px > x + w + 4 || py < y - 4 || py > y + h + 4) continue;
      if (++count > 5000) break;
      boolean chosen = e.point().id.equals(selected), consumed = service.consumed(e);
      int size = chosen ? 5 : 3;
      g.fill(px - size - 1, py - size - 1, px + size + 2, py + size + 2, 0xd8101820);
      if (consumed) g.outline(px - size, py - size, size * 2 + 1, size * 2 + 1, e.color());
      else g.fill(px - size, py - size, px + size + 1, py + size + 1, e.color());
      if (chosen) g.outline(px - 7, py - 7, 15, 15, 0xffffffff);
    }
    var mc = Minecraft.getInstance();
    if (player && mc.player != null && world.equals(service.scope())) {
      int px = screenX(mc.player.getX()), py = screenY(mc.player.getZ());
      double yaw = Math.toRadians(mc.player.getYRot());
      line(
          g,
          px,
          py,
          px - (int) (Math.sin(yaw) * 10),
          py + (int) (Math.cos(yaw) * 10),
          0xffffffff,
          2);
      g.fill(px - 3, py - 3, px + 4, py + 4, 0xffffffff);
      g.fill(px - 1, py - 1, px + 2, py + 2, 0xff162436);
    }
    g.disableScissor();
    g.outline(x, y, w, h, 0xff425268);
    Ui.text(g, "N", x + w / 2 - 3, y + 4, 0xffffffff);
  }

  private void clippedLine(
      GuiGraphicsExtractor g,
      double ax,
      double ay,
      double bx,
      double by,
      int color,
      int thickness) {
    double dx = bx - ax, dy = by - ay, lo = 0, hi = 1;
    double[] p = {-dx, dx, -dy, dy}, q = {ax - x, x + w - ax, ay - y, y + h - ay};
    for (int i = 0; i < 4; i++) {
      if (p[i] == 0) {
        if (q[i] < 0) return;
        continue;
      }
      double r = q[i] / p[i];
      if (p[i] < 0) lo = Math.max(lo, r);
      else hi = Math.min(hi, r);
      if (lo > hi) return;
    }
    line(
        g,
        (int) (ax + lo * dx),
        (int) (ay + lo * dy),
        (int) (ax + hi * dx),
        (int) (ay + hi * dy),
        color,
        thickness);
  }

  public static void line(
      GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color, int thickness) {
    if (y1 == y2) {
      g.fill(Math.min(x1, x2), y1, Math.max(x1, x2) + thickness, y1 + thickness, color);
      return;
    }
    if (x1 == x2) {
      g.fill(x1, Math.min(y1, y2), x1 + thickness, Math.max(y1, y2) + thickness, color);
      return;
    }
    int dx = x2 - x1, dy = y2 - y1, n = Math.min(2048, Math.max(Math.abs(dx), Math.abs(dy)));
    if (n == 0) return;
    for (int i = 0; i <= n; i++) {
      int x = x1 + (int) ((long) dx * i / n), y = y1 + (int) ((long) dy * i / n);
      g.fill(x, y, x + thickness, y + thickness, color);
    }
  }

  @Override
  public void close() {
    if (texture != null) {
      Minecraft.getInstance().getTextureManager().release(id);
      texture = null;
    }
  }
}
