package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide first-person hands and walking bob only in the extracted cinematic frame. */
@Mixin(GameRenderer.class)
public abstract class CelebrationViewMixin {
  @Inject(method = "extractOptions", at = @At("RETURN"))
  private void skyblockflow$orbitView(CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.celebrations != null && app.celebrations.orbitActive()) {
      var options = ((GameRenderer)(Object)this).gameRenderState().optionsRenderState;
      options.cameraType = CameraType.THIRD_PERSON_BACK;
      options.bobView = false;
      options.damageTiltStrength = 0;
    }
  }
}
