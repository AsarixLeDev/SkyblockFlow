package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
abstract class RecipeInspectMixin {
  @Shadow protected Slot hoveredSlot;

  @Inject(method = "extractTooltip", at = @At("TAIL"))
  private void skyblockflow$inspect(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && app.recipes != null && app.recipes.inspectOutside() && hoveredSlot != null
        && hoveredSlot.hasItem() && ((AbstractContainerScreen<?>)(Object)this).getMenu().getCarried().isEmpty())
      app.recipes.inspect(graphics, hoveredSlot.getItem(), mouseX, mouseY);
  }
}
