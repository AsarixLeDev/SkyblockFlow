package dev.asarix.skyblockflow;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.asarix.skyblockflow.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.rendering.v1.level.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.*;

/** Incremental snapshot work and display-only actors. Live world state is never replaced. */
public final class RemoteScenes {
  public record Captured(RemoteScene scene, TerrainSnapshot terrain) {}

  public record Prepared(RemoteScene scene, SnapshotMesh mesh, Entity subject) {
    public int radius() {
      var d = mesh.world.data;
      return Math.min(scene.radius, (Math.min(d.width, d.depth) - 1) / 2);
    }

    public void animate(double seconds) {
      if (subject instanceof EditAvatar avatar) {
        avatar.tick();
        avatar.animate(seconds);
      } else {
        subject.xRotO = subject.getXRot();
        subject.setXRot(scene.pose.headPitch(seconds));
        if (subject instanceof LivingEntity living) {
          living.yHeadRotO = living.yHeadRot;
          living.yHeadRot = scene.pose.headYaw(seconds);
        } else {
          subject.yRotO = subject.getYRot();
          subject.setYRot(scene.pose.headYaw(seconds));
        }
      }
    }
  }

  private final SkyblockFlowClient app;
  private Job<?> pending;

  private abstract class Job<T> {
    final CompletableFuture<T> result = new CompletableFuture<>();
    final BooleanSupplier valid;

    Job(BooleanSupplier valid) {
      this.valid = valid;
    }

    abstract boolean step(long deadline) throws Exception;
  }

  public RemoteScenes(SkyblockFlowClient app) {
    this.app = app;
    LevelExtractionEvents.END_EXTRACTION.register(
        context -> {
          var active = app.celebrations.remoteScene();
          if (active == null) return;
          var state = context.levelState();
          state.entityRenderStates.clear();
          state.blockEntityRenderStates.clear();
          state.particlesRenderState.reset();
          state.weatherRenderState.reset();
          state.worldBorderRenderState.reset();
          state.blockBreakingRenderStates.clear();
          state.blockOutlineRenderState = null;
          state.cloudColor = 0;
          var subject = active.subject();
          var actor =
              Minecraft.getInstance()
                  .getEntityRenderDispatcher()
                  .getRenderer(subject)
                  .createRenderState(
                      subject, context.deltaTracker().getGameTimeDeltaPartialTick(false));
          actor.lightCoords = 15728880;
          actor.nameTag = null;
          actor.shadowPieces.clear();
          state.entityRenderStates.add(actor);
        });
    LevelRenderEvents.COLLECT_SUBMITS.register(
        context -> {
          var active = app.celebrations.remoteScene();
          if (active == null) return;
          var p = context.poseStack();
          var d = active.mesh.world.data;
          var camera = context.levelState().cameraRenderState.pos;
          p.pushPose();
          p.translate(d.x - camera.x, d.y - camera.y, d.z - camera.z);
          active.mesh.submit(context.submitNodeCollector(), p);
          p.popPose();
        });
  }

  public void tick() {
    var job = pending;
    if (job == null) return;
    try {
      if (!job.valid.getAsBoolean()) throw new CancellationException("Edit preparation stopped");
      if (job.step(System.nanoTime() + 3_000_000)) pending = null;
    } catch (Exception e) {
      pending = null;
      job.result.completeExceptionally(e);
    }
  }

  public void cancel() {
    if (pending != null) {
      pending.result.completeExceptionally(new CancellationException("Edit preparation stopped"));
      pending = null;
    }
  }

  public CompletableFuture<Captured> capture(
      Entity target, EditScene staged, int radius, BooleanSupplier valid) {
    cancel();
    var job = new Capture(target, staged, radius, valid);
    pending = job;
    return job.result;
  }

  public CompletableFuture<Prepared> prepare(
      RemoteScene scene, TerrainSnapshot terrain, GameProfile profile, BooleanSupplier valid)
      throws Exception {
    cancel();
    scene.validate();
    if (terrain.index(
            (int) Math.floor(scene.pose.x()),
            (int) Math.floor(scene.pose.y()),
            (int) Math.floor(scene.pose.z()))
        < 0) throw new IllegalArgumentException("The subject is outside the terrain snapshot");
    var job =
        new Job<Prepared>(valid) {
          SnapshotMesh mesh = new SnapshotMesh(new SnapshotWorld(terrain));
          int radius = scene.radius;
          boolean compacted;

          boolean step(long deadline) throws Exception {
            boolean done;
            try {
              done = mesh.step(deadline);
            } catch (SnapshotMesh.TooDetailedException tooDetailed) {
              // Bounded local retries only: never download or invite again to trim scenery.
              if (radius == 8 && compacted) throw tooDetailed;
              radius = Math.max(8, radius * 3 / 4);
              compacted = true;
              var smaller =
                  terrain.crop(
                      (int) Math.floor(scene.pose.x()),
                      (int) Math.floor(scene.pose.y()),
                      (int) Math.floor(scene.pose.z()),
                      radius);
              mesh = new SnapshotMesh(new SnapshotWorld(smaller));
              app.shared.transfer = EditTransfer.preparing("Adjusting terrain preview");
              return false;
            }
            app.shared.transfer =
                EditTransfer.work("Building terrain preview", mesh.completed(), mesh.total());
            if (done) result.complete(new Prepared(scene, mesh, actor(scene, profile)));
            return done;
          }
        };
    pending = job;
    return job.result;
  }

