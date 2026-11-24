package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.RecipeCatalog.*;
import dev.asarix.skyblockflow.core.RecipeSettings.Inspection;
import dev.asarix.skyblockflow.mixin.ContainerScreenAccess;
import dev.asarix.skyblockflow.ui.FlowScreen;
import dev.asarix.skyblockflow.ui.InventoryRecipeDock;
import dev.asarix.skyblockflow.ui.RecipeScreen;
import dev.asarix.skyblockflow.ui.Ui;
import java.util.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.*;
import org.lwjgl.glfw.GLFW;

public final class RecipeHelper {
  public enum Action {
    RECIPES(GLFW.GLFW_KEY_R), USES(GLFW.GLFW_KEY_U), PIN(GLFW.GLFW_KEY_P),
    MATERIALS(GLFW.GLFW_KEY_G), CRAFT(GLFW.GLFW_KEY_UNKNOWN);

    final int defaultKey;

    Action(int defaultKey) { this.defaultKey = defaultKey; }
  }

  private final SkyblockFlowClient app;
  private final Minecraft mc = Minecraft.getInstance();
  private final RecipePlacement placement = new RecipePlacement(this);
  private final RecipePricing pricing;
  private final VanillaPlacement vanillaPlacement = new VanillaPlacement(this);
  private final Map<String, Recipe> observedRecipes = new LinkedHashMap<>();
  private final SeasonThreeCatalog.Data season = SeasonThreeCatalog.load();
  private final EnumMap<Action, KeyMapping> keys = new EnumMap<>(Action.class);
  private final Map<String, ItemStack> icons = new HashMap<>();
  private final Map<String, ItemStack> catalogIcons = new HashMap<>();
  private java.lang.ref.WeakReference<Object> iconResources = new java.lang.ref.WeakReference<>(null);
  private String inspectedItem = "";
  private long inspectedSince, lastInspected;
  private final Map<String, Recipe> seasonById = new HashMap<>();
  private final Map<String, String> modelIds = new HashMap<>();
  private final Map<String, RecipeDisplayId> serverRecipes = new HashMap<>();
  private final InventoryRecipeDock.State inventoryBrowser = new InventoryRecipeDock.State();
  private RecipeCatalog indexedItems;
  private net.minecraft.locale.Language indexedLanguage;
  private List<ItemTile> itemIndex = List.of();

  public record ItemTile(String id, Recipe recipe, String search) {}
  private List<?> recipeCollections;
  private Object level;
  private boolean seasonActive;
  private RecipeCatalog catalog = new RecipeCatalog(List.of());
  private String status = "";
  private long nextChecklist;
  private Recipe previousRecipe;
  private Map<String, Long> lastInventory = Map.of();
  private int previousBatches;
  private Checklist checklist = new Checklist(List.of(), false);

