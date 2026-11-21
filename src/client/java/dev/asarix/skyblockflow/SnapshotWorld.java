package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.TerrainSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Read-only terrain for meshes and camera collision; never installed in the live chunk cache. */
public final class SnapshotWorld implements BlockAndTintGetter {
  public final TerrainSnapshot data;
  private final BlockState[] states;

  public SnapshotWorld(TerrainSnapshot data) throws Exception {
    this.data = data;
    states = new BlockState[data.palette.size()];
    var blocks = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BLOCK);
    for (int i = 0; i < states.length; i++)
      states[i] = BlockStateParser.parseForBlock(blocks, data.palette.get(i), false).blockState();
  }

  public BlockState getBlockState(BlockPos p) {
    int i = data.index(p.getX(), p.getY(), p.getZ());
    return i < 0 ? Blocks.AIR.defaultBlockState() : states[data.blocks[i]];
  }

  public FluidState getFluidState(BlockPos p) {
    return getBlockState(p).getFluidState();
  }

  public BlockEntity getBlockEntity(BlockPos p) {
    return null;
  }

  public int getHeight() {
    return data.height;
  }

  public int getMinY() {
    return data.y;
  }

  public LevelLightEngine getLightEngine() {
    return LevelLightEngine.EMPTY;
  }

  public CardinalLighting cardinalLighting() {
    return CardinalLighting.DEFAULT;
  }

  public int getBrightness(LightLayer layer, BlockPos p) {
    int i = data.index(p.getX(), p.getY(), p.getZ());
    int light = i < 0 ? 240 : Byte.toUnsignedInt(data.light[i]);
    return layer == LightLayer.SKY ? light >> 4 : light & 15;
  }

  public int getRawBrightness(BlockPos p, int darken) {
    return Math.max(getBrightness(LightLayer.BLOCK, p), getBrightness(LightLayer.SKY, p) - darken);
  }

  public int getBlockTint(BlockPos p, ColorResolver resolver) {
    int dx = Math.clamp(p.getX() - data.x, 0, data.width - 1),
        dz = Math.clamp(p.getZ() - data.z, 0, data.depth - 1),
        i = dz * data.width + dx;
    return resolver == BiomeColors.WATER_COLOR_RESOLVER
        ? data.water[i]
        : resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER ? data.foliage[i] : data.grass[i];
  }
}
