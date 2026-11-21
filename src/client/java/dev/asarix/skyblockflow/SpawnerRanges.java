package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.Models.Feature;
import dev.asarix.skyblockflow.core.SpawnerMenu;
import dev.asarix.skyblockflow.core.SpawnerSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Uses only loaded chunks and data already sent to this client. */
public final class SpawnerRanges {
  private static final int SCAN_DISTANCE = 64;
  private static final int MAX_HIGHLIGHTS = 256;
  private static final int MAX_SPHERES = 32;
  private final SkyblockFlowClient app;
  private final KeyMapping highlightKey;
  private final KeyMapping sphereKey;
  private ClientLevel level;
  private String scope = "";
  private BlockPos pending;
  private long pendingUntil;
  private AbstractContainerScreen<?> menu;
  private BlockPos menuPosition;
  private long revision;
  private int ticks;
  private List<Spawner> nearby = List.of();
  private List<String> lastObservation = List.of();
  private final SpawnerRangeRenderer renderer = new SpawnerRangeRenderer();

  record Spawner(Vec3 center, Integer range, String warning, List<Vec3> lit) {
    Spawner(Vec3 center, Integer range) {
      this(center, range, "", List.of());
    }
  }

  public SpawnerRanges(SkyblockFlowClient app) {
    this.app = app;
    var category =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skyblockflow", "spawners"));
    highlightKey =
        KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.skyblockflow.spawner_highlight", GLFW.GLFW_KEY_UNKNOWN, category));
    sphereKey =
        KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.skyblockflow.spawner_spheres", GLFW.GLFW_KEY_UNKNOWN, category));
    UseBlockCallback.EVENT.register(
        (player, world, hand, hit) -> {
          var mc = Minecraft.getInstance();
          if (player == mc.player && world == mc.level && hand == InteractionHand.MAIN_HAND) {
            useLevel(mc);
            var pos = hit.getBlockPos();
            pending = world.getBlockState(pos).is(Blocks.SPAWNER) ? pos.immutable() : null;
            pendingUntil = System.nanoTime() + 3_000_000_000L;
            if (pending != null) {
              var data = blockData(mc.level, pending);
              lastObservation = List.of("Spawner " + pending.toShortString(), blockSummary(data));
            }
          }
          return InteractionResult.PASS;
        });
    ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    RecipeHelper.addBackgroundHud(
        Identifier.fromNamespaceAndPath("skyblockflow", "spawner_warnings"),
        (g, dt) -> {
          var mc = Minecraft.getInstance();
          if (!enabled() || !settings().warnings || mc.player == null || mc.gui.hud.isHidden())
            return;
          int shown = 0;
          var labels = new java.util.ArrayList<int[]>();
          for (var spawner : nearby) {
            if (spawner.warning().isEmpty()
                || spawner.center().distanceToSqr(mc.player.position()) > 32 * 32) continue;
            var at =
                WaypointRenderer.project(
                    spawner.center().x,
                    spawner.center().y + 1.15,
                    spawner.center().z,
                    g.guiWidth(),
                    g.guiHeight());
            if (at == null) continue;
            String text = spawner.warning();
            int w = dev.asarix.skyblockflow.ui.Ui.width(text);
            int x = Math.clamp(at[0] - w / 2, 2, Math.max(2, g.guiWidth() - w - 2));
            int labelY = at[1];
            for (int attempt = 0; attempt < 12; attempt++) {
              boolean overlap = false;
              for (var box : labels)
                if (x < box[0] + box[2] + 6
                    && x + w + 6 > box[0]
                    && labelY < box[1] + 16
                    && labelY + 16 > box[1]) {
                  overlap = true;
                  break;
                }
              if (!overlap) break;
              labelY -= 18;
            }
            if (labelY < 4) continue;
            labels.add(new int[] {x, labelY, w});
            at[1] = labelY;
            g.fill(x - 3, at[1] - 3, x + w + 3, at[1] + 12, 0xc020170d);
            dev.asarix.skyblockflow.ui.Ui.text(g, text, x, at[1], 0xffffa840);
            if (++shown >= 12) break;
          }
        });
    LevelExtractionEvents.END_EXTRACTION.register(
        context -> {
          var mc = Minecraft.getInstance();
          if (mc.player == null
              || mc.level != level
              || !enabled()
              || app.celebrations.remoteScene() != null) {
            renderer.clear();
            return;
          }
          var options = settings();
          renderer.prepare(
              nearby,
              mc.player.position(),
              context.levelState().cameraRenderState.cullFrustum,
              options,
              MAX_HIGHLIGHTS,
              MAX_SPHERES);
        });
    LevelRenderEvents.COLLECT_SUBMITS.register(
        context ->
            renderer.submit(
                context.submitNodeCollector(),
                context.poseStack(),
                context.levelState().cameraRenderState.pos));
  }

