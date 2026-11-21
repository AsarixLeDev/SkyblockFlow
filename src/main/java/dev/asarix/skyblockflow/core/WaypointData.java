package dev.asarix.skyblockflow.core;

import java.util.*;

/** Positions are clicked block coordinates, scoped to an exact server/world and dimension. */
public final class WaypointData {
  public int version = 1;
  public boolean enabled = true, minimap = true, worldMarkers = true, routeStream = true;
  public int mapSize = 140, mapRange = 64, mapCorner = 1;
  public int mapOffsetX = 12, mapOffsetY = 12;
  public boolean routes = false;
  public int nearestCount = 3, consumedHistory = 5, searchRadius = 64, searchHeight = 24;
  public double arrivalRadius = 2.0;
  public String routeSet = "";
  public Travel travel = Travel.WALK;
  public int clearKey = -1, mapKey = -1, creationKey = -1;
  public List<Set> sets = new ArrayList<>();

  public WaypointData settingsCopy() {
    WaypointData d = new WaypointData();
    d.enabled = enabled;
    d.minimap = minimap;
    d.worldMarkers = worldMarkers;
    d.routeStream = routeStream;
    d.mapSize = mapSize;
    d.mapRange = mapRange;
    d.mapCorner = mapCorner;
    d.mapOffsetX = mapOffsetX;
    d.mapOffsetY = mapOffsetY;
    d.routes = routes;
    d.nearestCount = nearestCount;
    d.consumedHistory = consumedHistory;
    d.searchRadius = searchRadius;
    d.searchHeight = searchHeight;
    d.arrivalRadius = arrivalRadius;
    d.routeSet = routeSet;
    d.travel = travel;
    d.clearKey = clearKey;
    d.mapKey = mapKey;
    d.creationKey = creationKey;
    d.sets = sets;
    return d;
  }

  public enum Travel {
    WALK,
    FLY
  }

  public record Pos(int x, int y, int z) {
    public Pos add(int dx, int dy, int dz) {
      return new Pos(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }

    public double distance(Pos b) {
      return Math.sqrt(distanceSquared(b));
    }

    public double distanceSquared(Pos b) {
      double dx = (double) x - b.x, dy = (double) y - b.y, dz = (double) z - b.z;
      return dx * dx + dy * dy + dz * dz;
    }

    public String text() {
      return x + ", " + y + ", " + z;
    }
  }

  public static final class Bounds {
    public boolean enabled;
    public int x1, y1 = -64, z1, x2, y2 = 320, z2;

    public boolean contains(double x, double y, double z) {
      return !enabled
          || (x >= Math.min(x1, x2)
              && x < (double) Math.max(x1, x2) + 1
              && y >= Math.min(y1, y2)
              && y < (double) Math.max(y1, y2) + 1
              && z >= Math.min(z1, z2)
              && z < (double) Math.max(z1, z2) + 1);
    }

    public Bounds offset(int dx, int dy, int dz) {
      Bounds b = new Bounds();
      b.enabled = enabled;
      b.x1 = Math.addExact(x1, dx);
      b.x2 = Math.addExact(x2, dx);
      b.y1 = Math.addExact(y1, dy);
      b.y2 = Math.addExact(y2, dy);
      b.z1 = Math.addExact(z1, dz);
      b.z2 = Math.addExact(z2, dz);
      return b;
    }
  }

  public static final class Set {
    public String id = UUID.randomUUID().toString(), name = "New set", world = "", location = "";
    public boolean shown = true, allowFlight = true;
    public Bounds bounds = new Bounds();
    public List<Category> categories = new ArrayList<>();

    public int count() {
      return categories.stream().mapToInt(c -> c.points.size()).sum();
    }

    public boolean active(String scope, double x, double y, double z) {
      return shown && world.equals(scope) && bounds.contains(x, y, z);
    }
  }

  public static final class Category {
    public String id = UUID.randomUUID().toString(), name = "New category";
    public int color = 0x79dfbe;
    public boolean shown = true;
    public List<Point> points = new ArrayList<>();
  }

  public static final class Point {
    public String id = UUID.randomUUID().toString(), name = "";
    public int x, y, z;

    public Point() {}

