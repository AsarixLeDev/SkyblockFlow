package dev.asarix.skyblockflow.ui;
import dev.asarix.skyblockflow.core.Numbers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
/** Formatting for text created by the mod; never touches incoming server messages. */
public final class DisplayText {
  private DisplayText() {}
  public static MutableComponent literal(String text) { return Component.literal(Numbers.text(text)); }
}
