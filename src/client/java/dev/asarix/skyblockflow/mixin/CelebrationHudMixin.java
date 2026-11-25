package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** F1 behavior for one celebration, without changing the user's saved/manual HUD choice. */
@Mixin(Hud.class)
public abstract class CelebrationHudMixin {
  private boolean skyblockflow$hideHudForFrame(boolean hidden) {
    var app = SkyblockFlowClient.INSTANCE;
    return hidden || app != null && app.celebrations != null && app.celebrations.hideMinecraftHud();
  }

  @Inject(method = "isHidden", at = @At("RETURN"), cancellable = true)
  private void skyblockflow$effectiveHudVisibility(CallbackInfoReturnable<Boolean> cir) {
    if (skyblockflow$hideHudForFrame(cir.getReturnValue())) cir.setReturnValue(true);
  }

  @Inject(method = "extractRenderState", at = @At("TAIL"))
  private void skyblockflow$celebrationAboveHiddenHud(
      GuiGraphicsExtractor graphics, DeltaTracker dt, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.celebrations != null) {
      graphics.nextStratum();
      app.celebrations.render(graphics);
      app.celebrations.renderSkipHint(graphics);
    }
  }
}
