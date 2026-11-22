package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MineralMiningMixin {
  @Unique private BlockState skyblockflow$mined;

  @Inject(method = "startDestroyBlock", at = @At("RETURN"))
  private void selected(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> ci) {
    var app = SkyblockFlowClient.INSTANCE;
    var level = Minecraft.getInstance().level;
    if (ci.getReturnValueZ() && app != null && app.tools != null && level != null)
      app.tools.miningStarted(pos, level.getBlockState(pos));
  }

  @Inject(method = "destroyBlock", at = @At("HEAD"))
  private void start(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
    var mc = Minecraft.getInstance();
    skyblockflow$mined = mc.level == null ? null : mc.level.getBlockState(pos);
  }

  @Inject(method = "destroyBlock", at = @At("RETURN"))
  private void end(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (ci.getReturnValueZ() && skyblockflow$mined != null && app != null && app.tools != null)
      app.tools.attempted(pos, skyblockflow$mined);
    skyblockflow$mined = null;
  }
}
