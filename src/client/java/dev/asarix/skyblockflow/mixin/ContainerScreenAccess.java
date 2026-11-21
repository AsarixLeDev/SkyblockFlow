package dev.asarix.skyblockflow.mixin;
import java.util.Set;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {
  @Accessor("hoveredSlot") Slot skyblockflow$hoveredSlot();
  @Accessor("leftPos") int skyblockflow$leftPos();
  @Accessor("topPos") int skyblockflow$topPos();
  @Accessor("imageWidth") int skyblockflow$imageWidth();
  @Accessor("imageHeight") int skyblockflow$imageHeight();
  @Accessor("quickCraftSlots") Set<Slot> skyblockflow$quickCraftSlots();
  @Accessor("isQuickCrafting") void skyblockflow$quickCrafting(boolean value);
}
