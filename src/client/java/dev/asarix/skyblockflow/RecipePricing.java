package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.mixin.KeyMappingAccess;
import dev.asarix.skyblockflow.ui.FlowScreen;
import dev.asarix.skyblockflow.ui.CraftingPriceTooltip;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.locale.Language;
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
  private static final int CACHE_LIMIT = 128;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
    var thread = new Thread(task, "Flow crafting prices");
    thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
  });
  private final LinkedHashMap<String, Optional<Quote>> cache = new LinkedHashMap<>(32, .75f, true);
  private RecipeCatalog catalog;
  private Language language;
  private long revision;
  private String requested, hovered;
  private long hoverSince;
  private Job active;

  private record Quote(BigDecimal unit, List<Component> details) {}

  private static final class Job {
    final String item;
    final Future<Set<String>> inputs;
    Future<Optional<Quote>> result;

    Job(String item, Future<Set<String>> inputs) {
      this.item = item;
      this.inputs = inputs;
    }
  }

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

  private BigDecimal buy(String id, String name, ExcaliaApiService.CraftingPrices snapshot) {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    BigDecimal lowest = null;
    var live = snapshot == null || snapshot.economy() == null ? List.<SeasonThreeCatalog.ShopQuote>of()
        : snapshot.economy().prices().getOrDefault(id, List.of());
    var data = season.items().get(id);
    var prices = live.isEmpty() ? data == null ? List.<SeasonThreeCatalog.ShopQuote>of() : data.prices() : live;
    for (var quote : prices) if (quote.buy() != null && quote.buy().signum() >= 0 && quote.unit().equalsIgnoreCase("Skydollars"))
      lowest = lowest == null ? quote.buy() : lowest.min(quote.buy());
    if (snapshot != null && snapshot.market() != null) {
      var offers = snapshot.market().selling().getOrDefault(id, List.of()).stream().map(offer -> {
        var stock = snapshot.stock().get(offer.identity());
        return stock == null || offer.unlimited() ? offer : offer.withStock(Math.min(stock, offer.stock()));
      }).toList();
      for (var offer : ExcaliaApiData.best(offers, name, false))
        lowest = lowest == null ? offer.price() : lowest.min(offer.price());
    }
    return lowest;
  }

  private boolean enabled() {
    return helper.enabled() && app.store.config.tools.craftingValues && app.store.config.tools.shopValues;
  }

  void reset() {
    if (active != null) {
      active.inputs.cancel(true);
      if (active.result != null) active.result.cancel(true);
    }
    active = null;
    requested = null;
    hovered = null;
    catalog = null;
    language = null;
    cache.clear();
  }

  void close() {
    reset();
    worker.shutdownNow();
  }

  void tick() {
    if (requested == null && active == null) return;
    if (!enabled()) { reset(); return; }
    helper.requestCatalog();
    var current = helper.readyCatalog();
    if (current == null) return;
    String item = requested;
    requested = null;
    var snapshot = app.excaliaApi == null ? null : app.excaliaApi.craftingPrices();
    long pricesVersion = snapshot == null ? 0 : snapshot.revision();
    var currentLanguage = Language.getInstance();
    if (catalog != current || pricesVersion != revision || language != currentLanguage) {
      reset();
      catalog = current;
      revision = pricesVersion;
      language = currentLanguage;
    }
    if (active != null && item != null && !active.item.equals(item)) {
      active.inputs.cancel(true);
      if (active.result != null) active.result.cancel(true);
      active = null;
    }
    if (active == null && item != null && !current.produces(item).isEmpty() && !cache.containsKey(item)) {
      active = new Job(item, worker.submit(() -> CraftingValue.inputs(current, item)));
    }
    var job = active;
    if (job == null || !job.inputs.isDone()) return;
    if (job.inputs.isCancelled()) { active = null; return; }
    if (job.inputs.state() == Future.State.FAILED) {
      failed(job.inputs.exceptionNow());
      return;
    }
    if (job.result == null) {
      var reachable = job.inputs.resultNow();
      var names = helper.pricingNames();
      job.result = worker.submit(() -> CraftingValue.calculate(current, job.item, reachable,
          id -> buy(id, names.apply(id), snapshot)).map(value -> {
            var rows = new ArrayList<Component>();
            for (var part : value.parts()) {
              if (Thread.currentThread().isInterrupted()) throw new CancellationException();
              String row = quantity(part.amount()) + " × " + names.apply(part.item()) + " · " + money(part.cost()) + " $";
              rows.add(Component.literal(row).withColor(FlowScreen.TEXT)
                  .withStyle(style -> style.withInsertion(CraftingPriceTooltip.PREFIX + part.item())));
            }
            return new Quote(value.unit(), List.copyOf(rows));
          }));
    }
    if (!job.result.isDone()) return;
    if (job.result.isCancelled()) { active = null; return; }
    if (job.result.state() == Future.State.FAILED) {
      failed(job.result.exceptionNow());
      return;
    }
    finish(job.result.resultNow());
  }

  private void failed(Throwable error) {
    SkyblockFlowClient.LOG.warn("Crafting price unavailable for {}", active.item, error);
    finish(Optional.empty());
  }

  private void finish(Optional<Quote> value) {
    if (cache.size() >= CACHE_LIMIT) cache.pollFirstEntry();
    cache.put(active.item, value);
    active = null;
  }

  void append(String id, int count, List<Component> lines) {
    if (!enabled() || lines.stream().anyMatch(l -> l.getString().startsWith("Crafting value:"))) return;
    id = helper.recipeLookup(id);
    long now = System.nanoTime();
    if (!id.equals(hovered)) { hovered = id; hoverSince = now; }

    var ready = helper.readyCatalog();
    if (ready != null && ready.produces(id).isEmpty()) return;
    var cached = ready != null && ready == catalog ? cache.get(id) : null;
    if (now - hoverSince >= 150_000_000L) requested = id;
    if (cached == null) {
      lines.add(Component.literal("Crafting value: calculating…").withColor(FlowScreen.MUTED));
      return;
    }
    if (cached.isEmpty()) {
      lines.add(Component.literal("Crafting value: unavailable").withColor(FlowScreen.MUTED));
      return;
    }
    var value = cached.get();
    String total = "Crafting value: " + money(value.unit()) + " $ / item";
    if (count > 1) total += " · " + Numbers.integer(count) + " = " + money(value.unit().multiply(BigDecimal.valueOf(count))) + " $";
    lines.add(Component.literal(total).withColor(FlowScreen.GOLD));
    if (!held()) {
      lines.add(Component.literal("Hold " + details.getTranslatedKeyMessage().getString() + " for ingredients").withColor(FlowScreen.MUTED));
      return;
    }
    lines.add(Component.literal("Cheapest ingredients · per item").withColor(FlowScreen.ACCENT));
    lines.addAll(value.details());
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
