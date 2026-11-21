package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(KeyBindsList.class)
public abstract class KeyBindsListMixin {
  @Redirect(
      method = "<init>",
      at =
          @At(
              value = "FIELD",
              target =
                  "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;"))
  private KeyMapping[] skyblockflow$visibleControls(Options options) {
    SkyblockFlowClient app = SkyblockFlowClient.INSTANCE;
    if (app == null || app.store == null || !app.store.config.hideFeatures)
      return options.keyMappings;
    // Filter the menu's copy; the registered mappings remain active and keep their bindings.
    return Arrays.stream(options.keyMappings).filter(app::visibleKey).toArray(KeyMapping[]::new);
  }
}