  public RecipeHelper(SkyblockFlowClient app) {
    this.app = app;
    for (var recipe : season.recipes()) seasonById.putIfAbsent(recipe.id(), recipe);
    season.items().forEach((key, item) -> {
      if (!item.model().isEmpty()) modelIds.put(item.model(), key);
    });
    var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skyblockflow", "recipes"));
    pricing = new RecipePricing(app, this, category);
    for (var action : Action.values()) keys.put(action, KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key.skyblockflow.recipe." + action.name().toLowerCase(Locale.ROOT),
            action.defaultKey, category)));
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          level = null;
          recipeCollections = null;
          icons.clear();
          catalogIcons.clear();
          serverRecipes.clear();
          previousRecipe = null;
          indexedItems = null;
          itemIndex = List.of();
          catalog = new RecipeCatalog(List.of());
          placement.reset();
          vanillaPlacement.reset();
          observedRecipes.clear();
        });
    ClientTickEvents.END_CLIENT_TICK.register(client -> { placement.tick(); vanillaPlacement.tick(); });
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) ->
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("skyblockflowrecipes").executes(context -> {
          mc.schedule(() -> open(mc.gui.screen(), null, false, false));
          return 1;
        })));
    ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
      if (screen instanceof AbstractContainerScreen<?> container)
        InventoryRecipeDock.attach(this, container, inventoryBrowser);
    });
    HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("skyblockflow", "recipe_checklist"),
        (graphics, tickCounter) -> {
          if (settings().showChecklist && mc.player != null && !mc.gui.hud.isHidden()
              && mc.gui.screen() == null)
            drawChecklist(graphics, graphics.guiWidth() - Math.min(242, graphics.guiWidth() / 2) - 8,
                30, Math.min(242, graphics.guiWidth() / 2), Math.max(32, graphics.guiHeight() / 2), 0);
        });
  }

  public RecipeSettings settings() { return app.store.config.tools.recipes; }

  public static void addBackgroundHud(Identifier id, HudElement renderer) {
    HudElementRegistry.addLast(id, (graphics, tick) -> {
      var app = SkyblockFlowClient.INSTANCE;
      if (app == null || app.recipes == null || !app.recipes.hideBackgroundOverlays())
        renderer.extractRenderState(graphics, tick);
    });
  }

  public boolean hideBackgroundOverlays() {
    var screen = mc.gui.screen();
    return settings().hideBackgroundOverlays && (screen instanceof RecipeScreen
        || screen instanceof AbstractContainerScreen<?> && !settings().inventoryDockHidden);
  }

  public enum CraftButton { CRAFT, OPEN, LOADING, RETRY, APPLYING, GUIDE }

  public void prepareCraft(RecipeScreen viewer, Recipe recipe, Screen parent) {
    placement.begin(viewer, recipe, parent);
  }

  public CraftButton craftButton(RecipeScreen viewer, Recipe recipe, Screen parent) {
    return placement.button(viewer, recipe, parent);
  }

  public void pressCraft(RecipeScreen viewer, Recipe recipe, Screen parent, boolean max) {
    placement.press(viewer, recipe, parent, max);
  }

  public boolean captureCraftMenu(Screen current, Screen incoming) {
    return placement.capture(current, incoming);
  }

  public boolean keepCraftViewer(Screen current) {
    return placement.keepViewer(current);
  }

  public Screen closeCraftViewer(RecipeScreen viewer) {
    return placement.close(viewer);
  }

  public boolean inventoryCalculatorEnabled() { return app.store.config.tools.calculator; }

  public void save() { app.save(); }

  public Action action(KeyEvent event) {
    for (var entry : keys.entrySet())
      if (entry.getValue().matches(event)) return entry.getKey();
    return null;
  }

  public String keyName(Action action) { return keys.get(action).getTranslatedKeyMessage().getString(); }

  public static ItemStack hovered(AbstractContainerScreen<?> screen) {
    Slot slot = ((ContainerScreenAccess) screen).skyblockflow$hoveredSlot();
    return slot == null ? ItemStack.EMPTY : slot.getItem();
  }

  public boolean onExcalia() {
    var server = mc.getCurrentServer();
    if (server == null) return false;
    String address = server.ip.toLowerCase(Locale.ROOT).split(":", 2)[0];
    return address.equals("excalia.fr") || address.endsWith(".excalia.fr");
  }

  public void open(Screen parent, String item, boolean uses, boolean showMaterials) {
    open(parent, item, uses, showMaterials, null);
  }

  public void openSelected(Screen parent, String item, Recipe preferred) {
    Recipe recipe = preferred == null ? recipeFor(item) : preferred;
    var viewer = new RecipeScreen(this, parent, item, false, false, recipe);
    mc.gui.setScreen(viewer);
    prepareCraft(viewer, recipe, parent);
  }

  private void open(Screen parent, String item, boolean uses, boolean showMaterials, Recipe preferred) {
    catalog();
    mc.gui.setScreen(new RecipeScreen(this, parent, item, uses, showMaterials, preferred));
  }

  public List<ItemTile> items(String query, boolean pinnedOnly) {
    var current = catalog();
    var language = net.minecraft.locale.Language.getInstance();
    if (indexedItems != current || indexedLanguage != language) {
      indexedItems = current;
      indexedLanguage = language;
      var ids = new LinkedHashSet<String>();
      for (Recipe recipe : current.recipes()) {
        ids.add(recipe.output());
        for (Ingredient input : recipe.ingredients()) ids.addAll(input.choices());
      }
      ids.addAll(season.items().keySet());
      BuiltInRegistries.ITEM.keySet().stream().sorted().forEach(key -> {
        if (BuiltInRegistries.ITEM.getValue(key) != Items.AIR) ids.add(key.toString());
      });
      itemIndex = ids.stream().map(id -> itemTile(id, null)).toList();
    }
    var source = pinnedOnly
        ? settings().pins.stream().map(current::get).filter(Objects::nonNull)
            .map(recipe -> itemTile(recipe.output(), recipe)).toList()
        : itemIndex;
    String words = RecipeCatalog.searchKey(query);
    if (words.isBlank()) return source;
    String[] terms = words.split(" +");
    return source.stream().filter(tile -> {
      for (String term : terms) if (!tile.search().contains(term)) return false;
      return true;
    }).toList();
  }

  private ItemTile itemTile(String id, Recipe recipe) {
    return new ItemTile(id, recipe, RecipeCatalog.searchKey(name(id)) + " " + RecipeCatalog.searchKey(id));
  }

  public RecipeCatalog catalog() {
    var collections = mc.player == null ? List.of() : mc.player.getRecipeBook().getCollections();
    boolean excalia = onExcalia();
    if (level == mc.level && recipeCollections == collections && seasonActive == excalia) return catalog;
    if (level != mc.level) icons.clear();
    level = mc.level;
    recipeCollections = collections;
    seasonActive = excalia;
    var entries = new ArrayList<Recipe>();
    // Season data remains browsable offline. Live prices and custom actions are scoped to Excalia.
    entries.addAll(observedRecipes.values());
    entries.addAll(season.recipes());
    serverRecipes.clear();
    if (mc.level != null && mc.player != null) {
      var context = SlotDisplayContext.fromLevel(mc.level);
      for (var group : mc.player.getRecipeBook().getCollections())
        for (var entry : group.getRecipes()) addServerRecipe(entry, context, entries);
    }
    entries.addAll(VanillaRecipeCatalog.load());
    catalog = new RecipeCatalog(entries);
    previousRecipe = null;
    return catalog;
  }

  private void addServerRecipe(RecipeDisplayEntry entry, net.minecraft.util.context.ContextMap context,
                                List<Recipe> recipes) {
    var display = entry.display();
    int width;
    List<SlotDisplay> slots;
    Station station = Station.VANILLA;
    switch (display) {
      case ShapedCraftingRecipeDisplay shaped -> { width = shaped.width(); slots = shaped.ingredients(); }
      case ShapelessCraftingRecipeDisplay shapeless -> { width = 3; slots = shapeless.ingredients(); }
      case FurnaceRecipeDisplay furnace -> {
        width = 1;
        slots = List.of(furnace.ingredient());
        station = Station.OTHER;
      }
      case StonecutterRecipeDisplay cut -> {
        width = 1;
        slots = List.of(cut.input());
        station = Station.OTHER;
      }
      case SmithingRecipeDisplay smithing -> {
        width = 3;
        slots = List.of(smithing.template(), smithing.base(), smithing.addition());
        station = Station.OTHER;
      }
      default -> { return; }
    }
    var result = display.result().resolveForFirstStack(context);
    if (result.isEmpty() || width < 1) return;
    String output = key(result);
    var amounts = new LinkedHashMap<List<String>, Integer>();
    var cells = new ArrayList<Cell>();
    for (int i = 0; i < slots.size(); i++) {
      var stacks = slots.get(i).resolveForStacks(context);
      var choices = new LinkedHashSet<String>();
      for (ItemStack stack : stacks) if (!stack.isEmpty()) choices.add(key(stack));
      if (choices.isEmpty()) continue;
      var ids = List.copyOf(choices);
      cells.add(new Cell(i % width, i / width, new Ingredient(ids, 1)));
      amounts.merge(ids, 1, Integer::sum);
    }
    if (cells.isEmpty()) return;
    var ingredients = new ArrayList<Ingredient>();
    amounts.forEach((choices, count) -> ingredients.add(new Ingredient(choices, count)));
    String id = "book:" + output + "/" + display.getClass().getSimpleName() + "/"
        + Integer.toUnsignedString(Objects.hash(result.getCount(), cells), 36);
    String note = station == Station.VANILLA ? "Server recipe book"
        : display instanceof FurnaceRecipeDisplay ? "Furnace · server recipe book"
        : display instanceof StonecutterRecipeDisplay ? "Stonecutter · server recipe book"
        : "Smithing table · server recipe book";
    recipes.add(new Recipe(id, output, result.getCount(), station, width == 1 ? 1 : 3,
        ingredients, cells, note, true));
    if (station == Station.VANILLA) serverRecipes.putIfAbsent(id, entry.id());
    if (icons.size() < 4096) icons.putIfAbsent(output, result.copyWithCount(1));
  }

  public RecipeDisplayId serverId(Recipe recipe) {
    if (recipe == null) return null;
    var direct = serverRecipes.get(recipe.id());
    if (direct != null) return direct;
    return catalog().produces(recipe.output()).stream()
        .filter(r -> r.station() == recipe.station() && r.amount() == recipe.amount()
            && r.ingredients().size() == recipe.ingredients().size()
            && new HashSet<>(r.ingredients()).equals(new HashSet<>(recipe.ingredients())))
        .map(r -> serverRecipes.get(r.id())).filter(Objects::nonNull).findFirst().orElse(null);
  }

  Recipe learnGrid(Recipe known, String output, List<Cell> cells, int count) {
    if (cells.isEmpty() || known != null && known.hasGrid()) return known;
    var totals = new LinkedHashMap<List<String>,Integer>();
    for (var cell : cells) totals.merge(cell.ingredient().choices(),cell.ingredient().amount(),Integer::sum);
    var ingredients = totals.entrySet().stream().map(e -> new Ingredient(e.getKey(),e.getValue())).toList();
    if (known != null && (!new HashSet<>(known.ingredients()).equals(new HashSet<>(ingredients)) || count != known.amount())) return known;
    Station station = known == null ? Station.CUSTOM : known.station();
    if (station == Station.VANILLA && cells.stream().anyMatch(c -> c.x()>=3 || c.y()>=3)) return known;
    var learned = new Recipe(known == null ? "observed:"+output : known.id(),output,count,station,
        station == Station.VANILLA ? 3 : 6,ingredients,cells,"",true);
    if (observedRecipes.size() >= 256) observedRecipes.remove(observedRecipes.keySet().iterator().next());
    observedRecipes.put(learned.id(),learned); recipeCollections = null;
    return learned;
  }

  public String key(ItemStack stack) {
    if (stack.isEmpty()) return "";
    var model = stack.get(DataComponents.ITEM_MODEL);
    if (model != null) {
      String id = model.toString();
      String canonical = modelIds.get(id);
      if (canonical != null) return canonical;
      if (!model.getNamespace().equals("minecraft")) return id;
      if (!model.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return "model:" + id;
    }
    if (stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.CUSTOM_DATA))
      return "named:" + BuiltInRegistries.ITEM.getKey(stack.getItem()) + "/"
          + RecipeCatalog.searchKey(stack.getHoverName().getString()).replaceAll("[/\\p{Cntrl}]", "-");
    return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  }

  public String remember(ItemStack stack) {
    String id = key(stack);
    if (!id.isEmpty() && icons.size() < 4096) icons.putIfAbsent(id, stack.copyWithCount(1));
    return id;
  }

  public ItemStack icon(String id) {
    var seen = icons.get(id);
    if (seen != null) return seen;
    var resources = mc.getResourceManager();
    if (iconResources.get() != resources) {
      catalogIcons.clear();
      iconResources = new java.lang.ref.WeakReference<>(resources);
    }
    var cached = catalogIcons.get(id);
    if (cached != null) return cached;
    ItemStack stack = catalogIcon(id);
    if (catalogIcons.size() < 4096) catalogIcons.put(id, stack);
    return stack;
  }

  private ItemStack catalogIcon(String id) {
    if (id.startsWith("minecraft:")) {
      var location = Identifier.tryParse(id);
      var item = location == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(location);
      return item.getDefaultInstance();
    }
    var item = season.items().get(id);
    String model = item != null && !item.model().isEmpty() ? item.model() : id;
    var stack = fallbackIcon(id);
    var loc = Identifier.tryParse(model);
    if (loc != null && mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath(
        loc.getNamespace(), "items/" + loc.getPath() + ".json")).isPresent())
      stack.set(DataComponents.ITEM_MODEL, loc);
    return stack;
  }

  private ItemStack fallbackIcon(String id) {
    String path = id.substring(id.lastIndexOf(':') + 1);
    if (path.matches(".+_t[1-5]")) {
      var first = seasonById.get("s3:" + path.substring(0, path.length() - 1) + "1");
      if (first != null) {
        var base = Identifier.tryParse(first.ingredients().getFirst().choices().getFirst());
        if (base != null && base.getNamespace().equals("minecraft"))
          return BuiltInRegistries.ITEM.getValue(base).getDefaultInstance();
      }
    }
    String fallback = switch (path) {
      case "cobblegrinder", "copper_furnace_off", "silo" -> "blast_furnace";
      case "composteur", "mycelium_isolant" -> "composter";
      case "extracteur_seve", "resine_sapin" -> "resin_clump";
      case "desintegrateur" -> "smoker";
      case "recycler" -> "grindstone";
      case "ciseleur" -> "stonecutter";
      case "table_compactage", "spatial_atelier_off" -> "crafting_table";
      case "chunk-guard" -> "shield";
      case "teleport_pad" -> "end_portal_frame";
      case "panneau_solaire", "panneau_solaire_2", "panneau_solaire_3" -> "daylight_detector";
      case "cellule_photovoltaique", "cellule_diamant", "cellule_cristal" -> "blue_stained_glass";
      case "circuit", "puce_stellaire", "microcircuit" -> "repeater";
      case "cable_solaire", "electrode" -> "lightning_rod";
      case "silicium" -> "clay_ball";
      case "acier", "plaque_blindage" -> "iron_ingot";
      case "noyau_ionique", "electrode_cristal", "electrode_diamant" -> "amethyst_shard";
      case "batterie", "batterie_avancee", "batterie_industrielle", "endless_battery" -> "redstone_torch";
      case "bouteille_oxygene", "electrolyte" -> "honey_bottle";
      case "caoutchouc", "gel_cryogenique", "bioplastique" -> "slime_ball";
      case "verre_renforce" -> "light_blue_stained_glass_pane";
      case "particule_or" -> "gold_nugget";
      case "particule_cuivre" -> "copper_ingot";
      case "particule_fer" -> "iron_nugget";
      case "particule_quartz" -> "quartz";
      case "particule_diamant" -> "diamond";
      case "particule_charbon" -> "coal";
      case "particule_redstone" -> "redstone";
      case "particule_lapis" -> "lapis_lazuli";
      case "particule_emeraude" -> "emerald";
      case "particule_amethyste" -> "amethyst_shard";
      case "particule_netherite", "cadre_netherite" -> "netherite_scrap";
      default -> path.startsWith("spatial_") ? "firework_rocket" : "paper";
    };
    return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(fallback)).getDefaultInstance();
  }

  public String name(String id) {
    var known = season.items().get(id);
    if (known != null && !known.name().isBlank()
        && !(id.startsWith("minecraft:") && known.name().equals(id.substring(10)))) return known.name();
    var seen = icons.get(id);
    if (seen != null) return seen.getHoverName().getString();
    if (id.startsWith("minecraft:")) {
      var stack = icon(id);
      if (!stack.isEmpty()) return stack.getHoverName().getString();
    }
    return id.substring(id.lastIndexOf(':') + 1).replace('_', ' ');
  }

  public String recipeLookup(String item) {
    if (!item.startsWith("oraxen:")) return item;
    return item.replaceFirst("_(?:peu_commun|rare|epique|legendaire|astronomique)$", "_commun");
  }

  public Recipe recipeFor(String item) {
    var recipes = catalog().produces(recipeLookup(item));
    return recipes.stream().filter(it -> !it.note().equals("Décompactage") && it.station() != Station.OTHER)
        .findFirst().orElse(recipes.isEmpty() ? null : recipes.getFirst());
  }

  public boolean pinned(Recipe recipe) { return settings().pins.contains(recipe.id()); }

  public void togglePin(Recipe recipe) {
    var pins = settings().pins;
    if (!pins.remove(recipe.id())) {
      if (pins.size() >= 1024) { message("The recipe pin list is full"); return; }
      pins.add(recipe.id());
    }
    save();
    message((pinned(recipe) ? "Pinned " : "Unpinned ") + name(recipe.output()));
  }

  public void handle(Action action, String item, Recipe selected, Screen parent, boolean max) {
    if (action == Action.RECIPES || action == Action.USES) {
      open(parent, item, action == Action.USES, false, action == Action.RECIPES ? selected : null);
      return;
    }
    Recipe recipe = selected == null ? recipeFor(item) : selected;
    if (recipe == null) { message("No known recipe for " + name(item)); return; }
    switch (action) {
      case PIN -> togglePin(recipe);
      case MATERIALS -> {
        showMaterials(recipe);
        open(parent, recipe.output(), false, true, recipe);
      }
      case CRAFT -> {
        if (usesExcaliaBook(recipe) && serverId(recipe) == null)
          openSelected(parent, item, recipe);
        else craft(recipe, parent, max);
      }
      default -> { }
    }
  }

  public boolean canCraft(Recipe recipe) {
    return recipe != null && (recipe.station() == Station.CUSTOM
        || recipe.station() == Station.VANILLA && (recipe.hasGrid() || usesExcaliaBook(recipe)));
  }

  public boolean usesExcaliaBook(Recipe recipe) {
    return recipe.station() == Station.CUSTOM || onExcalia() && recipe.id().startsWith("s3:");
  }

  public boolean canPlace(Recipe recipe, Screen parent) {
    return recipe != null && recipe.station() == Station.VANILLA && recipe.hasGrid()
        && mc.player != null && mc.gameMode != null
        && parent instanceof AbstractContainerScreen<?> table
        && table.getMenu() instanceof CraftingMenu && table.getMenu() == mc.player.containerMenu
        && table.getMenu().getCarried().isEmpty();
  }

  void placeVanilla(Recipe recipe, Screen parent, boolean max) {
    if (!canPlace(recipe, parent)) return;
    var menu = mc.player.containerMenu;
    var recipeId = serverId(recipe);
    if (mc.gui.screen() != parent) mc.gui.setScreen(parent);
    if (mc.player.containerMenu == menu) {
      if (recipeId != null) {
        mc.gameMode.handlePlaceRecipe(menu.containerId, recipeId, max);
        message("Recipe placement requested");
      } else vanillaPlacement.start(recipe, (AbstractContainerScreen<?>)parent, max);
    }
  }

  public void craft(Recipe recipe, Screen parent, boolean max) {
    if (!canCraft(recipe)) return;
    if (mc.gui.screen() instanceof RecipeScreen existing) {
      pressCraft(existing, recipe, parent, max);
    } else if (canPlace(recipe, parent)) {
      placeVanilla(recipe, parent, max);
    } else {
      var viewer = new RecipeScreen(this, parent, recipe.output(), false, false, recipe);
      mc.gui.setScreen(viewer);
      if (craftButton(viewer, recipe, parent) == CraftButton.GUIDE) viewer.showCraftGuide(recipe);
      else pressCraft(viewer, recipe, parent, max);
    }
  }

  public void message(String message) {
    status = message;
    if (mc.player != null && (mc.gui.screen() == null || mc.gui.screen() instanceof AbstractContainerScreen<?>))
      mc.gui.hud.setOverlayMessage(Component.literal(message), false);
  }

  public String status() { return status; }

  public void showMaterials(Recipe recipe) {
    settings().checklistRecipe = recipe.id();
    nextChecklist = 0;
    save();
  }

  public Recipe materialRecipe() { return catalog().get(settings().checklistRecipe); }

  public Checklist checklist() {
    var recipe = materialRecipe();
    long now = System.nanoTime();
    if (recipe == null) return new Checklist(List.of(), false);
    if (now >= nextChecklist || previousRecipe != recipe || previousBatches != settings().batches) {
      nextChecklist = now + 250_000_000L;
      Map<String, Long> inventory = inventory();
      if (previousRecipe != recipe || previousBatches != settings().batches || !lastInventory.equals(inventory)) {
        previousRecipe = recipe;
        previousBatches = settings().batches;
        lastInventory = inventory;
        checklist = catalog.calculate(recipe, settings().batches, inventory);
      }
    }
    return checklist;
  }

  private Map<String, Long> inventory() {
    if (mc.player == null) return Map.of();
    var counts = new HashMap<String, Long>();
    var inventory = mc.player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++) addCount(counts, inventory.getItem(i));
    var menu = mc.player.containerMenu;
    if (settings().includeContainer && (menu instanceof net.minecraft.world.inventory.ChestMenu
        || menu instanceof net.minecraft.world.inventory.ShulkerBoxMenu
        || menu instanceof net.minecraft.world.inventory.HopperMenu
        || menu instanceof net.minecraft.world.inventory.DispenserMenu)
        && (mc.gui.screen() instanceof RecipeScreen recipes && recipes.hasStorageParent()
            || mc.gui.screen() instanceof AbstractContainerScreen<?> screen && isStorage(screen))) {
      for (Slot slot : mc.player.containerMenu.slots)
        if (slot.container != inventory && slot.mayPickup(mc.player)) addCount(counts, slot.getItem());
    }
    return counts;
  }

  public boolean isStorage(AbstractContainerScreen<?> screen) {
    if (mc.player == null || screen.getMenu() != mc.player.containerMenu) return false;
    if (!onExcalia()) return true;
    String title = screen.getTitle().getString();
    var normalized = JobXp.normalize(title);
    return normalized.contains("coffre") || normalized.contains("chest") || normalized.contains("barrel")
        || normalized.contains("tonneau") || normalized.contains("shulker") || normalized.contains("hopper")
        || normalized.contains("entonnoir") || normalized.contains("dropper") || normalized.contains("dispenser");
  }

  private void addCount(Map<String, Long> counts, ItemStack stack) {
    if (!stack.isEmpty()) counts.merge(remember(stack), (long) stack.getCount(), Long::sum);
  }

  public void drawChecklist(GuiGraphicsExtractor g, int x, int y, int width, int height, int offset) {
    Recipe recipe = materialRecipe();
    if (recipe == null || width < 55 || height < 32) return;
    var materials = checklist();
    offset = materials.offset(height, offset);
    int total = materials.materials().size();
    int rows = Math.min(Checklist.rows(height), Math.max(0, total - offset));
    Ui.surface(g, x, y, width, 34 + rows * 19, FlowScreen.CARD, FlowScreen.BORDER);
    Ui.text(g, Ui.trim(name(recipe.output()) + " ×" + (long) recipe.amount() * settings().batches,
        width - 14), x + 7, y + 6, FlowScreen.ACCENT);
    for (int i = 0; i < rows; i++) {
      var material = materials.materials().get(offset + i);
      int rowY = y + 20 + i * 19;
      String count = Numbers.integer(material.present()) + "/" + Numbers.integer(material.needed());
      g.item(icon(material.item()), x + 6, rowY - 2);
      int space = width - 38 - Ui.width(count);
      Ui.text(g, Ui.trim(name(material.item()), space), x + 27, rowY + 2, FlowScreen.TEXT);
      Ui.text(g, count, x + width - Ui.width(count) - 7, rowY + 2,
          material.complete() ? FlowScreen.ACCENT : FlowScreen.GOLD);
    }
    String footer = total == 0 ? "Materials ready" : materials.limited() ? "Expansion limit reached"
        : offset + rows < total ? "+" + (total - offset - rows) + " more"
        : settings().includeContainer ? "Inventory + open storage" : "Inventory";
    Ui.text(g, Ui.trim(footer, width - 14), x + 7, y + 20 + rows * 19, FlowScreen.MUTED);
    if (total > rows)
      Ui.scrollBar(g, x + width - 3, y + 19, rows * 19, total * 19, offset * 19);
  }

  public boolean inspectOutside() { return settings().inspection == Inspection.EVERYWHERE; }

  public boolean inspectInside() { return settings().inspection != Inspection.OFF; }

  public void inspect(GuiGraphicsExtractor g, ItemStack stack, int mouseX, int mouseY) {
    inspect(g, remember(stack), stack, mouseX, mouseY, false);
  }

  public void inspectBrowser(GuiGraphicsExtractor g, String id, int mouseX, int mouseY) {
    inspect(g, id, icon(id), mouseX, mouseY, true);
  }

  private void inspect(GuiGraphicsExtractor g, String id, ItemStack stack, int mouseX, int mouseY,
                       boolean browsing) {
    if (id.isBlank()) return;
    long now = System.nanoTime();
    if (!id.equals(inspectedItem) || now - lastInspected > 500_000_000L) {
      inspectedItem = id;
      inspectedSince = now;
    }
    lastInspected = now;
    if (now - inspectedSince < 350_000_000L) return;
    var c = catalog();
    var usage = c.uses(id);
    var recipe = recipeFor(id);
    var data = season.items().get(id);
    String description = data == null ? "" : data.description();
    if (recipe == null && usage.isEmpty() && description.isEmpty()) return;
    int screenW = g.guiWidth(), screenH = g.guiHeight();
    int w = Math.min(136, screenW / 3 + 5);
    if (w < 65) return;
    int iconsPerRow = Math.max(1, (w - 16) / 19);
    int recipeHeight = recipe == null ? 0 : recipe.hasGrid() ? recipe.size() * 15 + 15 : 25;
    int h = 30 + (description.isEmpty() ? 0 : 12)
        + (usage.isEmpty() ? 0 : 30) + recipeHeight;
    int x = mouseX < screenW / 2 ? screenW - w - 5 : 5;
    int y = Math.max(5, Math.min(mouseY - 11, screenH - h - 5));
    Ui.surface(g, x, y, w, h, FlowScreen.PANEL, FlowScreen.BORDER);
    Ui.text(g, Ui.trim(name(id), w - 14), x + 7, y + 7, FlowScreen.ACCENT);
    int line = y + 22;
    if (!description.isEmpty()) {
      Ui.text(g, Ui.trim(description, w - 14), x + 7, line, FlowScreen.TEXT);
      line += 12;
    }
    if (!usage.isEmpty()) {
      Ui.text(g, Ui.trim("Used for · " + keyName(Action.USES), w - 14), x + 7, line, FlowScreen.TEXT);
      line += 11;
      int index = 0;
      for (String output : usage.stream().map(Recipe::output).distinct().limit(iconsPerRow).toList())
        g.item(icon(output), x + 7 + index++ * 19, line);
      line += 19;
    }
    if (recipe != null) {
      Ui.text(g, Ui.trim("Recipe · " + keyName(Action.RECIPES), w - 14), x + 7, line, FlowScreen.TEXT);
      line += 11;
      if (recipe.hasGrid()) {
        g.pose().pushMatrix();
        g.pose().translate(x + 6, line);
        g.pose().scale(.8f, .8f);
        for (Cell cell : recipe.cells()) {
          var ingredient = cell.ingredient();
          var icon = icon(ingredient.choices().getFirst());
          int xCell = cell.x() * 18, yCell = cell.y() * 18;
          g.item(icon, xCell, yCell);
          if (ingredient.amount() > 1)
            g.itemDecorations(mc.font, icon, xCell, yCell, Integer.toString(ingredient.amount()));
        }
        g.pose().popMatrix();
      } else Ui.text(g, Ui.trim(recipe.note().isBlank() ? "Ingredient list in Recipes" : recipe.note(),
          w - 14), x + 7, line, FlowScreen.MUTED);
    }
  }

  public List<String> shopLines(ItemStack stack, boolean allowReference) {
    if (stack.isEmpty()) return List.of();
    var prices = new ArrayList<String>();
    if (onExcalia() && mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
      String title = JobXp.normalize(screen.getTitle().getString());
      String shop = ShopPrices.shop(title);
      boolean shopSlot = !shop.isEmpty() && mc.player != null && screen.getMenu().slots.stream().anyMatch(slot ->
          slot.container != mc.player.getInventory() && slot.getItem() == stack);
      if (!shop.isEmpty() && shopSlot && stack.get(DataComponents.LORE) != null) {
        var raw = stack.get(DataComponents.LORE).lines().stream().map(Component::getString).toList();
        for (var quote : ShopPrices.live(raw))
          prices.add(ShopPrices.line("Shop", quote.buy(), quote.sell(), quote.unit(), stack.getCount()));
      }
    }
    if (prices.isEmpty() && allowReference) prices.addAll(referencePrices(key(stack), stack.getCount()));
    if (app.excaliaApi != null && !stack.isEnchanted() && !stack.isDamaged()
        && stack.get(DataComponents.POTION_CONTENTS) == null)
      prices.addAll(app.excaliaApi.market(key(stack), stack.getHoverName().getString(), stack.getCount()));
    return prices;
  }

  public List<String> shopReference(String id) {
    if (!app.store.config.tools.shopValues) return List.of();
    var lines = new ArrayList<>(referencePrices(id, 1));
    if (app.excaliaApi != null) lines.addAll(app.excaliaApi.market(id, name(id), 1));
    return lines;
  }

  public record PurchaseSource(String source, String price, String destination, String stock) {}

  public List<PurchaseSource> purchaseSources(String id) {
    if (id == null || !app.store.config.tools.shopValues) return List.of();
    var data = season.items().get(id);
    var live = app.excaliaApi == null ? List.<SeasonThreeCatalog.ShopQuote>of() : app.excaliaApi.prices(id);
    var prices = !live.isEmpty() ? live : data == null ? List.<SeasonThreeCatalog.ShopQuote>of() : data.prices();
    var sources = new ArrayList<PurchaseSource>();
    for (var quote : prices) {
      if (quote.buy() == null || quote.buy().signum() < 0) continue;
      String shop = switch (quote.shop()) {
        case "mineur" -> "Mineur"; case "bucheron" -> "Bûcheron"; case "fermier" -> "Fermier";
        case "chasseur" -> "Chasseur"; case "aventurier" -> "Aventurier"; case "pecheur" -> "Pêcheur";
        case "blocs" -> "Blocs"; case "spawners" -> "Spawners"; case "vote" -> "Vote";
        case "boutique" -> "Boutique"; default -> quote.shop();
      };
      String unit = quote.unit().equalsIgnoreCase("Skydollars") ? "$" : quote.unit();
      String destination = switch (quote.shop()) {
        case "vote" -> "/vote"; case "boutique" -> "/boutique";
        case "pecheur" -> "Marchand de pêche"; default -> "/shop → " + shop;
      };
      sources.add(new PurchaseSource("Shop " + shop, Numbers.decimal(quote.buy(), 0, 4) + " " + unit + " / item",
          destination, "Server shop"));
    }
    if (app.excaliaApi != null) for (var offer : app.excaliaApi.sellers(id, name(id)))
      sources.add(new PurchaseSource("Shop · " + offer.owner(), Numbers.decimal(offer.price(), 0, 4) + " $ / item",
          "Player shop", offer.unlimited() ? "Unlimited stock" : Numbers.integer(offer.stock()) + " in stock"));
    return List.copyOf(sources);
  }

  private List<String> referencePrices(String id, int count) {
    var data = season.items().get(id);
    var live = app.excaliaApi == null ? List.<SeasonThreeCatalog.ShopQuote>of() : app.excaliaApi.prices(id);
    var prices = !live.isEmpty() ? live : data == null ? List.<SeasonThreeCatalog.ShopQuote>of() : data.prices();
    var lines = new ArrayList<String>();
    for (var quote : prices) {
      var sell = quote.sell();
      var level = app.jobs.tracker.data.jobs.get(quote.shop());
      if (sell != null && level != null && onExcalia()) sell = ShopValue.unit(sell, level.level);
      lines.add(ShopPrices.line(!live.isEmpty() ? app.excaliaApi.priceSource(quote.shop()) : "S3 " + quote.shop(),
          quote.buy(), sell, quote.unit(), count));
    }
    return lines;
  }

  public List<Component> browserTooltip(String id, boolean controls) {
    var lines = new ArrayList<Component>();
    // A catalog icon can be a stand-in (for example glass for a solar cell).
    // Never use that stand-in's vanilla lore or prices as the item's description.
    if (id.startsWith("minecraft:")) {
      var location = Identifier.tryParse(id);
      if (location != null) lines.addAll(Screen.getTooltipFromItem(mc, BuiltInRegistries.ITEM.getValue(location).getDefaultInstance()));
    } else if (icons.containsKey(id)) {
      lines.addAll(Screen.getTooltipFromItem(mc, icons.get(id)));
    }
    if (lines.isEmpty()) lines.add(Ui.label(name(id)));
    var existing = new HashSet<String>();
    lines.forEach(line -> existing.add(line.getString()));
    var data = season.items().get(id);
    if (data != null && !data.description().isBlank() && existing.add(data.description()))
      lines.add(dev.asarix.skyblockflow.ui.DisplayText.literal(data.description()).withColor(FlowScreen.MUTED));
    for (String price : shopReference(id))
      if (existing.add(price)) lines.add(dev.asarix.skyblockflow.ui.DisplayText.literal(price).withColor(FlowScreen.GOLD));
    pricing.append(id, 1, lines);
    if (controls) lines.add(Ui.label("Click for recipe · Right-click for uses").copy().withColor(FlowScreen.MUTED));
    return lines;
  }

  public void craftingValue(ItemStack stack, List<Component> lines) { pricing.append(key(stack), stack.getCount(), lines); }

  public void browserTooltip(GuiGraphicsExtractor g, String id, int x, int y, boolean controls) {
    var actual = icons.get(id);
    var image = actual == null ? Optional.<net.minecraft.world.inventory.tooltip.TooltipComponent>empty() : actual.getTooltipImage();
    g.setTooltipForNextFrame(mc.font, browserTooltip(id, controls), image, x, y, RecipeScreen.TOOLTIP_STYLE);
  }
}
