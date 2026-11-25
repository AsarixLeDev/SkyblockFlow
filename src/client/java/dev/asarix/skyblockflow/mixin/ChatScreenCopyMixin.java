package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.ChatCopyAccess;
import dev.asarix.skyblockflow.ChatTabAccess;
import dev.asarix.skyblockflow.ui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenCopyMixin {
  @Unique private ChatTabAccess.Tab skyblockflow$selected = ChatTabAccess.Tab.ALL;

  @Inject(method = "extractRenderState", at = @At("TAIL"))
  private void skyblockflow$tabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
    if (!ChatTabAccess.enabled()) {
      skyblockflow$selected = ChatTabAccess.Tab.ALL;
      ((ChatTabAccess) Minecraft.getInstance().gui.hud.getChat()).skyblockflow$tab(ChatTabAccess.Tab.ALL);
      return;
    }
    int x = 6;
    for (var tab : ChatTabAccess.Tab.values()) {
      boolean selected = tab == skyblockflow$selected;
      Ui.rounded(graphics, x, 5, 48, 14, 4, selected ? 0xe01b5a50 : 0xd0202b38);
      Ui.text(graphics, tab.label, x + (48 - Ui.width(tab.label)) / 2, 8,
          selected ? 0xffd9fff1 : 0xffc0cdd8);
      x += 50;
    }
  }

  @Inject(method = "removed", at = @At("TAIL"))
  private void skyblockflow$resetTab(CallbackInfo ci) {
    ((ChatTabAccess) Minecraft.getInstance().gui.hud.getChat()).skyblockflow$tab(ChatTabAccess.Tab.ALL);
  }

  @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$copy(
      MouseButtonEvent event, boolean twice, CallbackInfoReturnable<Boolean> ci) {
    if (ChatTabAccess.enabled() && event.button() == 0 && event.y() >= 5 && event.y() < 19
        && event.x() >= 6 && event.x() < 6 + ChatTabAccess.Tab.values().length * 50 && (event.x() - 6) % 50 < 48) {
      skyblockflow$selected = ChatTabAccess.Tab.values()[(int) (event.x() - 6) / 50];
      ((ChatTabAccess) Minecraft.getInstance().gui.hud.getChat()).skyblockflow$tab(skyblockflow$selected);
      ci.setReturnValue(true);
      return;
    }
    var app = SkyblockFlowClient.INSTANCE;
    var mc = Minecraft.getInstance();
    if (app == null
        || !app.store.config.copyChatOnClick
        || event.button() != 0
        || (event.modifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) == 0) return;
    String message =
        ((ChatCopyAccess) mc.gui.hud.getChat()).skyblockflow$messageAt(event.x(), event.y());
    if (message == null) return;
    mc.keyboardHandler.setClipboard(message);
    mc.gui.hud.setOverlayMessage(Component.literal("Message copied"), false);
    ci.setReturnValue(true);
  }
}