  public SpawnerSettings settings() {
    return app.store.config.spawners;
  }

  private boolean enabled() {
    return app.store.config.enabled(Feature.SPAWNERS);
  }

  public List<String> observed() {
    return lastObservation;
  }

  private void useLevel(Minecraft mc) {
    if (mc.level == level) return;
    level = mc.level;
    pending = menuPosition = null;
    menu = null;
    nearby = List.of();
    renderer.clear();
    scope = "";
    ticks = 0;
    if (level != null) {
      var server = mc.getSingleplayerServer();
      String world =
          server == null
              ? app.serverKey()
              : server.getWorldPath(LevelResource.ROOT).normalize().toString();
      scope = world + "|" + level.dimension().identifier() + "|";
    }
  }

  private void tick(Minecraft mc) {
    useLevel(mc);
    if (level == null || mc.player == null) return;
    boolean toggleHighlight = false, toggleSpheres = false;
    while (highlightKey.consumeClick()) toggleHighlight = true;
    while (sphereKey.consumeClick()) toggleSpheres = true;
    if (!enabled()) {
      nearby = List.of();
      pending = menuPosition = null;
      menu = null;
      ticks = 0;
      return;
    }
    var options = settings();
    if (mc.gui.screen() == null && (toggleHighlight || toggleSpheres)) {
      if (toggleHighlight) options.highlight = !options.highlight;
      if (toggleSpheres) options.spheres = !options.spheres;
      app.save();
      mc.gui.hud.setOverlayMessage(
          dev.asarix.skyblockflow.ui.DisplayText.literal(
              toggleHighlight
                  ? "Spawner highlights: " + (options.highlight ? "on" : "off")
                  : "Spawner radius vision: " + (options.spheres ? "on" : "off")),
          false);
      ticks = 0;
    }
    readMenu(mc);
    if (!options.highlight && !options.spheres && !options.warnings) {
      nearby = List.of();
      ticks = 0;
    } else if (ticks++ % 10 == 0) discover(mc.player.position());
  }

