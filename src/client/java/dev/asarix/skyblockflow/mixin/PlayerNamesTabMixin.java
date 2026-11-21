package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerNamesTabMixin {
  @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
  private void skyblockflow$name(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.playerNames != null)
      cir.setReturnValue(app.playerNames.style(info.getProfile().id(), info.getProfile().name(), cir.getReturnValue()));
  }
}
