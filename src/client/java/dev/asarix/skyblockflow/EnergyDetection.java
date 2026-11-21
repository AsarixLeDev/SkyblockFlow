package dev.asarix.skyblockflow;

import com.google.gson.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.EnergyPlan.Device;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** Only reads client-visible data; never sends NBT queries, opens menus, or moves items. */
final class EnergyDetection {
  record Found(Device type, Boolean battery, String evidence, String observation, boolean machineCandidate) {}
  private final Map<Identifier, JsonObject> blockModels = new HashMap<>();
  void invalidate() { blockModels.clear(); }
  Found block(BlockPos pos) {
    var mc = Minecraft.getInstance();
    if (mc.level == null || !mc.level.hasChunkAt(pos)) return null;
    var state = mc.level.getBlockState(pos);
    List<String> identity = new ArrayList<>(), status = new ArrayList<>();
    var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    if (!id.getNamespace().equals("minecraft")) identity.add(id.toString());
    // Custom blocks can use either variants or multipart branches in the resource pack.
    var json = blockModels.computeIfAbsent(id, key -> {
      try {
        var resource = mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath(key.getNamespace(), "blockstates/" + key.getPath() + ".json"));
        if (resource.isPresent()) try (var reader = resource.get().openAsReader()) {
          return JsonParser.parseReader(reader).getAsJsonObject();
        }
      } catch (Exception ignored) { }
      return new JsonObject();
    });
    Map<String, String> properties = new HashMap<>();
    for (var property : state.getProperties()) properties.put(property.getName(), propertyValue(state, property));
    identity.addAll(BlockModelSelection.models(json, properties));
    var be = mc.level.getBlockEntity(pos);
    if (be != null) {
      var components = be.collectComponents();
      var name = components.get(DataComponents.CUSTOM_NAME);
      if (name != null) identity.add(name.getString());
      var data = components.get(DataComponents.CUSTOM_DATA);
      if (data != null) metadata(data.copyTag(), identity, status);
      // Inventory contents are deliberately excluded: they describe stored items, not the block.
      metadata(be.saveWithoutMetadata(mc.level.registryAccess()), identity, status);
    }
    for (var entity : mc.level.getEntitiesOfClass(Display.ItemDisplay.class, new AABB(pos).inflate(.01)))
      if (entity.blockPosition().equals(pos)) item(entity.getItemStack(), identity, status);
    for (var entity : mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(.01)))
      if (entity.blockPosition().equals(pos)) item(entity.getItemBySlot(EquipmentSlot.HEAD), identity, status);
    Device found = EnergyIdentification.unique(identity);
    String observation = "Block: " + id + "\nState: " + state + "\nLabels and models: " + String.join(" | ", identity)
        + "\nStatus: " + String.join(" | ", status);
    return new Found(found, EnergyIdentification.battery(status), found == null ? "Open the machine to identify it, or choose its type." : "Detected from the block's model or data",
        EnergyPlan.bounded(observation, 2048), EnergyIdentification.machineCandidate(identity));
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String propertyValue(net.minecraft.world.level.block.state.BlockState s, net.minecraft.world.level.block.state.properties.Property p) {
    return p.getName(s.getValue(p));
  }
  private static void metadata(net.minecraft.nbt.CompoundTag data, List<String> identity, List<String> status) {
    for (String key : List.of("machine", "machine_type", "machine_id", "oraxen_id", "model", "CustomName"))
      data.getString(key).ifPresent(identity::add);
    for (String key : List.of("battery_installed", "has_battery"))
      data.getBoolean(key).ifPresent(b -> status.add("battery " + (b ? "installed" : "absent")));
  }
  static void item(ItemStack item, List<String> identity, List<String> status) {
    if (item.isEmpty()) return;
    var model = item.get(DataComponents.ITEM_MODEL);
    if (model != null && !model.getNamespace().equals("minecraft")) identity.add(model.toString());
    var name = item.getHoverName();
    identity.add(name.getString()); status.add(name.getString());
    var lore = item.get(DataComponents.LORE);
    if (lore != null) for (var line : lore.lines()) status.add(line.getString());
    var data = item.get(DataComponents.CUSTOM_DATA);
    if (data != null) metadata(data.copyTag(), identity, status);
  }
}
