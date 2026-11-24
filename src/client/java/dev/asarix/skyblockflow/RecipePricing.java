package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.mixin.KeyMappingAccess;
import dev.asarix.skyblockflow.ui.FlowScreen;
import dev.asarix.skyblockflow.ui.CraftingPriceTooltip;
import java.math.BigDecimal;
import java.util.*;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class RecipePricing {
  private final SkyblockFlowClient app;
  private final RecipeHelper helper;
  private final SeasonThreeCatalog.Data season = SeasonThreeCatalog.load();
  private final KeyMapping details;
  private record Cached(RecipeCatalog catalog, long until, Optional<CraftingValue.Value> value) {}
  private final Map<String, Cached> cache = new HashMap<>();

  RecipePricing(SkyblockFlowClient app, RecipeHelper helper, KeyMapping.Category category) {
    this.app = app; this.helper = helper;
    details = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skyblockflow.recipe.cost_details", GLFW.GLFW_KEY_C, category));
  }

  private boolean held() {
    var key = ((KeyMappingAccess)details).skyblockflow$key();
    long window = Minecraft.getInstance().getWindow().handle();
    if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE)
      return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
    return key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
        && key.getValue() >= 0 && com.mojang.blaze3d.platform.InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key.getValue());
  }

  private BigDecimal buy(String id) {
    BigDecimal lowest = null;
    var live = app.excaliaApi == null ? List.<SeasonThreeCatalog.ShopQuote>of() : app.excaliaApi.prices(id);
    var data = season.items().get(id);
    var prices = live.isEmpty() ? data == null ? List.<SeasonThreeCatalog.ShopQuote>of() : data.prices() : live;
    for (var quote : prices) if (quote.buy() != null && quote.buy().signum() >= 0 && quote.unit().equalsIgnoreCase("Skydollars"))
      lowest = lowest == null ? quote.buy() : lowest.min(quote.buy());
    if (app.excaliaApi != null) for (var offer : app.excaliaApi.sellers(id, helper.name(id)))
      lowest = lowest == null ? offer.price() : lowest.min(offer.price());
    return lowest;
  }

  void append(String id, int count, List<Component> lines) {
    if (!app.store.config.tools.craftingValues || !app.store.config.tools.shopValues
        || lines.stream().anyMatch(l -> l.getString().startsWith("Crafting value:"))) return;
    id = helper.recipeLookup(id);
    var catalog = helper.catalog();
    if (catalog.produces(id).isEmpty()) return;
    long now = System.currentTimeMillis();
    var cached = cache.get(id);
    if (cached == null || cached.catalog != catalog || now >= cached.until) {
      if (cache.size() >= 128) cache.clear();
      cached = new Cached(catalog, now + 2000, CraftingValue.calculate(catalog, id, this::buy));
      cache.put(id, cached);
    }
    if (cached.value.isEmpty()) {
      lines.add(Component.literal("Crafting value: missing prices").withColor(FlowScreen.MUTED));
      return;
    }
    var value = cached.value.get();
    String total = "Crafting value: " + money(value.unit()) + " $ / item";
    if (count > 1) total += " · " + Numbers.integer(count) + " = " + money(value.unit().multiply(BigDecimal.valueOf(count))) + " $";
    lines.add(Component.literal(total).withColor(FlowScreen.GOLD));
    if (!held()) {
      lines.add(Component.literal("Hold " + details.getTranslatedKeyMessage().getString() + " for ingredients").withColor(FlowScreen.MUTED));
      return;
    }
    lines.add(Component.literal("Cheapest ingredients · per item").withColor(FlowScreen.ACCENT));
    for (var part : value.parts()) {
      String row = quantity(part.amount()) + " × " + helper.name(part.item()) + " · " + money(part.cost()) + " $";
      lines.add(Component.literal(row).withColor(FlowScreen.TEXT)
          .withStyle(style -> style.withInsertion(CraftingPriceTooltip.PREFIX + part.item())));
    }
  }

  private static String quantity(BigDecimal value) {
    return value.signum() > 0 && value.compareTo(new BigDecimal("0.000000000001")) < 0 ? "<0.000000000001"
        : Numbers.decimal(value, 0, 12);
  }
  private static String money(BigDecimal value) {
    return value.signum() > 0 && value.compareTo(new BigDecimal("0.000001")) < 0 ? "<0.000001"
        : Numbers.decimal(value, 0, 6);
  }
}
