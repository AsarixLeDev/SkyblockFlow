package dev.asarix.skyblockflow.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** JSON is snapshotted on the client; disk writes run in order on one worker. */
public final class WaypointStore implements AutoCloseable {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private final Path file;
  private final Consumer<String> error;
  private final ExecutorService writer =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Flow waypoint save");
            t.setDaemon(true);
            return t;
          });
  public WaypointData data = new WaypointData();
  private volatile String pending;
  private boolean scheduled, readOnly;

  public WaypointStore(Path dir, Consumer<String> error) {
    this.file = dir.resolve("waypoints.json");
    this.error = error;
  }

  public void load() {
    if (!Files.exists(file)) return;
    try {
      if (Files.size(file) > 16 * 1024 * 1024)
        throw new IOException("Waypoint file exceeds 16 MiB");
      data = JSON.fromJson(Files.readString(file), WaypointData.class);
      if (data == null) throw new IOException("Empty waypoint file");
      data.validate();
    } catch (Exception e) {
      data = new WaypointData();
      readOnly = true;
      error.accept("Waypoints could not be loaded; original file preserved: " + e.getMessage());
    }
  }

  public synchronized void save() {
    if (readOnly) {
      error.accept("Waypoint file needs repair before saving; original file has been preserved");
      return;
    }
    data.validate();
    pending = JSON.toJson(data);
    if (scheduled) return;
    scheduled = true;
    writer.execute(
        () -> {
          while (true) {
            String value;
            synchronized (this) {
              value = pending;
              pending = null;
              if (value == null) {
                scheduled = false;
                return;
              }
            }
            try {
              Files.createDirectories(file.getParent());
              Path temp = file.resolveSibling("waypoints.json.tmp");
              Files.writeString(temp, value);
              if (Files.exists(file))
                Files.copy(
                    file,
                    file.resolveSibling("waypoints.json.bak"),
                    StandardCopyOption.REPLACE_EXISTING);
              atomicMove(temp, file);
            } catch (Exception e) {
              error.accept("Could not save waypoints: " + e.getMessage());
            }
          }
        });
  }

  public static void atomicMove(Path a, Path b) throws IOException {
    try {
      Files.move(a, b, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(a, b, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @Override
  public void close() {
    save();
    writer.shutdown();
    try {
      writer.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
