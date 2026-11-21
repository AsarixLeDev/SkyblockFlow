package dev.asarix.skyblockflow.mixin;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Hud.class)
public abstract class LeaderboardHudMixin {
  @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$hideMeasurement(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.closest != null && app.closest.hideSidebar()) ci.cancel();
  }
}
