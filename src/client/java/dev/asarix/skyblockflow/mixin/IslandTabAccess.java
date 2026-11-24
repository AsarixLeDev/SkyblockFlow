package dev.asarix.skyblockflow.mixin;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(PlayerTabOverlay.class)
public interface IslandTabAccess {
  @Accessor("header") Component skyblockflow$header();
  @Accessor("footer") Component skyblockflow$footer();
}