  private Entity actor(RemoteScene scene, GameProfile profile) {
    var mc = Minecraft.getInstance();
    Entity entity;
    if (scene.type.equals("minecraft:player")) {
      if (profile == null)
        throw new IllegalArgumentException("Remote player profile is unavailable");
      entity = new EditAvatar(mc.level, scene.pose, profile);
    } else {
      var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(scene.type));
      if (type == null || !BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().equals(scene.type))
        throw new IllegalArgumentException("Unknown remote entity");
      entity = type.create(mc.level, EntitySpawnReason.COMMAND);
      if (entity == null)
        throw new IllegalArgumentException("This entity cannot be displayed in a snapshot");
      entity.setUUID(UUID.randomUUID());
      entity.setId(-1999999999);
      entity.setPos(scene.pose.x(), scene.pose.y(), scene.pose.z());
      entity.setYRot(scene.pose.yaw());
      entity.setXRot(scene.pose.pitch());
      entity.setOldPosAndRot();
      entity.setNoGravity(true);
      if (entity instanceof Mob mob) mob.setNoAi(true);
      if (entity instanceof AgeableMob ageable) ageable.setBaby(scene.baby);
      if (entity instanceof LivingEntity living) {
        living.yBodyRot = living.yBodyRotO = living.yHeadRot = living.yHeadRotO = scene.pose.yaw();
      }
    }
    if (entity instanceof LivingEntity living) {
      var ops = RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess());
      for (var e : scene.equipment.entrySet())
        for (var slot : EquipmentSlot.values())
          if (slot.getName().equals(e.getKey()))
            living.setItemSlot(
                slot,
                ItemStack.CODEC.parse(ops, JsonParser.parseString(e.getValue())).getOrThrow());
    }
    return entity;
  }

  private final class Capture extends Job<Captured> {
    final ClientLevel level = Minecraft.getInstance().level;
    final RemoteScene scene = new RemoteScene();
    final int x, y, z, w, h, d;
    final int[] blocks, grass, foliage, water;
    final byte[] light;
    final Map<BlockState, Integer> palette = new LinkedHashMap<>();
    int cursor;

    Capture(Entity target, EditScene staged, int radius, BooleanSupplier valid) {
      super(valid);
      radius = Math.clamp(radius, 8, 48);
      double px = staged == null ? target.getX() : staged.x(),
          py = staged == null ? target.getY() : staged.y(),
          pz = staged == null ? target.getZ() : staged.z();
      x = (int) Math.floor(px) - radius;
      y = (int) Math.floor(py) - 32;
      z = (int) Math.floor(pz) - radius;
      w = d = radius * 2 + 1;
      h = 65;
      blocks = new int[w * h * d];
      light = new byte[blocks.length];
      grass = new int[w * d];
      foliage = new int[w * d];
      water = new int[w * d];
      palette.put(Blocks.AIR.defaultBlockState(), 0);
      scene.radius = radius;
      scene.type =
          staged != null
              ? "minecraft:player"
              : BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
      String name =
          staged != null
              ? staged.playerName()
              : target instanceof Player player ? player.getGameProfile().name() : "SnapshotActor";
      scene.name = staged != null ? staged.playerName() : target.getName().getString();
      if (scene.name.length() > 80) scene.name = scene.name.substring(0, 80);
      scene.pose =
          staged != null
              ? staged
              : new EditScene(
                  target.getUUID().toString(),
                  name,
                  level.dimension().identifier().toString(),
                  px,
                  py,
                  pz,
                  Mth.wrapDegrees(target.getYRot()),
                  Math.clamp(target.getXRot(), -60, 60),
                  12,
                  5);
      if (staged == null && target instanceof LivingEntity living) {
        scene.baby = living.isBaby();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        for (var slot : EquipmentSlot.values()) {
          var item = living.getItemBySlot(slot);
          if (item.isEmpty()) continue;
          String json = ItemStack.CODEC.encodeStart(ops, item).getOrThrow().toString();
          if (json.length() <= 16384) scene.equipment.put(slot.getName(), json);
        }
      }
    }

    boolean step(long deadline) {
      if (Minecraft.getInstance().level != level)
        throw new CancellationException("World changed while capturing terrain");
      var pos = new BlockPos.MutableBlockPos();
      while (cursor < blocks.length && System.nanoTime() < deadline) {
        int dx = cursor % w, dz = cursor / w % d, dy = cursor / (w * d);
        pos.set(x + dx, y + dy, z + dz);
        var chunk =
            level
                .getChunkSource()
                .getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        var state = chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
        blocks[cursor] = palette.computeIfAbsent(state, k -> palette.size());
        if (palette.size() > 4096)
          throw new IllegalArgumentException("Terrain has too many block types");
        light[cursor] =
            (byte)
                (chunk == null
                    ? 240
                    : level.getBrightness(LightLayer.BLOCK, pos)
                        | level.getBrightness(LightLayer.SKY, pos) << 4);
        if (dy == 0) {
          int i = dz * w + dx;
          grass[i] = level.getBlockTint(pos, BiomeColors.GRASS_COLOR_RESOLVER);
          foliage[i] = level.getBlockTint(pos, BiomeColors.FOLIAGE_COLOR_RESOLVER);
          water[i] = level.getBlockTint(pos, BiomeColors.WATER_COLOR_RESOLVER);
        }
        cursor++;
      }
      app.shared.transfer = EditTransfer.work("Capturing nearby terrain", cursor, blocks.length);
      if (cursor < blocks.length) return false;
      var states = palette.keySet().stream().map(BlockStateParser::serialize).toList();
      result.complete(
          new Captured(
              scene,
              new TerrainSnapshot(x, y, z, w, h, d, states, blocks, light, grass, foliage, water)));
      return true;
    }
  }
}
