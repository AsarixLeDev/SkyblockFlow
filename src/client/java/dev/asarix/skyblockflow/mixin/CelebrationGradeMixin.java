package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class CelebrationGradeMixin {
  @Inject(method = "extractRenderState", at = @At("HEAD"))
  private void skyblockflow$gradeBeforeHud(GuiGraphicsExtractor graphics, DeltaTracker dt, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.celebrations != null) app.celebrations.renderEdit(graphics);
  }
}
