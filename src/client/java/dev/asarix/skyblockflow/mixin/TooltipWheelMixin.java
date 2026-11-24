package dev.asarix.skyblockflow.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.asarix.skyblockflow.ui.ScrollableTooltips;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
abstract class TooltipWheelMixin {
  @WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"))
  private boolean skyblockflow$scroll(Screen screen, double x, double y, double dx, double dy, Operation<Boolean> original) {
    return ScrollableTooltips.scroll(screen, x, y, dx, dy) || original.call(screen, x, y, dx, dy);
  }
}
