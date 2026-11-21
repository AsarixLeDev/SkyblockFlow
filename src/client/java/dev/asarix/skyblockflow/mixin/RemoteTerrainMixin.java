package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skip live terrain only for snapshot frames, before Sodium's renderGroup hook. */
@Mixin(value = ChunkSectionsToRender.class, priority = 2000)
public abstract class RemoteTerrainMixin {
  @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$remoteTerrain(CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.celebrations != null && app.celebrations.remoteScene() != null)
      ci.cancel();
  }
}
