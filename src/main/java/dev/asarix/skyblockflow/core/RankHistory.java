package dev.asarix.skyblockflow.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One file per connection identity. Only explicit positions enter the notification baseline. */
public final class RankHistory {
  public record Observation(int position, long at) {}

  public final Map<String, Observation> boards = new LinkedHashMap<>();
  private final Map<String, Integer> baselines = new LinkedHashMap<>();
  public final TreeMap<Integer, IslandBoard.Entry> islands = new TreeMap<>();
  private static final int MAX_PLAYERS = 2048;
  public record Player(int rank, String name, int level, JobXp.Reading reading, long at, long xpAt) {
    public Player(int rank, String name, int level, JobXp.Reading reading, long at) {
      this(rank, name, level, reading, at, reading == null ? 0 : at);
    }
  }
  public Map<String, Map<String, Player>> players = new LinkedHashMap<>();
  public Map<String, IslandBoard.Entry> islandScores = new LinkedHashMap<>();
  public void cacheRank(String job, JobMessages.Rank rank, long at) {
    var board = players.computeIfAbsent(job, k -> new LinkedHashMap<>());
    String name = rank.name().toLowerCase(Locale.ROOT);
    board.replaceAll((key, value) -> !key.equals(name) && value.rank == rank.rank()
        ? new Player(0, value.name, value.level, value.reading, value.at, value.xpAt) : value);
    var old = board.get(name);
    boolean sameLevel = old != null && old.level == rank.level();
    board.remove(name);
    board.put(name, new Player(rank.rank(), rank.name(), rank.level(),
        sameLevel ? old.reading : null, at, sameLevel ? old.xpAt : 0));
    trim(board);
  }
  public void cacheStats(String name, JobXp.Reading reading, long at) {
    var board = players.computeIfAbsent(reading.job(), k -> new LinkedHashMap<>());
    String key = name.toLowerCase(Locale.ROOT);
    var old = board.get(key);
    board.remove(key);
    board.put(key, new Player(old == null ? 0 : old.rank, name, reading.level(), reading, at, at));
    trim(board);
  }

  public void cacheIsland(IslandBoard.Entry entry) {
    islandScores.remove(entry.leader());
    islandScores.put(entry.leader(), entry);
    trim(islandScores);
  }

  private static void trim(Map<?, ?> entries) {
    var iterator = entries.keySet().iterator();
    while (entries.size() > MAX_PLAYERS && iterator.hasNext()) {
      iterator.next();
      iterator.remove();
    }
  }

  public RankHistory snapshot() {
    var copy = new RankHistory();
    copy.boards.putAll(boards);
    copy.baselines.putAll(baselines);
    copy.islands.putAll(islands);
    players.forEach((job, entries) -> copy.players.put(job, new LinkedHashMap<>(entries)));
    copy.islandScores.putAll(islandScores);
    copy.snapshotAt = snapshotAt;
    copy.owner = owner;
    return copy;
  }
  public Player neighbor(String job, String self) {
    var board = players.getOrDefault(job, Map.of());
    var own = board.get(self.toLowerCase(Locale.ROOT));
    var observed = boards.get(job);
    int rank = own != null && own.rank > 0 ? own.rank : observed == null ? 0 : observed.position;
    int target = rank == 1 ? 2 : rank - 1;
    return target < 1 ? null : board.values().stream().filter(p -> p.rank == target && !p.name.equalsIgnoreCase(self)).max(Comparator.comparingLong(Player::at)).orElse(null);
  }
  public long snapshotAt;
  public String owner = "";

  public int baseline(String board) {
    return baselines.getOrDefault(board, 0);
  }

  public String observe(String board, int rank, long at) {
    return observe(board, rank, at, true);
  }

  public String observe(String board, int rank, long at, boolean unambiguous) {
    if (rank < 1) throw new IllegalArgumentException("Expected a positive position");
    boards.put(board, new Observation(rank, at));
    if (!unambiguous) return "";
    var prior = baselines.put(board, rank);
    if (prior == null || prior == rank) return "";
    return (rank < prior ? "Gained" : "Lost") + " rank · #" + rank + " (was #" + prior + ")";
  }

  public static Path path(Path directory, String server, String account) {
    return JobStore.scopedFile(directory, "leaderboards", server, account);
  }

  public static RankHistory load(Path file) throws IOException {
    if (!Files.exists(file)) return new RankHistory();
    try {
      var h = ConfigStore.GSON.fromJson(Files.readString(file), RankHistory.class);
      if (h == null
          || h.baselines == null
          || h.baselines.containsValue(null)
          || h.boards == null
          || h.islands == null
          || h.owner == null
          || h.boards.values().stream().anyMatch(o -> o == null || o.position < 1 || o.at < 0)
          || h.islands.entrySet().stream()
              .anyMatch(
                  e ->
                      e.getValue() == null
                          || e.getKey() != e.getValue().rank()
                          || e.getValue().leader() == null
                          || e.getValue().precision() < 1)) throw new IllegalArgumentException();
      if (h.players == null) h.players = new LinkedHashMap<>();
      h.players.entrySet().removeIf(e -> !JobXp.NAMES.containsKey(e.getKey()) || e.getValue() == null);
      for (var board : h.players.values()) board.values().removeIf(p -> p == null || p.name == null || p.rank < 0 || p.level < 0 || p.level > 200);
      if (h.islandScores == null) h.islandScores = new LinkedHashMap<>();
      for (var entry : h.islands.values()) h.islandScores.putIfAbsent(entry.leader(), entry);
      h.players.values().forEach(RankHistory::trim);
      trim(h.islandScores);
      return h;
    } catch (RuntimeException e) {
      throw new IOException("Unreadable rank history: " + file.getFileName(), e);
    }
  }

  public void save(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Path temp = Files.createTempFile(file.getParent(), "ranks-", ".tmp");
    try {
      Files.writeString(temp, ConfigStore.GSON.toJson(this));
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
