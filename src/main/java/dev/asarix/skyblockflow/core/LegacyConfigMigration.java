package dev.asarix.skyblockflow.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Prepare the import away from the live configuration, then install it as a directory. */
public final class LegacyConfigMigration {
  private static final String LEGACY_NAME = "excaliaflow";
  private static final String SETTINGS = "skyblockflow.json";
  private static final String DECISION = ".legacy-migration";
  private final Path target;
  private final Path legacy;

  public LegacyConfigMigration(Path target) {
    this(target, target.resolveSibling(LEGACY_NAME));
  }

  public LegacyConfigMigration(Path target, Path legacy) {
    this.target = target.toAbsolutePath().normalize();
    this.legacy = legacy.toAbsolutePath().normalize();
  }

  public static LegacyConfigMigration discover(Path target, Path defaultConfig) {
    var local = new LegacyConfigMigration(target);
    return local.hasLegacySettings() ? local
        : new LegacyConfigMigration(target, defaultConfig.resolve(LEGACY_NAME));
  }

  public Path source() {
    return legacy;
  }

  public boolean hasLegacySettings() {
    return legacy.getFileName() != null
        && legacy.getFileName().toString().equalsIgnoreCase(LEGACY_NAME)
        && Files.isDirectory(legacy, NOFOLLOW_LINKS)
        && Files.isRegularFile(legacy.resolve(LEGACY_NAME + ".json"), NOFOLLOW_LINKS);
  }

  public record Prepared(
      Path directory, Models.Config settings, BasicFileAttributes legacyDirectory,
      BasicFileAttributes originalTarget) implements AutoCloseable {
    private String receipt() {
      return "imported " + directory.getFileName();
    }

    @Override
    public void close() throws IOException {
      deleteTree(directory);
    }
  }

  public boolean shouldOffer() {
    return !Files.exists(target.resolve(DECISION), NOFOLLOW_LINKS)
        && hasLegacySettings();
  }

  public void decline() throws IOException {
    Path decision = target.resolve(DECISION);
    if (!Files.exists(decision, NOFOLLOW_LINKS))
      Files.writeString(decision, "declined", StandardOpenOption.CREATE_NEW);
  }

  public Prepared prepare() throws IOException {
    if (!hasLegacySettings())
      throw new IOException("The old configuration is no longer available.");
    Path sourcePath = legacy.toRealPath();
    Path targetPath = target.toRealPath();
    if (sourcePath.startsWith(targetPath) || targetPath.startsWith(sourcePath))
      throw new IOException("The old folder must be separate from the active SkyblockFlow folder.");
    var originalTarget = Files.readAttributes(target, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (!originalTarget.isDirectory())
      throw new IOException("The current SkyblockFlow configuration folder is unavailable.");
    var source = Files.readAttributes(legacy, BasicFileAttributes.class, NOFOLLOW_LINKS);
    Path staging = Files.createTempDirectory(target.getParent(), ".skyblockflow-import-");
    try {
      Files.walkFileTree(legacy, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
            throws IOException {
          if (!directory.equals(legacy)) Files.createDirectory(staging.resolve(legacy.relativize(directory)));
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
          if (!attributes.isRegularFile())
            throw new IOException("The old configuration contains a link or a special file.");
          Path relative = legacy.relativize(file);
          if (relative.getNameCount() == 1) {
            String name = file.getFileName().toString();
            if (name.equals(LEGACY_NAME + ".json")) relative = Path.of(SETTINGS);
            else if (name.equals(LEGACY_NAME + ".previous.json")) relative = Path.of("skyblockflow.previous.json");
          }
          Path copy = staging.resolve(relative);
          Files.copy(file, copy, NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
          if (!Files.isRegularFile(copy, NOFOLLOW_LINKS))
            throw new IOException("The old configuration changed during the copy.");
          return FileVisitResult.CONTINUE;
        }
      });
      Path file = staging.resolve(SETTINGS);
      var reader = new ConfigStore(staging);
      Models.Config imported = reader.decode(Files.readString(file));
      if (!reader.loadWarning.isEmpty()) throw new IOException(reader.loadWarning);
      var prepared = new Prepared(staging, imported, source, originalTarget);
      Files.writeString(staging.resolve(DECISION), prepared.receipt());
      return prepared;
    } catch (IOException | RuntimeException failure) {
      try {
        deleteTree(staging);
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public Path install(Prepared prepared) throws IOException {
    if (!sameDirectory(legacy, prepared.legacyDirectory()))
      throw new IOException("The old configuration folder changed during migration.");
    if (!sameDirectory(target, prepared.originalTarget()))
      throw new IOException("The SkyblockFlow configuration folder changed during migration.");
    Path backup = target.resolveSibling("skyblockflow-before-import-" + UUID.randomUUID());
    Files.move(target, backup);
    try {
      Files.move(prepared.directory(), target);
      return backup;
    } catch (IOException failure) {
      try {
        Files.move(backup, target);
      } catch (IOException restore) {
        failure.addSuppressed(restore);
        throw new IOException("Could not restore the current settings from " + backup.getFileName(), failure);
      }
      throw failure;
    }
  }

  public void deleteLegacy(Prepared prepared) throws IOException {
    Path decision = target.resolve(DECISION);
    if (!Files.isRegularFile(decision, NOFOLLOW_LINKS) || !Files.readString(decision).equals(prepared.receipt()))
      throw new IOException("The imported configuration could not be verified.");
    if (!sameDirectory(legacy, prepared.legacyDirectory()))
      throw new IOException("The old configuration folder changed during migration.");
    deleteTree(legacy);
  }

  private static boolean sameDirectory(Path directory, BasicFileAttributes original) throws IOException {
    var current = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
    return current.isDirectory()
        && Objects.equals(original.fileKey(), current.fileKey())
        && original.creationTime().equals(current.creationTime());
  }

  private static void deleteTree(Path directory) throws IOException {
    if (!Files.exists(directory, NOFOLLOW_LINKS)) return;
    Files.walkFileTree(directory, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
        if (failure != null) throw failure;
        Files.delete(path);
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
