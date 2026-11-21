package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class ProfileSharing {
  public static Path exportBundle(ConfigStore store) throws IOException {
    var directory = store.directory().resolve("exports");
    Files.createDirectories(directory);
    var path = directory.resolve("skyblockflow-" + System.currentTimeMillis() + ".zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.putNextEntry(new ZipEntry("skyblockflow.json"));
      zip.write(
          ConfigStore.GSON.toJson(store.config).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      Path images = store.directory().resolve("celebrations");
      if (Files.isDirectory(images)) try (var files = Files.list(images)) {
        for (var image : files.filter(Files::isRegularFile).filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".png")).toList()) {
          if (Files.size(image) > 8 * 1024 * 1024) continue;
          zip.putNextEntry(new ZipEntry("celebrations/" + image.getFileName())); Files.copy(image, zip); zip.closeEntry();
        }
      }
      try (var sounds = Files.list(store.sounds())) {
        for (var sound :
            sounds
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".mp3"))
                .toList()) {
          zip.putNextEntry(new ZipEntry("sounds/" + sound.getFileName()));
          Files.copy(sound, zip);
          zip.closeEntry();
        }
      }
    }
    return path;
  }

  public static void importBundle(ConfigStore store, Path path) throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    long total = 0;
    try (var zip = new ZipInputStream(Files.newInputStream(path))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        String name = entry.getName();
        if (!name.equals("skyblockflow.json") && !name.matches("sounds/[^/\\\\]+\\.mp3") && !name.matches("celebrations/[^/\\\\]+\\.png"))
          throw new IOException("Unexpected bundle entry");
        byte[] data = zip.readNBytes(32 * 1024 * 1024 + 1);
        total += data.length;
        if (data.length > 32 * 1024 * 1024 || total > 128L * 1024 * 1024 || entries.size() > 100)
          throw new IOException("Bundle exceeds size limits");
        if (entries.put(name, data) != null) throw new IOException("Duplicate bundle entry");
      }
    }
    if (!entries.containsKey("skyblockflow.json")) throw new IOException("Missing skyblockflow.json");
    store.loadWarning = "";
    Config imported =
        store.decode(
            new String(entries.get("skyblockflow.json"), java.nio.charset.StandardCharsets.UTF_8));
    if (!store.loadWarning.isBlank()) throw new IOException("Invalid bundle: " + store.loadWarning);
    for (var entry : entries.entrySet())
      if (entry.getKey().startsWith("celebrations/")) PngSafety.validate(entry.getValue());
    store.backup();
    Map<String, String> renamed = new HashMap<>();
    for (var entry : entries.entrySet())
      if (entry.getKey().startsWith("sounds/")) {
        String original = entry.getKey().substring(7), name = original;
        Path destination = store.sounds().resolve(name);
        int n = 2;
        while (Files.exists(destination)
            && !Arrays.equals(Files.readAllBytes(destination), entry.getValue())) {
          name = original.substring(0, original.length() - 4) + "-" + (n++) + ".mp3";
          destination = store.sounds().resolve(name);
        }
        if (!Files.exists(destination)) Files.write(destination, entry.getValue());
        renamed.put(original, name);
      }
    for (var entry : entries.entrySet()) if (entry.getKey().startsWith("celebrations/")) {
      if (entry.getValue().length > 8 * 1024 * 1024) throw new IOException("Celebration image exceeds size limit");
      Path folder = store.directory().resolve("celebrations"); Files.createDirectories(folder);
      String original = entry.getKey().substring("celebrations/".length()), name = original;
      Path destination = folder.resolve(name);
      for (int n = 2; Files.exists(destination) && !Arrays.equals(Files.readAllBytes(destination), entry.getValue()); n++) {
        name = n + "-" + original; destination = folder.resolve(name);
      }
      if (!Files.exists(destination)) Files.write(destination, entry.getValue());
      if (imported.jobs.celebration.image.equals(original)) imported.jobs.celebration.image = name;
    }
    for (SoundSettings sound : imported.alerts) rename(sound, renamed);
    for (SoundSettings sound : imported.coordinateAlerts) rename(sound, renamed);
    rename(imported.jobs.celebration, renamed);
    for (var timer : imported.timers) rename(timer, renamed);
    store.config = imported;
    store.save();
  }

  private static void rename(SoundSettings sound, Map<String, String> names) {
    if (sound.soundKind == SoundKind.MP3)
      sound.sound = names.getOrDefault(sound.sound, sound.sound);
  }

}
