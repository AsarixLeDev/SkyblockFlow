package dev.asarix.skyblockflow.core;

import java.io.IOException;
import java.util.*;

/** Display-only subject and the fingerprint of its frozen surroundings. */
public final class RemoteScene {
  public String type = "minecraft:player", name = "", terrainSha256 = "";
  public EditScene pose;
  public boolean baby;
  public int radius = 32;
  public Map<String, String> equipment = new LinkedHashMap<>();

  public void validate() throws IOException {
    if (pose == null
        || type == null
        || type.length() > 128
        || !type.matches("minecraft:[a-z0-9_]+")
        || name == null
        || name.isBlank()
        || name.length() > 80
        || terrainSha256 == null
        || !terrainSha256.matches("[a-f0-9]{64}")
        || radius < 8
        || radius > 48
        || equipment == null
        || equipment.size() > 8) throw new IOException("Invalid remote edit scene");
    pose.validate();
    for (var e : equipment.entrySet())
      if (!Set.of("mainhand", "offhand", "head", "chest", "legs", "feet", "body", "saddle")
              .contains(e.getKey())
          || e.getValue() == null
          || e.getValue().length() > 16384)
        throw new IOException("Invalid remote avatar equipment");
  }
}
