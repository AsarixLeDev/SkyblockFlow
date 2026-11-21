package dev.asarix.skyblockflow.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Separate from settings/profiles: XP must never be shared with another account or server. */
public final class JobStore {
  private final Path file;

  public JobStore(Path directory, String server, String account) {
    file = scopedFile(directory, "jobs", server, account);
  }

  static Path scopedFile(Path directory, String group, String server, String account) {
    try {
      String key =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest((server + "\n" + account).getBytes(StandardCharsets.UTF_8)));
      return directory.resolve(group).resolve(key + ".json");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public JobTracker.Data load() throws IOException {
    if (!Files.exists(file)) return new JobTracker.Data();
    try {
      var d = ConfigStore.GSON.fromJson(Files.readString(file), JobTracker.Data.class);
      if (d == null || d.version != 1 || d.jobs == null || d.jobs.size() > 100)
        throw new IllegalArgumentException();
      for (var e : d.jobs.entrySet()) {
        var j = e.getValue();
        if (j == null
            || !JobXp.NAMES.containsKey(e.getKey())
            || !e.getKey().equals(j.id)
            || j.level < 0
            || j.level > 200
            || !Double.isFinite(j.xp + j.required)
            || j.xp < 0
            || j.required < 0
            || j.updated < 0
            || j.sessions < 0) throw new IllegalArgumentException();
        validate(j.active);
        validate(j.last);
      }
      return d;
    } catch (RuntimeException e) {
      Files.copy(
          file, file.resolveSibling(file.getFileName() + ".invalid-" + System.currentTimeMillis()));
      throw new IOException("Invalid job history backed up; starting a fresh history", e);
    }
  }

  private static void validate(JobTracker.Session s) {
    if (s != null) {
      if (s.gains == null) s.gains = new java.util.ArrayList<>();
      if (s.gains.size() > 7200 || s.gains.stream().anyMatch(g -> g == null || g.at < 0
          || !Double.isFinite(g.xp) || g.xp < 0)) throw new IllegalArgumentException();
    }
    if (s != null
        && (!Double.isFinite(s.xp)
            || s.xp < 0
            || s.started < 0
            || s.lastGain < s.started
            || s.ended < 0
            || s.ended > 0 && s.ended < s.started)) throw new IllegalArgumentException();
  }

  public void save(JobTracker.Data data) throws IOException {
    Files.createDirectories(file.getParent());
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(temp, ConfigStore.GSON.toJson(data));
    try {
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