    public Point(Pos p) {
      x = p.x;
      y = p.y;
      z = p.z;
    }

    public Pos pos() {
      return new Pos(x, y, z);
    }
  }

  public record Entry(Set set, Category category, Point point) {
    public int color() {
      return 0xff000000 | category.color;
    }

    public String label() {
      return point.name.isBlank() ? category.name : point.name;
    }
  }

  public List<Entry> entries(Set set, boolean includeHidden) {
    List<Entry> out = new ArrayList<>();
    for (Category c : set.categories)
      if (includeHidden || c.shown) for (Point p : c.points) out.add(new Entry(set, c, p));
    return out;
  }

  public List<Entry> visible(String world, double x, double y, double z) {
    List<Entry> out = new ArrayList<>();
    for (Set s : sets) if (s.active(world, x, y, z)) out.addAll(entries(s, false));
    return out;
  }

  public static Set copy(Set original, String name, String world, int dx, int dy, int dz) {
    Set s = new Set();
    s.name = validName(name);
    s.world = world;
    s.location = original.location;
    s.shown = original.shown;
    s.allowFlight = original.allowFlight;
    s.bounds = original.bounds.offset(dx, dy, dz);
    for (Category old : original.categories) {
      Category c = new Category();
      c.name = old.name;
      c.color = old.color;
      c.shown = old.shown;
      for (Point p : old.points) {
        Point n = new Point(p.pos().add(dx, dy, dz));
        checkPosition(n.pos());
        n.name = p.name;
        c.points.add(n);
      }
      s.categories.add(c);
    }
    return s;
  }

  public static String validName(String s) {
    if (s == null || s.isBlank() || s.strip().length() > 80)
      throw new IllegalArgumentException("Use a name with 1–80 characters");
    return s.strip();
  }

  public static void checkPosition(Pos p) {
    if (Math.abs((long) p.x) > 30_000_000
        || Math.abs((long) p.z) > 30_000_000
        || Math.abs((long) p.y) > 30_000_000)
      throw new IllegalArgumentException("Coordinates exceed world limits");
  }

  public void validate() {
    if (version != 1) throw new IllegalArgumentException("Unsupported waypoint file version");
    if (sets == null || sets.size() > 256)
      throw new IllegalArgumentException("Maximum 256 waypoint sets");
    java.util.Set<String> ids = new HashSet<>();
    int total = 0;
    for (Set s : sets) {
      if (s == null
          || s.id == null
          || !ids.add(s.id)
          || s.world == null
          || s.world.length() > 512
          || s.categories == null
          || s.categories.size() > 256
          || s.bounds == null) throw new IllegalArgumentException("Invalid set");
      s.name = validName(s.name);
      for (Category c : s.categories) {
        if (c == null || c.id == null || !ids.add(c.id) || c.points == null)
          throw new IllegalArgumentException("Invalid category");
        c.name = validName(c.name);
        c.color &= 0xffffff;
        for (Point p : c.points) {
          if (p == null || p.id == null || !ids.add(p.id) || p.name == null || p.name.length() > 80)
            throw new IllegalArgumentException("Invalid waypoint");
          checkPosition(p.pos());
          if (++total > 50000) throw new IllegalArgumentException("Maximum 50,000 waypoints");
        }
      }
    }
    mapSize = Math.clamp(mapSize, 90, 240);
    mapRange = Math.clamp(mapRange, 16, 256);
    mapCorner = Math.clamp(mapCorner, 0, 3);
    mapOffsetX = Math.clamp(mapOffsetX, 0, 1000);
    mapOffsetY = Math.clamp(mapOffsetY, 0, 1000);
    nearestCount = Math.clamp(nearestCount, 1, 20);
    consumedHistory = Math.clamp(consumedHistory, 0, 50000);
    searchRadius = Math.clamp(searchRadius, 16, 128);
    searchHeight = Math.clamp(searchHeight, 8, 64);
    if (!Double.isFinite(arrivalRadius)) arrivalRadius = 2;
    arrivalRadius = Math.clamp(arrivalRadius, .5, 10);
    if (travel == null) travel = Travel.WALK;
    if (routeSet == null) routeSet = "";
  }
}
