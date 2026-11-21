package dev.asarix.skyblockflow.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Persistent content-addressed songs; verified before a transfer is skipped. */
public final class SharedAudioCache {
  private static final long LIMIT = 512L * 1024 * 1024;
  private final Path directory;

  public SharedAudioCache(Path sounds) {
    directory = sounds.resolve(".shared/cache");
  }

  private Path path(String hash) throws IOException {
    if (hash == null || !hash.matches("[a-f0-9]{64}"))
      throw new IOException("Invalid shared audio hash");
    return directory.resolve(hash + ".mp3");
  }

  public synchronized void clear() throws IOException {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
    if (Files.isSymbolicLink(directory.getParent())) throw new IOException("Invalid saved-song folder");
    try (var files = Files.list(directory)) {
      for (Path file : files.toList()) {
        if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file);
      }
    }
  }

  public synchronized byte[] read(String hash) throws Exception {
    Path file = path(hash);
    if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(directory.getParent()))
      throw new IOException("Invalid saved-song folder");
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
    if (Files.size(file) > 32L * 1024 * 1024) {
      Files.delete(file);
      return null;
    }
    byte[] bytes = Files.readAllBytes(file);
    if (!SharedEditBundle.hash(bytes).equals(hash)) {
      Files.delete(file);
      return null;
    }
    Files.setLastModifiedTime(
        file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
    return bytes;
  }

  public synchronized Path save(String hash, byte[] audio) throws Exception {
    if (audio == null
        || audio.length > 32 * 1024 * 1024
        || !SharedEditBundle.hash(audio).equals(hash))
      throw new IOException("Shared song fingerprint mismatch");
    Path target = path(hash);
    Files.createDirectories(directory);
    if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(directory.getParent()))
      throw new IOException("Invalid saved-song folder");
    Path temporary = Files.createTempFile(directory, "audio-", ".part");
    try {
      Files.write(temporary, audio);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
    List<Path> files;
    try (var stream = Files.list(directory)) {
      files =
          new ArrayList<>(
              stream
                  .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                  .filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.mp3"))
                  .toList());
    }
    files.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
    long size = 0;
    for (Path file : files) size += Files.size(file);
    for (Path file : files)
      if (size > LIMIT && !file.equals(target)) {
        size -= Files.size(file);
        Files.deleteIfExists(file);
      }
    return target;
  }
}
