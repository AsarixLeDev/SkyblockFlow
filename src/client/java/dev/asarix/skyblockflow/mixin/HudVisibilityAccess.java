package dev.asarix.skyblockflow.mixin;

import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudVisibilityAccess {
  @Accessor("isHidden")
  boolean skyblockflow$rawHidden();

  @Accessor("isHidden")
  void skyblockflow$rawHidden(boolean hidden);
}
