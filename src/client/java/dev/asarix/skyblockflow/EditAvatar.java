package dev.asarix.skyblockflow;

import com.mojang.authlib.GameProfile;
import dev.asarix.skyblockflow.core.EditScene;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Render-only actor: never changes the real player's position or sends movement packets. */
public final class EditAvatar extends RemotePlayer {
  private static int nextId = -2000000000;
  private final EditScene scene;
  private final Supplier<PlayerSkin> skin;

  public EditAvatar(ClientLevel level, EditScene scene, GameProfile profile) {
    super(level, profile);
    scene.validate();
    this.scene = scene;
    skin = Minecraft.getInstance().getSkinManager().createLookup(profile, true);
    setUUID(UUID.randomUUID()); // A staged copy cannot collide with a real loaded player.
    while (level.getEntity(nextId) != null) nextId++;
    setId(nextId++);
    setPos(scene.x(), scene.y(), scene.z());
    setYRot(scene.yaw());
    setXRot(scene.pitch());
    yBodyRot = yBodyRotO = yHeadRot = yHeadRotO = scene.yaw();
    setOldPosAndRot();
    setNoGravity(true);
    getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
  }

  public static boolean available(EditScene scene) {
    var mc = Minecraft.getInstance();
    if (scene == null || mc.level == null || mc.player == null) return false;
    try {
      scene.validate();
    } catch (RuntimeException e) {
      return false;
    }
    return mc.level.dimension().identifier().toString().equals(scene.dimension())
        && mc.player.distanceToSqr(scene.x(), scene.y(), scene.z()) <= 256 * 256
        && mc.level
                .getChunkSource()
                .getChunk(
                    (int) Math.floor(scene.x()) >> 4,
                    (int) Math.floor(scene.z()) >> 4,
                    ChunkStatus.FULL,
                    false)
            != null;
  }

  public EditScene scene() {
    return scene;
  }

  public void animate(double seconds) {
    yHeadRotO = yHeadRot;
    xRotO = getXRot();
    yHeadRot = scene.headYaw(seconds);
    setXRot(scene.headPitch(seconds));
  }

  @Override
  public void tick() {
    avatarState().tick(position(), Vec3.ZERO);
  }

  @Override
  public void aiStep() {}

  @Override
  public boolean isPickable() {
    return false;
  }

  @Override
  public boolean isPushable() {
    return false;
  }

  @Override
  public PlayerSkin getSkin() {
    return skin.get();
  }

  public void close() {
    ((ClientLevel) level()).removeEntity(getId(), RemovalReason.DISCARDED);
  }
}
