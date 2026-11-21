package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.screens.Screen;

public final class SpawnerSettingsScreen extends FormScreen {
  private boolean warnings, ignoreSpawners;
  private boolean highlight;
  private boolean glowThroughBlocks;
  private boolean spheres;
  private boolean spheresOutOfRangeOnly;
  private final int opacity;

  public SpawnerSettingsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Spawner helper");
    var options = app.spawners.settings();
    warnings = options.warnings;
    ignoreSpawners = options.ignoreSpawnerObstructions;
    highlight = options.highlight;
    glowThroughBlocks = options.glowThroughBlocks;
    spheres = options.spheres;
    spheresOutOfRangeOnly = options.spheresOutOfRangeOnly;
    opacity = options.sphereOpacity;
  }

  @Override
  protected void buildForm() {
    page("Radius");
    toggle("Spawner highlights", highlight, 0, () -> highlight = !highlight);
    toggle(
        "Glow through blocks", glowThroughBlocks, 34, () -> glowThroughBlocks = !glowThroughBlocks);
    label("Applies to spawner highlights.", 0, 66);
    toggle("Radius vision", spheres, 86, () -> spheres = !spheres);
    toggle(
        "Only show out-of-range spheres",
        spheresOutOfRangeOnly,
        120,
        () -> spheresOutOfRangeOnly = !spheresOutOfRangeOnly);
    field("opacity", "Sphere opacity (%)", Integer.toString(opacity), 160);
    label("Green: in range  ·  Red: out of range  ·  Purple: unknown", 0, 210);
    label("Set shortcuts under Minecraft Controls → SkyblockFlow Spawners.", 0, 232);
    label("Open a spawner to read its current player range.", 0, 254);

    page("Helper");
    toggle("Warn about blocked space and hostile light", warnings, 0, () -> warnings = !warnings);
    toggle(
        "Ignore other spawners as obstructions",
        ignoreSpawners,
        36,
        () -> ignoreSpawners = !ignoreSpawners);
    label("Checks the 5 × 5 × 5 cube centered on each loaded spawner.", 0, 78);
    label("Orange = blocked space or light. Purple range = unknown range.", 0, 100);
    label("Hostile type comes from entity data sent by the server.", 0, 122);
    page("Observed data");
    var lines = app.spawners.observed();
    if (lines.isEmpty()) {
      label("Open a spawner to inspect its menu and block data.", 0, 4);
      return;
    }
    action(
        "Copy observed data",
        0,
        0,
        bodyW,
        () -> minecraft.keyboardHandler.setClipboard(String.join("\n", lines)));
    int y = 38;
    for (var line : lines) {
      for (var part : font.split(net.minecraft.network.chat.Component.literal(line), bodyW)) {
        var text = new StringBuilder();
        part.accept(
            (index, style, codePoint) -> {
              text.appendCodePoint(codePoint);
              return true;
            });
        label(text.toString(), 0, y);
        y += 16;
      }
    }
  }

  @Override
  protected void commit() {
    int alpha = (int) number("opacity", 0, 100);
    var options = app.spawners.settings();
    options.warnings = warnings;
    options.ignoreSpawnerObstructions = ignoreSpawners;
    options.highlight = highlight;
    options.glowThroughBlocks = glowThroughBlocks;
    options.spheres = spheres;
    options.spheresOutOfRangeOnly = spheresOutOfRangeOnly;
    options.sphereOpacity = alpha;
    if (!app.save()) throw new IllegalStateException(app.lastError);
    onClose();
  }
}
