package dev.asarix.skyblockflow.mixin;
import dev.asarix.skyblockflow.ChatCopyAccess;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.*;
@Mixin(ChatComponent.class)
public abstract class ChatCopyMixin implements ChatCopyAccess {
  @Shadow @Final private Minecraft minecraft;
  @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
  @Shadow private int chatScrollbarPos;
  @Shadow public abstract int getLinesPerPage();
  @Override public String skyblockflow$messageAt(double x, double y) {
    double scale = minecraft.options.chatScale().get();
    if (scale <= 0) return null;
    double localX = x / scale - 4;
    int width = (int) Math.ceil(ChatComponent.getWidth(minecraft.options.chatWidth().get()) / scale);
    if (localX < -4 || localX > width + 4) return null;
    int bottom = (int) Math.floor((minecraft.getWindow().getGuiScaledHeight() - 40) / scale);
    int lineHeight = (int) (9 * (minecraft.options.chatLineSpacing().get() + 1));
    double distance = bottom - y / scale;
    if (distance <= 0) return null;
    int visible = (int) Math.ceil(distance / lineHeight) - 1;
    int index = visible + chatScrollbarPos;
    if (visible < 0 || visible >= getLinesPerPage() || index < 0 || index >= trimmedMessages.size()) return null;
    return trimmedMessages.get(index).parent().content().getString();
  }
}
