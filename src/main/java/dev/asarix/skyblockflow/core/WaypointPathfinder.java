package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.WaypointData.Pos;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Incremental multi-target Dijkstra: one search ranks all reached targets by real path cost.
 * Navigation is supplied by the client, on the client thread. No world is touched by a worker.
 */
public final class WaypointPathfinder {
  public interface Terrain {
    boolean open(Pos p);

    boolean supported(Pos p);
  }

  public record Target(String id, Pos block) {}

  public record Route(String id, double length, List<Pos> path) {}

  private record Node(Pos p, double cost) {}

  private final Terrain terrain;
  private final Pos origin;
  private final boolean flying;
  private final int radius, height, wanted, maxNodes;
  private final PriorityQueue<Node> queue =
      new PriorityQueue<>(Comparator.comparingDouble(Node::cost));
  private final Map<Pos, Double> distance = new HashMap<>();
  private final Map<Pos, Pos> previous = new HashMap<>();
  private final Map<Pos, List<Target>> goals = new HashMap<>();
  private final Iterator<Target> pending;
  private final Set<String> reached = new HashSet<>();
  private final List<Route> routes = new ArrayList<>();
  private final Map<Pos, Boolean> open = new HashMap<>(), support = new HashMap<>();
  public boolean done, limited;
  public int expanded;

  public WaypointPathfinder(
      Terrain terrain,
      Pos origin,
      List<Target> targets,
      boolean flying,
      int radius,
      int height,
      int wanted,
      int maxNodes) {
    this.terrain = terrain;
    this.origin = origin;
    this.flying = flying;
    this.radius = radius;
    this.height = height;
    this.wanted = wanted;
    this.maxNodes = maxNodes;
    pending = targets.iterator();
    distance.put(origin, 0.0);
    queue.add(new Node(origin, 0));
  }

  private void index(Target t) {
    Pos top = t.block.add(0, 1, 0);
    if (canOpen(top) && (flying || support.computeIfAbsent(top, terrain::supported))) {
      goals.computeIfAbsent(top, k -> new ArrayList<>()).add(t);
      return;
    }
    for (int[] d :
        new int[][] {
          {0, 1, 0},
          {0, 0, 0},
          {1, 0, 0},
          {-1, 0, 0},
          {0, 0, 1},
          {0, 0, -1},
          {1, 1, 0},
          {-1, 1, 0},
          {0, 1, 1},
          {0, 1, -1}
        }) {
      Pos p = t.block.add(d[0], d[1], d[2]);
      if (inBounds(p)) goals.computeIfAbsent(p, k -> new ArrayList<>()).add(t);
    }
  }

  public List<Route> routes() {
    return List.copyOf(routes);
  }

  public Pos origin() {
    return origin;
  }

  public void advance(int nodeBudget, long nanos) {
    advance(nodeBudget, nanos, System::nanoTime);
  }

  public void advance(int nodeBudget, long nanos, LongSupplier clock) {
    long end = clock.getAsLong() + nanos;
    for (int work = 0; !done && work < nodeBudget; work++) {
      if ((work & 7) == 0 && clock.getAsLong() >= end) return;
      if (pending.hasNext()) {
        index(pending.next());
        continue;
      }
      if (goals.isEmpty()) {
        done = true;
        break;
      }
      Node n = queue.poll();
      if (n == null) {
        done = true;
        break;
      }
      if (n.cost != distance.getOrDefault(n.p, Double.POSITIVE_INFINITY)) continue;
      if (++expanded > maxNodes) {
        done = true;
        limited = true;
        break;
      }
      for (Target t : goals.getOrDefault(n.p, List.of()))
        if (reached.add(t.id)) {
          routes.add(new Route(t.id, n.cost, path(n.p)));
          if (routes.size() >= wanted) {
            done = true;
            break;
          }
        }
      if (done) break;
      if (flying) {
        for (int[] d :
            new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
          Pos p = n.p.add(d[0], d[1], d[2]);
          if (canOpen(p)) relax(n, p, 1);
        }
      } else
        for (int dx = -1; dx <= 1; dx++)
          for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            Pos next = n.p.add(dx, 0, dz);
            int dy = 0;
            if (!stand(next)) {
              if (canOpen(n.p.add(0, 1, 0)) && stand(next.add(0, 1, 0))) {
                next = next.add(0, 1, 0);
                dy = 1;
              } else if (canOpen(next) && stand(next.add(0, -1, 0))) {
                next = next.add(0, -1, 0);
                dy = -1;
              } else continue;
            }
            if (dx != 0 && dz != 0) {
              // Do not cut corners or jump diagonally between obstructions.
              if (dy != 0 || !stand(n.p.add(dx, 0, 0)) || !stand(n.p.add(0, 0, dz))) continue;
            }
            if (dy > 0 && !canOpen(n.p.add(dx, 1, dz))) continue;
            relax(n, next, Math.sqrt(dx * dx + dz * dz + dy * dy));
          }
    }
  }

  private boolean inBounds(Pos p) {
    return Math.abs((long) p.x() - origin.x()) <= radius
        && Math.abs((long) p.z() - origin.z()) <= radius
        && Math.abs((long) p.y() - origin.y()) <= height;
  }

  private boolean canOpen(Pos p) {
    return inBounds(p) && open.computeIfAbsent(p, terrain::open);
  }

  private boolean stand(Pos p) {
    return canOpen(p) && support.computeIfAbsent(p, terrain::supported);
  }

  private void relax(Node n, Pos p, double step) {
    if (!inBounds(p)) return;
    double cost = n.cost + step;
    if (cost + 1e-8 >= distance.getOrDefault(p, Double.POSITIVE_INFINITY)) return;
    distance.put(p, cost);
    previous.put(p, n.p);
    queue.add(new Node(p, cost));
  }

  private List<Pos> path(Pos end) {
    LinkedList<Pos> out = new LinkedList<>();
    for (Pos p = end; p != null; p = previous.get(p)) out.addFirst(p);
    return List.copyOf(out);
  }
}
