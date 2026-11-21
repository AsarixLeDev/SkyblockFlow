package dev.asarix.skyblockflow.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderType.class)
public interface SpawnerRenderAccess {
  @Invoker("create")
  static RenderType skyblockflow$create(String name, RenderSetup setup) {
    throw new AssertionError();
  }
}
