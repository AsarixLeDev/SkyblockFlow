package dev.asarix.skyblockflow.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Separate from settings/profiles: XP must never be shared with another account or server. */
public final class PetStore {
  private final Path file;

  public PetStore(Path directory, String server, String account) {
    try {
      String key =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest((server + "\n" + account).getBytes(StandardCharsets.UTF_8)));
      file = directory.resolve("pets").resolve(key + ".json");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public PetTracker.Snapshot load() throws IOException {
    if (!Files.exists(file)) return new PetTracker.Snapshot();
    try {
      var d = ConfigStore.GSON.fromJson(Files.readString(file), PetTracker.Snapshot.class);
      if (d == null
          || d.version != 1
          || d.state == null
          || d.type == null
          || d.name == null
          || d.rarity == null
          || d.level < -1
          || d.level > 100
          || !Double.isFinite(d.totalXp)
          || d.totalXp < -1
          || d.totalXp > 1e12
          || d.foodSeconds < -1
          || d.foodSeconds > 14400
          || d.observedAt < 0
          || d.foodObservedAt < 0
          || d.state == PetTracker.State.EQUIPPED && !PetCatalog.NAMES.containsKey(d.type))
        throw new IllegalArgumentException();
      if (d.iconItemJson == null || d.iconItemJson.length() > PetTracker.MAX_ICON_LENGTH)
        d.iconItemJson = "";
      if (d.jobBoostPercent == null) d.jobBoostPercent = new java.util.LinkedHashMap<>();
      d.jobBoostPercent.entrySet().removeIf(e -> !JobXp.NAMES.containsKey(e.getKey()) || e.getValue() == null || !Double.isFinite(e.getValue()) || e.getValue() < 0 || e.getValue() > 9900);
      return d;
    } catch (RuntimeException e) {
      Files.copy(
          file, file.resolveSibling(file.getFileName() + ".invalid-" + System.currentTimeMillis()));
      throw new IOException("Invalid pet cache backed up; open /pets to refresh", e);
    }
  }

  public void save(PetTracker.Snapshot data) throws IOException {
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
