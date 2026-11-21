package dev.asarix.skyblockflow.mixin;

import dev.asarix.skyblockflow.SpawnerDataAccess;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpawnerBlockEntity.class)
public abstract class SpawnerDataMixin implements SpawnerDataAccess {
  @Unique private Map<String, Integer> skyblockflow$data = Map.of();

  @Inject(method = "loadAdditional", at = @At("TAIL"))
  private void skyblockflow$readData(ValueInput input, CallbackInfo ci) {
    var data = new LinkedHashMap<>(skyblockflow$data);
    for (String key :
        new String[] {
          "RequiredPlayerRange",
          "SpawnRange",
          "Delay",
          "MinSpawnDelay",
          "MaxSpawnDelay",
          "SpawnCount",
          "MaxNearbyEntities"
        }) {
      input.getInt(key).ifPresent(value -> data.put(key, value));
    }
    skyblockflow$data = Map.copyOf(data);
  }

  @Override
  public Map<String, Integer> skyblockflow$spawnerData() {
    return skyblockflow$data;
  }
}
