package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.FarmSacks;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public final class SackBadges {
  private SackBadges() {}
  private record Key(String model, String name, ItemLore lore) {}
  private static final Map<Key, String> FILTERS = new LinkedHashMap<>();
  private static final Map<String, String> NAMES = new HashMap<>();
  private static Object language, resources;

  public static void draw(GuiGraphicsExtractor g, ItemStack sack, int x, int y) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app == null || app.recipes == null || sack.isEmpty()) return;
    var model = sack.get(DataComponents.ITEM_MODEL);
    var lore = sack.get(DataComponents.LORE);
    if (lore == null) return;
    var key = new Key(model == null ? "" : model.toString(), sack.getHoverName().getString(), lore);
    String filter = FILTERS.get(key);
    if (filter == null) {
      filter = FarmSacks.filter(key.model, key.name, lore.lines().stream().map(Component::getString).toList());
      if (FILTERS.size() >= 256) FILTERS.clear();
      FILTERS.put(key, filter);
    }
    if (filter.isEmpty()) return;
    refreshNames(app);
    String id = NAMES.get(FarmSacks.nameKey(filter));
    if (id == null || id.isEmpty()) return;
    ItemStack icon = app.recipes.icon(id);
    if (icon.isEmpty()) return;
    g.pose().pushMatrix();
    g.pose().translate(x + 8, y);
    g.pose().translate(.5f, .5f);
    g.pose().scale(.5f, .5f);
    g.item(icon, 0, 0);
    g.pose().popMatrix();
  }

  private static void alias(String label, String id) {
    String key = FarmSacks.nameKey(label);
    if (!key.isEmpty()) NAMES.merge(key, id, (a, b) -> a.equals(b) ? a : "");
  }

  private static void refreshNames(SkyblockFlowClient app) {
    var mc = Minecraft.getInstance();
    var current = net.minecraft.locale.Language.getInstance();
    if (language == current && resources == mc.getResourceManager()) return;
    language = current; resources = mc.getResourceManager(); NAMES.clear();
    Map<String, String> french = new HashMap<>();
    mc.getResourceManager().getResource(Identifier.withDefaultNamespace("lang/fr_fr.json")).ifPresent(resource -> {
      try (var reader = resource.openAsReader()) {
        com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().entrySet()
            .forEach(e -> { if (e.getValue().isJsonPrimitive()) french.put(e.getKey(), e.getValue().getAsString()); });
      } catch (java.io.IOException | RuntimeException ignored) { }
    });
    for (var id : BuiltInRegistries.ITEM.keySet()) {
      var item = BuiltInRegistries.ITEM.getValue(id);
      if (item == Items.AIR) continue;
      alias(id.getPath(), id.toString());
      alias(item.getDefaultInstance().getHoverName().getString(), id.toString());
      String translated = french.get(item.getDescriptionId());
      if (translated != null) alias(translated, id.toString());
    }
    for (var tile : app.recipes.items("", false)) {
      if (tile.id().startsWith("minecraft:")) continue;
      alias(app.recipes.name(tile.id()), tile.id());
      alias(tile.id().substring(tile.id().lastIndexOf(':') + 1), tile.id());
    }
  }
}
