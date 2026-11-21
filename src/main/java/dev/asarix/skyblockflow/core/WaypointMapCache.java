package dev.asarix.skyblockflow.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Bounded map tiles. Surface colors and heights survive disconnects; unknown land stays unknown.
 * Each 32×32 tile is loaded/saved on a single IO worker, never during rendering.
 */
public final class WaypointMapCache implements AutoCloseable {
  private static final int SIZE = 32, MAGIC = 0x46574d31, MAX_MEMORY = 512, MAX_DISK = 16384;

  private record Key(String world, int x, int z) {}

  private static final class Tile {
    int[] colors = new int[1024], heights = new int[1024];
    boolean writing, dirty, flushAfterLoad, loading = true;
    long retryWriteAt;
  }

  private final LinkedHashMap<Key, Tile> tiles = new LinkedHashMap<>(64, .75f, true);
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Flow map cache");
            t.setDaemon(true);
            return t;
          });
  private final Path directory;
  private final Consumer<String> error;
  private int pendingLoads, writes, pendingWrites;
  private boolean waitingForRoom;
  private static final int MAX_PENDING_WRITES = 64;
  public volatile long revision;
  private boolean warned;

  public WaypointMapCache(Path directory, Consumer<String> error) {
    this.directory = directory;
    this.error = error;
  }

  public static String scope(String server, String dimension) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((server + "\n" + dimension).getBytes(StandardCharsets.UTF_8)))
          .substring(0, 32);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Path path(Key k) {
    return directory.resolve(k.world).resolve(k.x + "_" + k.z + ".map");
  }

  private Tile tile(String world, int x, int z) {
    Key k = new Key(world, Math.floorDiv(x, SIZE), Math.floorDiv(z, SIZE));
    Tile t = tiles.get(k);
    if (t != null) return t;
    if (pendingLoads >= 48) return null;
    if (tiles.size() >= MAX_MEMORY) {
      if (waitingForRoom) return null;
      if (!evictOne()) {
        waitingForRoom = pendingWrites > 0 || pendingLoads > 0;
        return null;
      }
    }
    t = new Tile();
    tiles.put(k, t);
    pendingLoads++;
    Tile created = t;
    io.execute(
        () -> {
          int[] colors = new int[1024], heights = new int[1024];
          try {
            Path p = path(k);
            if (Files.exists(p))
              try (DataInputStream in =
                  new DataInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
                if (Files.size(p) != 8196 || in.readInt() != MAGIC)
                  throw new IOException("Invalid map tile");
                for (int i = 0; i < 1024; i++) {
                  colors[i] = in.readInt();
                  heights[i] = in.readInt();
                }
              }
          } catch (Exception e) {
            warn(e);
          }
          synchronized (this) {
            for (int i = 0; i < 1024; i++)
              if (created.colors[i] == 0) {
                created.colors[i] = colors[i];
                created.heights[i] = heights[i];
              }
            created.loading = false;
            pendingLoads--;
            waitingForRoom = false;
            revision++;
            if (created.flushAfterLoad) write(k, created);
            evict();
          }
        });
    evict();
    return t;
  }

  private void evict() {
    while (tiles.size() > MAX_MEMORY && evictOne()) { }
  }

  private boolean evictOne() {
    var entries = tiles.entrySet().iterator();
    while (entries.hasNext()) {
      var entry = entries.next();
      if (entry.getValue().loading || entry.getValue().writing || !write(entry.getKey(), entry.getValue())) continue;
      // Keep dirty tiles resident until their queued write succeeds.
      if (entry.getValue().writing) continue;
      entries.remove();
      return true;
    }
    return false;
  }

  public synchronized boolean sample(String world, int x, int z, int color, int height) {
    if (!world.matches("[a-f0-9]{32}")) return false;
    Tile t = tile(world, x, z);
    if (t == null) return false;
    int i = Math.floorMod(z, SIZE) * SIZE + Math.floorMod(x, SIZE);
    if (t.colors[i] == color && t.heights[i] == height) return true;
    t.colors[i] = color;
    t.heights[i] = height;
    t.dirty = true;
    revision++;
    return true;
  }

  /** Copies pixels under one lock; image upload is kept outside the cache. */
  public synchronized int[] image(
      String world, double cx, double cz, double blocksWide, double blocksHigh, int w, int h) {
    int[] out = new int[w * h];
    if (!world.matches("[a-f0-9]{32}")) return out;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int wx = (int) Math.floor(cx + (x + .5 - w / 2.0) * blocksWide / w),
            wz = (int) Math.floor(cz + (y + .5 - h / 2.0) * blocksHigh / h);
        Tile t = tile(world, wx, wz);
        int i = Math.floorMod(wz, SIZE) * SIZE + Math.floorMod(wx, SIZE),
            color = t == null ? 0 : t.colors[i];
        if (color == 0)
          color =
              ((Math.floorDiv(wx, 8) + Math.floorDiv(wz, 8)) & 1) == 0 ? 0xff182230 : 0xff1c2838;
        else {
          int north = i >= SIZE && t.colors[i - SIZE] != 0 ? t.heights[i - SIZE] : t.heights[i];
          double shade = t.heights[i] > north ? 1.12 : t.heights[i] < north ? .82 : 1;
          color =
              0xff000000
                  | Math.min(255, (int) (((color >> 16) & 255) * shade)) << 16
                  | Math.min(255, (int) (((color >> 8) & 255) * shade)) << 8
                  | Math.min(255, (int) ((color & 255) * shade));
        }
        out[y * w + x] = color;
      }
    return out;
  }

  public synchronized void flush() {
    for (var e : tiles.entrySet()) {
      if (e.getValue().loading) e.getValue().flushAfterLoad = true;
      else write(e.getKey(), e.getValue());
    }
  }

  private boolean write(Key key, Tile tile) {
    if (tile.writing) return false;
    if (!tile.dirty) return true;
    if (pendingWrites >= MAX_PENDING_WRITES || io.isShutdown() || System.nanoTime() < tile.retryWriteAt) return false;
    pendingWrites++;
    tile.writing = true;
    tile.dirty = false;
    int[] colors = tile.colors.clone(), heights = tile.heights.clone();
    io.execute(
        () -> {
          boolean saved = false;
          try {
            Path p = path(key);
            Files.createDirectories(p.getParent());
            Path temp = p.resolveSibling(p.getFileName() + ".tmp");
            try (DataOutputStream out =
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
              out.writeInt(MAGIC);
              for (int i = 0; i < 1024; i++) {
                out.writeInt(colors[i]);
                out.writeInt(heights[i]);
              }
            }
            WaypointStore.atomicMove(temp, p);
            saved = true;
            if (++writes % 128 == 0) prune();
          } catch (Exception e) {
            warn(e);
          } finally {
            synchronized (this) {
              tile.writing = false;
              tile.dirty |= !saved;
              tile.retryWriteAt = saved ? 0 : System.nanoTime() + 1_000_000_000L;
              pendingWrites--;
              waitingForRoom = false;
            }
          }
        });
    return true;
  }

  private void prune() throws IOException {
    if (!Files.exists(directory)) return;
    try (var files = Files.walk(directory, 2)) {
      var maps =
          files
              .filter(p -> p.toString().endsWith(".map"))
              .sorted(
                  Comparator.comparingLong(
                      p -> {
                        try {
                          return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                          return 0;
                        }
                      }))
              .toList();
      for (int i = 0; i < maps.size() - MAX_DISK; i++) Files.deleteIfExists(maps.get(i));
    }
  }

  private void warn(Exception e) {
    if (!warned) {
      warned = true;
      error.accept("Map cache: " + e.getMessage());
    }
  }

  public synchronized int residentTiles() {
    return tiles.size();
  }

  @Override
  public void close() {
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
      while (System.nanoTime() < deadline) {
        flush();
        io.submit(() -> {}).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        synchronized (this) {
          if (tiles.values().stream().noneMatch(t -> t.loading || t.writing
              || t.dirty && System.nanoTime() >= t.retryWriteAt)) break;
        }
      }
    } catch (Exception e) {
      warn(e);
    }
    io.shutdown();
    try {
      io.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
