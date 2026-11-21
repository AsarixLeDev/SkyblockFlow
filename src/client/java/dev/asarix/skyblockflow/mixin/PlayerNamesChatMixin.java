package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GuiMessage.class)
public abstract class PlayerNamesChatMixin {
  @ModifyArg(method = "splitLines", at = @At(value = "INVOKE", target =
      "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"), index = 0)
  private FormattedText skyblockflow$name(FormattedText original) {
    var app = SkyblockFlowClient.INSTANCE;
    if (((GuiMessage) (Object) this).source() == GuiMessageSource.SYSTEM_CLIENT
        || !(original instanceof Component component) || app == null || app.playerNames == null) return original;
    return app.playerNames.styleChat(component);
  }
}
