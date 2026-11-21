package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class WaypointKeyMixin {
  @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$waypointKey(long window, int action, KeyEvent event, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null
        && app.waypoints != null
        && window == Minecraft.getInstance().getWindow().handle()
        && action == GLFW.GLFW_PRESS
        && event.key() != GLFW.GLFW_KEY_F11
        && app.waypoints.key(event.key())) ci.cancel();
  }
}
