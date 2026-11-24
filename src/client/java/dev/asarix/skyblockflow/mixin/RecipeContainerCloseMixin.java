package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
abstract class RecipeContainerCloseMixin {
  @Redirect(method = "clientSideCloseContainer", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
  private void skyblockflow$keepRecipeViewer(Gui gui, Screen next) {
    var app = SkyblockFlowClient.INSTANCE;
    if(app!=null && app.excaliaApi!=null && app.excaliaApi.inspections.keepViewer(gui.screen()))return;
    if (app == null || app.recipes == null || !app.recipes.keepCraftViewer(gui.screen()))
      gui.setScreen(next);
  }
}
