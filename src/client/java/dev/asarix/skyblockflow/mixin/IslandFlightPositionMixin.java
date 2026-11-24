package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class IslandFlightPositionMixin {
  @Inject(method="handleMovePlayer",at=@At("TAIL"))
  private void skyblockflow$islandArrival(ClientboundPlayerPositionPacket packet,CallbackInfo ci){
    var app=SkyblockFlowClient.INSTANCE;
    if(app!=null && app.islandFlight!=null)app.islandFlight.positionPacket();
  }
}
