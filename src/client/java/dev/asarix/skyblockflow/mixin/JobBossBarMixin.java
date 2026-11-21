package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Read incoming data before a client HUD replacement can consume it.
@Mixin(value = ClientPacketListener.class, priority = 2000)
public abstract class JobBossBarMixin {
  @Inject(method = "handleBossUpdate", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$job(ClientboundBossEventPacket packet, CallbackInfo ci) {
    if (!Minecraft.getInstance().isSameThread()) return;
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.jobs != null && !app.jobs.visibility.replaying()) {
      // Observe first, then prevent replacement HUDs from receiving hidden bars.
      packet.dispatch(app.jobs);
      if (app.jobs.visibility.consume(packet)) ci.cancel();
    }
  }

  @Inject(method = "setActionBarText", at = @At("HEAD"))
  private void skyblockflow$actionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
    if (!Minecraft.getInstance().isSameThread()) return;
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.jobs != null) app.jobs.accept(packet.text(), "Action bar");
  }
}
