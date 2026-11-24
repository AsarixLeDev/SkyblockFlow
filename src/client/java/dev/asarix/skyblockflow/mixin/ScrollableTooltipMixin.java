package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.ui.ScrollableTooltips;
import dev.asarix.skyblockflow.ui.CraftingPriceTooltip;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.*;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
abstract class ScrollableTooltipMixin {
  @Inject(method = "<init>", at = @At("TAIL"))
  private void skyblockflow$newFrame(CallbackInfo ci) { ScrollableTooltips.beginFrame(); }

  @WrapMethod(method = "tooltip")
  private void skyblockflow$tooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                    ClientTooltipPositioner positioner, Identifier style, Operation<Void> original) {
    var decorated = CraftingPriceTooltip.decorate(lines);
    if (!ScrollableTooltips.render((GuiGraphicsExtractor)(Object)this, font, decorated, x, y, positioner, style))
      original.call(font, decorated, x, y, positioner, style);
  }
}
