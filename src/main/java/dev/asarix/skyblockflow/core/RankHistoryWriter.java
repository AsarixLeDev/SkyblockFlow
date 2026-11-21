package dev.asarix.skyblockflow.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Keeps only the newest pending snapshot for each history file. */
public final class RankHistoryWriter implements AutoCloseable {
  private record Write(RankHistory history, Consumer<String> error) {}
  private final Map<Path, Write> pending = new LinkedHashMap<>();
  private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(
      task -> Thread.ofPlatform().daemon().name("Flow rank history").unstarted(task));
  private boolean draining;
  private Path activePath;
  private RankHistory activeSnapshot;

  public synchronized RankHistory latest(Path path) {
    var requested = pending.get(path);
    if (requested != null) return requested.history().snapshot();
    return path.equals(activePath) ? activeSnapshot.snapshot() : null;
  }

  public synchronized void save(Path path, RankHistory snapshot, Consumer<String> error) {
    pending.put(path, new Write(snapshot, error));
    if (!draining) {
      draining = true;
      worker.execute(this::drain);
    }
  }

  private void drain() {
    for (;;) {
      Path path;
      Write write;
      synchronized (this) {
        var entries = pending.entrySet().iterator();
        if (!entries.hasNext()) {
          draining = false;
          return;
        }
        var entry = entries.next();
        path = entry.getKey();
        write = entry.getValue();
        entries.remove();
        activePath = path;
        activeSnapshot = write.history();
      }
      try {
        write.history().save(path);
      } catch (IOException e) {
        write.error().accept("Could not save rank history: " + e.getMessage());
      } finally {
        synchronized (this) {
          activePath = null;
          activeSnapshot = null;
        }
      }
    }
  }

  @Override
  public void close() {
    worker.shutdown();
    try {
      worker.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
