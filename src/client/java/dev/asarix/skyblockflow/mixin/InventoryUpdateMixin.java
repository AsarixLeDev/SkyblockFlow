package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class InventoryUpdateMixin {
  @Inject(method="handleOpenScreen",at=@At(value="INVOKE",
      target="Lnet/minecraft/client/gui/screens/MenuScreens;create(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/Minecraft;ILnet/minecraft/network/chat/Component;)V"),cancellable=true)
  private void captureInspection(ClientboundOpenScreenPacket packet,CallbackInfo ci){
    var app=SkyblockFlowClient.INSTANCE;
    if(app!=null && app.excaliaApi!=null && app.excaliaApi.inspections.openBackground(packet))ci.cancel();
  }

  @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
  private void slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
    changed();
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.tools != null) app.tools.energy.menuSlot(packet.getContainerId(), packet.getSlot());
  }

  @Inject(method = "handleContainerContent", at = @At("TAIL"))
  private void content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
    changed();
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.pets != null) app.pets.menuContent(packet.containerId());
    if (app != null && app.tools != null) app.tools.energy.menuContent(packet.containerId());
  }

  @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"))
  private void inventory(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
    changed();
  }

  private void changed() {
    if (SkyblockFlowClient.INSTANCE != null) SkyblockFlowClient.INSTANCE.inventoryRevision++;
  }
}
