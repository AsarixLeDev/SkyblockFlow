package dev.asarix.skyblockflow.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import dev.asarix.skyblockflow.core.Models.Config;

/** Default settings for new profiles and reset actions. */
public final class FactoryDefaults {
  private static final String ROOT = "/assets/skyblockflow/defaults/";
  private FactoryDefaults() {}
  public static Config load(ConfigStore store) throws IOException {
    return read(store);
  }
  /** Fresh, independent settings for Reset buttons. Does not create files. */
  public static Config settings() {
    try { return read(new ConfigStore(Path.of("."))); }
    catch (IOException e) { throw new IllegalStateException("Cannot read bundled defaults", e); }
  }
  private static Config read(ConfigStore store) throws IOException {
    try (var input = FactoryDefaults.class.getResourceAsStream(ROOT + "config.json")) {
      if (input == null) return Config.defaults();
      Config config = store.decode(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      if (!store.loadWarning.isBlank()) throw new IOException("Invalid bundled settings: " + store.loadWarning);
      return config;
    }
  }
}
