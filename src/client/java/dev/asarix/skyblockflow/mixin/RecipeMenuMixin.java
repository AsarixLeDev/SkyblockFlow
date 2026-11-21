package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class RecipeMenuMixin {
  @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$captureCraftMenu(Screen incoming, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.recipes != null
        && app.recipes.captureCraftMenu(((Gui) (Object) this).screen(), incoming))
      ci.cancel();
  }
}
