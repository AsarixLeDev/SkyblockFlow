package dev.asarix.skyblockflow.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Apply real F1 state to every HUD renderer, including direct-field reads in other clients. */
@Mixin(GameRenderer.class)
public abstract class CelebrationHudFrameMixin {
  @WrapMethod(method = {"extract", "render"})
  private void skyblockflow$withHiddenHud(
      DeltaTracker deltaTracker, boolean advanceGameTime, Operation<Void> original) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app == null || app.celebrations == null || !app.celebrations.hideMinecraftHud()) {
      original.call(deltaTracker, advanceGameTime);
      return;
    }
    var hud = (HudVisibilityAccess) Minecraft.getInstance().gui.hud;
    boolean manualHidden = hud.skyblockflow$rawHidden();
    hud.skyblockflow$rawHidden(true);
    try {
      original.call(deltaTracker, advanceGameTime);
    } finally {
      // Rendering must never change the user's persistent F1 choice, even if it fails.
      hud.skyblockflow$rawHidden(manualHidden);
    }
  }
}
