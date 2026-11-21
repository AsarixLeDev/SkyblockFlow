package dev.asarix.skyblockflow.mixin;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MultiPlayerGameMode.class)
public abstract class IslandPageMixin {
  @Inject(method="handleContainerInput", at=@At("HEAD"))
  private void skyblockflow$page(int container, int slot, int button, ContainerInput type, Player player, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app == null || app.closest == null || slot < 0 || slot >= player.containerMenu.slots.size() || container != player.containerMenu.containerId) return;
    var item = player.containerMenu.getSlot(slot).getItem();
    var lore = item.get(DataComponents.LORE);
    app.closest.islands.pageClicked(item.getHoverName().getString() + " " + (lore == null ? "" : lore.lines().toString()));
  }
}
