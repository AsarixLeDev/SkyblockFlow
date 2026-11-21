package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class CommandKeyMixin {
  @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$commandKey(long window, int action, KeyEvent event, CallbackInfo ci) {
    SkyblockFlowClient app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.secretKey(window, event.key(), action == GLFW.GLFW_PRESS)) {
      ci.cancel();
      return;
    }
    // Repeat events are consumed too, but only the initial press executes a command.
    if (app != null
        && action != GLFW.GLFW_RELEASE
        && app.commandKey(window, event.key(), action == GLFW.GLFW_PRESS)) ci.cancel();
  }
}
