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
public abstract class SharedEditKeyMixin {
  @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$stopSharedEdit(long window, int action, KeyEvent event, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null
        && app.shared != null
        && action == GLFW.GLFW_PRESS
        && event.key() == GLFW.GLFW_KEY_ESCAPE
        && app.shared.stopFromEscape()) ci.cancel();
  }
}