  private void readMenu(Minecraft mc) {
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
      menu = null;
      menuPosition = null;
      if (System.nanoTime() > pendingUntil) pending = null;
      return;
    }
    if (screen != menu) {
      menu = screen;
      menuPosition = System.nanoTime() <= pendingUntil ? pending : null;
      pending = null;
      revision = -1;
    }
    if (menuPosition == null || revision == app.inventoryRevision) return;
    String title = screen.getTitle().getString();
    if (!SpawnerMenu.isSpawnerMenu(title)) return;
    revision = app.inventoryRevision;
    var lines = new ArrayList<String>();
    lines.add("Spawner " + menuPosition.toShortString() + " · " + title);
    lines.add(blockSummary(blockData(level, menuPosition)));
    Integer radius = null;
    var slots = screen.getMenu().slots;
    for (var slot : slots) {
      if (slot.container == mc.player.getInventory()) continue;
      var stack = slot.getItem();
      if (stack.isEmpty()) continue;
      String name = stack.getHoverName().getString();
      var lore = stack.get(DataComponents.LORE);
      List<String> raw =
          lore == null ? List.of() : lore.lines().stream().map(c -> c.getString()).toList();
      if (radius == null) radius = SpawnerMenu.radius(name, raw);
      if (lines.size() < 120) {
        lines.add("Slot " + slot.index + " · " + name);
        raw.stream().limit(120 - lines.size()).forEach(lines::add);
      }
    }
    if (lines.size() <= 2) return;
    lines.add(
        1, radius == null ? "Menu range unavailable" : "Menu player range: " + radius + " blocks");
    lastObservation = List.copyOf(lines);
    if (radius != null && settings().remember(cacheKey(menuPosition), radius)) {
      app.save();
      ticks = 0;
    }
  }

  private static Map<String, Integer> blockData(ClientLevel world, BlockPos pos) {
    return world.getBlockEntity(pos) instanceof SpawnerDataAccess data
        ? data.skyblockflow$spawnerData()
        : Map.of();
  }

  private static String blockSummary(Map<String, Integer> blockData) {
    return blockData.isEmpty()
        ? "No spawner fields received from the block entity"
        : "Block data: " + blockData;
  }

  private String cacheKey(BlockPos pos) {
    return scope + pos.asLong();
  }

  private Spawner inspect(Spawner source) {
    if (!settings().warnings) return source;
    var pos = BlockPos.containing(source.center());
    boolean hostile = false, known = false;
    if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity entity) {
      var display = entity.getSpawner().getOrCreateDisplayEntity(level, pos);
      if (display != null) {
        known = true;
        hostile = display.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER;
      }
    }
    int blocked = 0;
    boolean loaded = true;
    var lit = new ArrayList<Vec3>();
    for (int dx = -2; dx <= 2; dx++)
      for (int dy = -2; dy <= 2; dy++)
        for (int dz = -2; dz <= 2; dz++) {
          var at = pos.offset(dx, dy, dz);
          if (!level.hasChunkAt(at)) {
            loaded = false;
            continue;
          }
          var state = level.getBlockState(at);
          boolean center = dx == 0 && dy == 0 && dz == 0;
          if (!center
              && !(settings().ignoreSpawnerObstructions && state.is(Blocks.SPAWNER))
              && !state.getCollisionShape(level, at).isEmpty()) blocked++;
          if (hostile && level.getMaxLocalRawBrightness(at) > 0) lit.add(Vec3.atCenterOf(at));
        }
    String message = blocked > 0 ? "Clear 5×5×5 · " + blocked + " blocked" : "";
    if (!lit.isEmpty()) message += (message.isEmpty() ? "" : " · ") + "Needs darkness";
    if (!loaded) message += (message.isEmpty() ? "" : " · ") + "Area not loaded";
    if (!known && message.isEmpty()) message = "Mob type unavailable · light check pending";
    return new Spawner(source.center(), source.range(), message, List.copyOf(lit));
  }

  private void discover(Vec3 player) {
    var origin = BlockPos.containing(player);
    var found = new ArrayList<Spawner>();
    for (int z = (origin.getZ() - SCAN_DISTANCE) >> 4;
        z <= (origin.getZ() + SCAN_DISTANCE) >> 4;
        z++) {
      for (int x = (origin.getX() - SCAN_DISTANCE) >> 4;
          x <= (origin.getX() + SCAN_DISTANCE) >> 4;
          x++) {
        var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) continue;
        for (var entry : chunk.getBlockEntities().entrySet()) {
          if (!(entry.getValue() instanceof SpawnerBlockEntity spawner) || spawner.isRemoved())
            continue;
          var pos = entry.getKey();
          var center = Vec3.atCenterOf(pos);
          if (center.distanceToSqr(player) > SCAN_DISTANCE * SCAN_DISTANCE) continue;
          Integer range = settings().menuRanges.get(cacheKey(pos));
          if (range == null) {
            var raw =
                ((SpawnerDataAccess) spawner).skyblockflow$spawnerData().get("RequiredPlayerRange");
            if (raw != null && SpawnerMenu.validRadius(raw)) range = raw;
          }
          found.add(new Spawner(center, range));
        }
      }
    }
    found.sort(Comparator.comparingDouble(spawner -> spawner.center().distanceToSqr(player)));
    nearby = found.stream().limit(MAX_HIGHLIGHTS).map(this::inspect).toList();
  }
}
