package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.PlayerNamesChatRefresh;
import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChatComponent.class)
public abstract class PlayerNamesChatRefreshMixin implements PlayerNamesChatRefresh {
  @Shadow private int chatScrollbarPos;
  @Shadow private boolean newMessageSinceScroll;
  @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
  @Invoker("refreshTrimmedMessages") protected abstract void skyblockflow$refreshLines();

  @Override public void refreshCommunityNames() {
    int scroll = chatScrollbarPos;
    boolean unread = newMessageSinceScroll;
    skyblockflow$refreshLines();
    chatScrollbarPos = Math.min(scroll, Math.max(0, trimmedMessages.size() - 1));
    newMessageSinceScroll = unread;
  }
}
