package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CelebrationSettings;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Named snapshots of one edit. Audio and reviewed timelines remain in their existing library. */
public final class EditPresetStore {
  public record Preset(String id, String name, CelebrationSettings settings) {}

  private static final class Library {
    int version = 1;
    List<Preset> presets = new ArrayList<>();
  }

  private final Path file;

  public EditPresetStore(Path directory) {
    file = directory.resolve("edit-presets.json");
  }

  public List<Preset> list() throws IOException {
    if (!Files.exists(file)) return new ArrayList<>();
    try {
      if (Files.size(file) > 4 * 1024 * 1024) throw new IOException("Preset library is too large");
      var library = ConfigStore.GSON.fromJson(Files.readString(file), Library.class);
      if (library == null
          || library.version != 1
          || library.presets == null
          || library.presets.size() > 100) throw new IOException("Invalid preset library");
      var ids = new HashSet<String>();
      var names = new HashSet<String>();
      for (var p : library.presets) {
        if (p == null
            || p.id == null
            || !ids.add(p.id)
            || !names.add(name(p.name).toLowerCase(Locale.ROOT)))
          throw new IOException("Invalid or duplicate preset");
        UUID.fromString(p.id);
        ConfigStore.validateCelebration(p.settings);
      }
      return new ArrayList<>(library.presets);
    } catch (RuntimeException e) {
      throw new IOException("Cannot read edit presets; the original file was kept", e);
    }
  }

  public Preset get(String id) throws IOException {
    return list().stream()
        .filter(p -> p.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IOException("That preset no longer exists"));
  }

  /** Return an editable copy that remembers which library entry Save must update. */
  public static CelebrationSettings edit(Preset preset) {
    var copy = ConfigStore.copy(preset.settings, CelebrationSettings.class);
    copy.presetId = preset.id;
    return copy;
  }

  public Preset update(String id, CelebrationSettings settings) throws IOException {
    return save(id, get(id).name, settings);
  }

  public static boolean sameSettings(CelebrationSettings a, CelebrationSettings b) {
    var left = ConfigStore.GSON.toJsonTree(a).getAsJsonObject();
    var right = ConfigStore.GSON.toJsonTree(b).getAsJsonObject();
    left.remove("presetId");
    right.remove("presetId");
    return left.equals(right);
  }

  /** Older editors saved unlinked copies. Reconnect only one exact match, never guess. */
  public Optional<Preset> matching(CelebrationSettings settings) throws IOException {
    var matches = list().stream().filter(p -> sameSettings(p.settings, settings)).toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private static String name(String name) {
    if (name == null
        || name.isBlank()
        || name.strip().length() > 80
        || name.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Use a preset name of 1–80 characters");
    return name.strip();
  }

  public Preset save(String id, String name, CelebrationSettings settings) throws IOException {
    name = name(name);
    var copy = ConfigStore.copy(settings, CelebrationSettings.class);
    copy.presetId = "";
    ConfigStore.validateCelebration(copy);
    var rows = list();
    int index = -1;
    for (int i = 0; i < rows.size(); i++) {
      var old = rows.get(i);
      if (old.id.equals(id)) index = i;
      else if (old.name.equalsIgnoreCase(name))
        throw new IllegalArgumentException(
            "That name is already used; choose Update preset instead");
    }
    if (id != null && index < 0) throw new IOException("That preset no longer exists");
    if (id == null && rows.size() >= 100) throw new IOException("Maximum 100 edit presets");
    var preset = new Preset(id == null ? UUID.randomUUID().toString() : id, name, copy);
    if (index < 0) rows.add(preset);
    else rows.set(index, preset);
    write(rows);
    return preset;
  }

  public void rename(String id, String name) throws IOException {
    save(id, name, get(id).settings);
  }

  public void delete(String id) throws IOException {
    var rows = list();
    if (!rows.removeIf(p -> p.id.equals(id))) throw new IOException("That preset no longer exists");
    write(rows);
  }

  private void write(List<Preset> rows) throws IOException {
    var library = new Library();
    library.presets = rows;
    Files.createDirectories(file.getParent());
    Path temp = Files.createTempFile(file.getParent(), "edit-presets-", ".tmp");
    try {
      Files.writeString(temp, ConfigStore.GSON.toJson(library));
      if (Files.exists(file))
        Files.copy(
            file,
            file.resolveSibling("edit-presets.previous.json"),
            StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
