package dev.asarix.skyblockflow;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.Feature;
import dev.asarix.skyblockflow.core.WaypointData.*;
import dev.asarix.skyblockflow.ui.*;
import java.util.*;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;

/** Waypoint runtime. World sampling and incremental routing only run on the client thread. */
public final class Waypoints implements AutoCloseable {
  public final SkyblockFlowClient app;
  public WaypointStore store;
  public WaypointMapCache cache;
  public final WaypointVisits visits = new WaypointVisits();
  public final WaypointRenderer renderer;
  public String status = "Create a set to begin", editingCategory = "";
  public List<Entry> visible = List.of();
  public List<WaypointPathfinder.Route> routes = List.of();
  private final Map<String, Entry> byId = new HashMap<>();
  private final Map<Long, List<Entry>> near = new HashMap<>();
  private String scope = "", activeSignature = "";
  private Object level;
  private long revision, lastBuild = -1, nextRoute;
  private WaypointPathfinder search;
  private boolean flight, placementLatch, openRequested;
  private int ticks;
  private boolean pausedForConfigReload;
  private long lastRouteNanos;
  // Each loaded chunk retains its column cursor, even when the player moves.
  private final LinkedHashMap<Long, Integer> sampleChunks = new LinkedHashMap<>();

  public Waypoints(SkyblockFlowClient app) {
    this.app = app;
    store = new WaypointStore(app.store.directory(), this::error);
    store.load();
    cache = new WaypointMapCache(app.store.directory().resolve("waypoint-maps"), this::error);
    renderer = new WaypointRenderer(this);
    ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    ClientChunkEvents.CHUNK_LOAD.register(
        (world, chunk) -> {
          if (world != Minecraft.getInstance().level || chunk == null) return;
          useLevel(world);
          sampleChunks.put(cell(chunk.getPos().x(), chunk.getPos().z()), 0);
        });
    ClientChunkEvents.CHUNK_UNLOAD.register(
        (world, chunk) -> {
          if (world == level) sampleChunks.remove(cell(chunk.getPos().x(), chunk.getPos().z()));
        });
    UseBlockCallback.EVENT.register(
        (player, world, hand, hit) -> {
          var mc = Minecraft.getInstance();
          if (player != mc.player
              || world != mc.level
              || editingCategory.isEmpty()
              || !enabled()
              || mc.gui.screen() != null
              || !creationAvailable()) return InteractionResult.PASS;
          if (hand == InteractionHand.MAIN_HAND && !placementLatch) {
            placementLatch = true;
            place(hit);
          }
          return InteractionResult.FAIL;
        });
    RecipeHelper.addBackgroundHud(
        Identifier.fromNamespaceAndPath("skyblockflow", "waypoints"), (g, dt) -> renderer.render(g));
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registry) ->
            dispatcher.register(
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("waypoints")
                    .executes(
                        c -> {
                          openRequested = true;
                          return 1;
                        })));
  }

  void pauseForConfigReload() {
    store.close();
    cache.close();
    renderer.close();
    pausedForConfigReload = true;
  }

  void reloadPreferences() {
    if (pausedForConfigReload) {
      store = new WaypointStore(app.store.directory(), this::error);
      cache = new WaypointMapCache(app.store.directory().resolve("waypoint-maps"), this::error);
      pausedForConfigReload = false;
    }
    store.load();
    lastBuild = -1;
  }

  public WaypointData data() {
    return store.data;
  }

  public boolean enabled() {
    return data().enabled && app.store.config.enabled(Feature.WAYPOINTS);
  }

  public boolean shown() {
    return enabled() && app.store.config.visible(Feature.WAYPOINTS);
  }

  public String scope() {
    return scope;
  }

  public String worldLabel() {
    var mc = Minecraft.getInstance();
    return mc.level == null
        ? "Not connected"
        : app.serverKey() + " · " + mc.level.dimension().identifier();
  }

  public static Pos pos(BlockPos p) {
    return new Pos(p.getX(), p.getY(), p.getZ());
  }

  public static BlockPos block(Pos p) {
    return new BlockPos(p.x(), p.y(), p.z());
  }

  public Pos playerBlock() {
    var p = Minecraft.getInstance().player;
    if (p == null) throw new IllegalArgumentException("Join a world first");
    return pos(p.blockPosition());
  }

  public Pos standingBlock() {
    return playerBlock().add(0, -1, 0);
  }

  private void error(String message) {
    Minecraft.getInstance().execute(() -> app.error(message));
  }

  public void changed() {
    revision++;
    search = null;
    routes = List.of();
    nextRoute = 0;
    store.save();
  }

  public void clearConsumed() {
    visits.clearConsumed();
    search = null;
    routes = List.of();
    nextRoute = 0;
    status = "Consumed checkpoints cleared";
  }

  public void edit(Category c) {
    editingCategory = editingCategory.equals(c.id) ? "" : c.id;
    placementLatch = false;
  }

  public boolean creationAvailable() {
    var mc = Minecraft.getInstance();
    if (mc.player == null) return false;
    for (var s : data().sets)
      for (var c : s.categories)
        if (c.id.equals(editingCategory))
          return c.shown && s.active(scope, mc.player.getX(), mc.player.getY(), mc.player.getZ());
    return false;
  }

  public String creationName() {
    for (var s : data().sets)
      for (var c : s.categories) if (c.id.equals(editingCategory)) return s.name + " / " + c.name;
    return "";
  }

  public boolean key(int key) {
    var mc = Minecraft.getInstance();
    if (mc.gui.screen() != null || mc.player == null || !enabled()) return false;
    if (key >= 0 && key == data().clearKey) {
      clearConsumed();
      return true;
    }
    if (key >= 0 && key == data().mapKey) {
      data().minimap = !data().minimap;
      store.save();
      return true;
    }
    if (key >= 0 && key == data().creationKey && !editingCategory.isEmpty()) {
      editingCategory = "";
      return true;
    }
    return false;
  }

  public void place(BlockHitResult hit) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;
    for (var s : data().sets)
      for (var c : s.categories)
        if (c.id.equals(editingCategory)) {
          if (!s.active(scope, mc.player.getX(), mc.player.getY(), mc.player.getZ()) || !c.shown) {
            status = "Creation paused: set/category hidden or outside its range";
            return;
          }
          Pos p = pos(hit.getBlockPos());
          boolean removed = c.points.removeIf(w -> w.pos().equals(p));
          if (!removed) {
            if (data().sets.stream().mapToInt(WaypointData.Set::count).sum() >= 50000) {
              status = "Waypoint limit reached";
              return;
            }
            c.points.add(new Point(p));
          }
          changed();
          status = (removed ? "Removed" : "Created") + " · " + c.name + " · " + p.text();
          return;
        }
    editingCategory = "";
  }

  public Entry entry(String id) {
    return byId.get(id);
  }

  public boolean consumed(Entry e) {
    return visits.consumed(e.point().id, data().consumedHistory);
  }

  public double routeMillis() {
    return lastRouteNanos / 1_000_000.0;
  }

  public boolean usingFlight() {
    return flight;
  }

  private static long cell(int x, int z) {
    return (long) x << 32 | z & 0xffffffffL;
  }

  private void rebuildVisible() {
    var mc = Minecraft.getInstance();
    StringBuilder signature = new StringBuilder(scope);
    for (var s : data().sets)
      if (s.active(scope, mc.player.getX(), mc.player.getY(), mc.player.getZ()))
        signature.append(s.id);
    String key = signature.toString();
    if (lastBuild == revision && key.equals(activeSignature)) return;
    visible = data().visible(scope, mc.player.getX(), mc.player.getY(), mc.player.getZ());
    lastBuild = revision;
    activeSignature = key;
    byId.clear();
    near.clear();
    for (Entry e : visible) {
      byId.put(e.point().id, e);
      near.computeIfAbsent(
              cell(Math.floorDiv(e.point().x, 16), Math.floorDiv(e.point().z, 16)),
              k -> new ArrayList<>())
          .add(e);
    }
    search = null;
    routes = List.of();
    nextRoute = 0;
  }

  public List<Entry> nearby(double x, double z, int radius) {
    List<Entry> out = new ArrayList<>();
    for (int cx = Math.floorDiv((int) Math.floor(x - radius), 16);
        cx <= Math.floorDiv((int) Math.floor(x + radius), 16);
        cx++)
      for (int cz = Math.floorDiv((int) Math.floor(z - radius), 16);
          cz <= Math.floorDiv((int) Math.floor(z + radius), 16);
          cz++) out.addAll(near.getOrDefault(cell(cx, cz), List.of()));
    return out;
  }

  public void tick() {
    var mc = Minecraft.getInstance();
    ticks++;
    if (openRequested) {
      openRequested = false;
      mc.gui.setScreen(new WaypointScreen(app, null));
    }
    if (!mc.options.keyUse.isDown()) placementLatch = false;
    if (mc.level == null || mc.player == null) {
      if (level != null) disconnect();
      return;
    }
    useLevel(mc.level);
    if (!enabled()) {
      visible = List.of();
      routes = List.of();
      search = null;
      lastBuild = -1;
      return;
    }
    rebuildVisible();
    sample();
    if (ticks % 200 == 0) cache.flush();
    if (!data().routes) {
      routes = List.of();
      search = null;
      status =
          editingCategory.isEmpty()
              ? visible.size() + " visible checkpoints"
              : "Right-click blocks · " + creationName();
      return;
    }
    routeTick();
  }

  private void useLevel(ClientLevel world) {
    if (level == world) return;
    disconnect();
    level = world;
    var mc = Minecraft.getInstance();
    String server =
        mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null
            ? "local:"
                + mc.getSingleplayerServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath()
                    .normalize()
            : app.serverKey();
    scope = WaypointMapCache.scope(server, world.dimension().identifier().toString());
  }

  private void sample() {
    var mc = Minecraft.getInstance();
    if (!data().minimap && data().sets.isEmpty() && !(mc.gui.screen() instanceof WaypointMapScreen))
      return;
    long end = System.nanoTime() + 900_000L;
    int budget = Math.min(2048, sampleChunks.size() * 256);
    while (!sampleChunks.isEmpty() && budget > 0 && System.nanoTime() < end) {
      var entry = sampleChunks.firstEntry();
      long key = entry.getKey();
      int cx = (int) (key >> 32), cz = (int) key, pixel = entry.getValue();
      // ClientLevel.hasChunk() always returns true. With load=true Minecraft returns an
      // empty placeholder for missing chunks, which would erase previously recorded terrain.
      var chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
      if (chunk == null) {
        sampleChunks.remove(key);
        continue;
      }
      while (pixel < 256 && budget > 0 && System.nanoTime() < end) {
        int x = cx * 16 + (pixel & 15), z = cz * 16 + (pixel >> 4);
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        BlockPos p = new BlockPos(x, y, z);
        int color = chunk.getBlockState(p).getMapColor(mc.level, p).col;
        // A busy disk loader must not make the recorder skip this column permanently.
        if (!cache.sample(scope, x, z, 0xff000000 | color, y)) {
          sampleChunks.put(key, pixel);
          return;
        }
        pixel++;
        budget--;
      }
      if (pixel == 256) {
        sampleChunks.remove(key);
        sampleChunks.put(key, 0); // Round robin: revisit loaded terrain to pick up block edits.
      } else sampleChunks.put(key, pixel);
    }
  }

  private boolean inRoute(Entry e) {
    return data().routeSet.isEmpty() || data().routeSet.equals(e.set().id);
  }

  private void routeTick() {
    var mc = Minecraft.getInstance();
    long now = System.nanoTime();
    Pos player = playerBlock();
    java.util.Set<String> close = new HashSet<>();
    boolean visited = false;
    for (Entry e :
        nearby(mc.player.getX(), mc.player.getZ(), (int) Math.ceil(data().arrivalRadius) + 2))
      if (inRoute(e)) {
        var p = e.point();
        double dx = Math.max(0, Math.max(p.x - mc.player.getX(), mc.player.getX() - (p.x + 1.0))),
            dy = Math.max(0, Math.max(p.y - mc.player.getY(), mc.player.getY() - (p.y + 1.0))),
            dz = Math.max(0, Math.max(p.z - mc.player.getZ(), mc.player.getZ() - (p.z + 1.0)));
        if (dx * dx + dy * dy + dz * dz <= data().arrivalRadius * data().arrivalRadius) {
          close.add(p.id);
          if (!visited && visits.visit(p.id, data().consumedHistory)) visited = true;
        }
      }
    visits.outside(close);
    if (visited) {
      search = null;
      routes = List.of();
      nextRoute = 0;
    }
    if (search != null && search.origin().distanceSquared(player) > 256) {
      search = null;
      routes = List.of();
    }
    if (search == null || search.done && now >= nextRoute) {
      boolean allowFlight = data().travel == Travel.FLY;
      for (var s : data().sets)
        if (s.active(scope, mc.player.getX(), mc.player.getY(), mc.player.getZ())
            && (data().routeSet.isEmpty() || data().routeSet.equals(s.id))
            && !s.allowFlight) allowFlight = false;
      flight = allowFlight;
      var terrain = terrain();
      Pos origin = player;
      if (!flight && !terrain.supported(origin)) {
        for (int down = 1; down <= data().searchHeight; down++) {
          Pos below = player.add(0, -down, 0);
          if (!terrain.open(below)) break;
          if (terrain.supported(below)) {
            origin = below;
            break;
          }
        }
      }
      List<WaypointPathfinder.Target> targets = new ArrayList<>();
      for (Entry e : nearby(origin.x(), origin.z(), data().searchRadius))
        if (inRoute(e) && !consumed(e) && !close.contains(e.point().id))
          targets.add(new WaypointPathfinder.Target(e.point().id, e.point().pos()));
      search =
          new WaypointPathfinder(
              terrain,
              origin,
              targets,
              flight,
              data().searchRadius,
              data().searchHeight,
              data().nearestCount,
              60000);
      nextRoute = now + 1_000_000_000L;
    }
    long start = System.nanoTime();
    search.advance(800, 1_250_000L);
    lastRouteNanos = System.nanoTime() - start;
    routes = search.routes();
    status =
        routes.isEmpty()
            ? (search.done
                ? "No reachable unconsumed checkpoint in range"
                : "Finding walkable paths…")
            : routes.size()
                + " reachable · "
                + String.format(java.util.Locale.ROOT, "%.1f", routes.getFirst().length())
                + " blocks to nearest · "
                + (flight ? "Flying" : "Walking");
    if (search.limited) status += " · search limit reached";
  }

  public WaypointPathfinder.Terrain terrain() {
    var world = Minecraft.getInstance().level;
    return new WaypointPathfinder.Terrain() {
      private boolean safe(BlockPos p) {
        if (world.getChunkSource().getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.FULL, false)
                == null
            || p.getY() < world.getMinY()
            || p.getY() + 1 > world.getMaxY()) return false;
        var s = world.getBlockState(p);
        return s.getCollisionShape(world, p).isEmpty()
            && s.getFluidState().isEmpty()
            && !s.is(Blocks.FIRE)
            && !s.is(Blocks.SOUL_FIRE)
            && !s.is(Blocks.SWEET_BERRY_BUSH)
            && !s.is(Blocks.POWDER_SNOW);
      }

      @Override
      public boolean open(Pos p) {
        BlockPos b = block(p);
        return safe(b) && safe(b.above());
      }

      @Override
      public boolean supported(Pos p) {
        BlockPos b = block(p).below();
        if (world.getChunkSource().getChunk(b.getX() >> 4, b.getZ() >> 4, ChunkStatus.FULL, false)
            == null) return false;
        var s = world.getBlockState(b);
        var shape = s.getCollisionShape(world, b);
        return !shape.isEmpty()
            && shape.max(Direction.Axis.Y) >= .4
            && shape.max(Direction.Axis.Y) <= 1.001
            && !s.is(Blocks.MAGMA_BLOCK)
            && !s.is(Blocks.CACTUS)
            && !s.is(Blocks.CAMPFIRE)
            && !s.is(Blocks.SOUL_CAMPFIRE);
      }
    };
  }

  public WaypointData.Set addSet(String name) {
    if (scope.isEmpty()) throw new IllegalArgumentException("Join a world before creating a set");
    if (data().sets.size() >= 256) throw new IllegalArgumentException("Maximum 256 sets");
    var s = new WaypointData.Set();
    s.name = WaypointData.validName(name);
    s.world = scope;
    s.location = worldLabel();
    data().sets.add(s);
    changed();
    return s;
  }

  public void disconnect() {
    cache.flush();
    level = null;
    scope = "";
    sampleChunks.clear();
    editingCategory = "";
    visits.clear();
    visible = List.of();
    routes = List.of();
    search = null;
    byId.clear();
    near.clear();
    lastBuild = -1;
  }

  @Override
  public void close() {
    store.close();
    cache.close();
    renderer.close();
  }
}
