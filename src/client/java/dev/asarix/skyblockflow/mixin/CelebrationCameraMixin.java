package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.BeatEnvelope;
import dev.asarix.skyblockflow.core.SongOutro;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Render-only transforms; player position, aim and outgoing movement packets are untouched. */
@Mixin(Camera.class)
public abstract class CelebrationCameraMixin {
  @Shadow private boolean detached;

  @Shadow
  protected abstract void setPosition(Vec3 position);

  @Shadow
  protected abstract void setRotation(float yaw, float pitch);

  @Shadow
  protected abstract void move(float forward, float up, float right);

  @Shadow
  private float getMaxZoom(float distance) {
    throw new AssertionError();
  }

  @Inject(method = "alignWithEntity", at = @At("RETURN"))
  private void skyblockflow$orbitPlayer(float partialTicks, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    var mc = Minecraft.getInstance();
    if (app == null
        || app.celebrations == null
        || mc.player == null
        || ((Camera) (Object) this).entity() != mc.player) return;
    var shot = app.celebrations.cameraShot();
    if (shot == null) return;
    var camera = (Camera) (Object) this;
    Vec3 vanillaPosition = camera.position();
    float vanillaYaw = camera.yRot(), vanillaPitch = camera.xRot();
    var remote = app.celebrations.remoteScene();
    if (remote != null) {
      vanillaPosition = remote.subject().getEyePosition(partialTicks);
      vanillaYaw = remote.subject().getYRot();
      vanillaPitch = remote.subject().getXRot();
    }
    var motion = app.celebrations.editFrame();
    double azimuth = shot.azimuth() + motion.yaw();
    float elevation = (float) Math.clamp(shot.elevation() + motion.pitch(), -85, 85);
    // Shake the orbit around the subject, so the look-at point remains at screen center.
    var subject = app.celebrations.subject();
    setPosition(subject.getPosition(partialTicks).add(0, subject.getBbHeight() * .5, 0));
    setRotation((float) (180 - azimuth), elevation);
    detached = true;
    // Vanilla's eight collision rays prevent the cinematic camera going through blocks.
    double returning = app.celebrations.cameraReturnProgress();
    // Move the pivot up to the eyes and shrink the orbit continuously. Collision rays still apply.
    if (returning > 0) {
      setPosition(camera.position().lerp(vanillaPosition, returning));
      setRotation(
          SongOutro.angle(camera.yRot(), vanillaYaw, returning),
          (float) (elevation + (vanillaPitch - elevation) * returning));
    }
    float distance = (float) (shot.distance() * (1 - returning));
    float maximum =
        remote == null ? getMaxZoom(distance) : remoteZoom(remote.mesh().world, camera, distance);
    move(-Math.max(0, maximum - .1f * (1 - (float) returning)), 0, 0);
  }

  private static float remoteZoom(
      dev.asarix.skyblockflow.SnapshotWorld world, Camera camera, float distance) {
    Vec3 backward = Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(-distance);
    double maximum = distance;
    for (int i = 0; i < 8; i++) {
      Vec3 start =
          camera
              .position()
              .add((i & 1) == 0 ? -.1 : .1, (i & 2) == 0 ? -.1 : .1, (i & 4) == 0 ? -.1 : .1);
      var hit =
          world.clip(
              new net.minecraft.world.level.ClipContext(
                  start,
                  start.add(backward),
                  net.minecraft.world.level.ClipContext.Block.VISUAL,
                  net.minecraft.world.level.ClipContext.Fluid.NONE,
                  net.minecraft.world.phys.shapes.CollisionContext.empty()));
      if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS)
        maximum = Math.min(maximum, hit.getLocation().distanceTo(start));
    }
    return (float) maximum;
  }

  @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
  private void skyblockflow$editZoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.celebrations != null) {
      double zoom = app.celebrations.editFrame().zoom();
      if (zoom != 1) cir.setReturnValue(BeatEnvelope.zoomFov(cir.getReturnValue(), zoom));
    }
  }

  @Inject(method = "extractRenderState", at = @At("RETURN"))
  private void skyblockflow$editShake(CameraRenderState state, float partialTicks, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app == null || app.celebrations == null) return;
    var f = app.celebrations.editFrame();
    float rad = (float) (Math.PI / 180);
    if (!app.celebrations.orbitActive())
      state.viewRotationMatrix.rotateLocalX(f.pitch() * rad).rotateLocalY(f.yaw() * rad);
    state.viewRotationMatrix.rotateLocalZ(f.roll() * rad);
  }
}
