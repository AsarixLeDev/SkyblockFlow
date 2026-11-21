package dev.asarix.skyblockflow.ui;

import static dev.asarix.skyblockflow.ui.FlowScreen.ACCENT;
import static dev.asarix.skyblockflow.ui.FlowScreen.BORDER;
import static dev.asarix.skyblockflow.ui.FlowScreen.CARD;
import static dev.asarix.skyblockflow.ui.FlowScreen.MUTED;
import static dev.asarix.skyblockflow.ui.FlowScreen.PANEL;
import static dev.asarix.skyblockflow.ui.FlowScreen.TEXT;

import dev.asarix.skyblockflow.RecipeHelper;
import dev.asarix.skyblockflow.RecipeHelper.Action;
import dev.asarix.skyblockflow.RecipeHelper.CraftButton;
import dev.asarix.skyblockflow.RecipeHelper.ItemTile;
import dev.asarix.skyblockflow.core.RecipeCatalog;
import dev.asarix.skyblockflow.core.RecipeCatalog.Recipe;
import dev.asarix.skyblockflow.core.RecipeSettings.Inspection;
import dev.asarix.skyblockflow.ui.FlowScreen.FlowButton;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** A compact item index, bookmark shelf, and recipe viewer. */
public final class RecipeScreen extends Screen {
  private enum View { RECIPES, USES, PINS }
  private record Hit(String item, Recipe recipe) {}
  static final Identifier TOOLTIP_STYLE = Identifier.fromNamespaceAndPath("skyblockflow", "recipe");
  private static final int TILE = 18;
  private static final int SLOT = 0xff151e2a;
  private final RecipeHelper helper;
  private final Screen parent;
  private String item;
  private View view;
  private boolean materials, craftGuide, options;
  private CraftButton currentCraftButton;
  private Recipe selected;
  private RecipeCatalog indexed;
  private List<ItemTile> filtered = List.of();
  private List<Recipe> results = List.of(), pins = List.of();
  private String query = "";
  private Hit hover;
  private FlowButton pin, materialsButton, craft, prev, next;
  private int x, y, w, h, right, itemCols, itemRows, leftCols, itemPage, pinPage, recipePage, checklistScroll;
  private int searchY;
  private long lastClick;
  private Hit lastItemClick;

  public RecipeScreen(RecipeHelper helper, Screen parent, String item, boolean uses, boolean showMaterials,
                      Recipe preferred) {
    super(Component.literal("Excalia recipes"));
    this.helper = helper;
    this.parent = parent;
    this.item = item;
    view = uses ? View.USES : View.RECIPES;
    materials = showMaterials;
    selected = preferred;
  }

  @Override
  protected void init() {
    w = Math.min(190, Math.max(125, width / 2));
    x = (width - w) / 2;
    y = Math.max(19, (height - 224) / 2);
    h = Math.min(224, height - y - 13);
    right = x + w + 6;
    itemCols = Math.max(1, (width - right - 3) / TILE);
    leftCols = Math.max(1, (x - 7) / TILE);
    searchY = height - 21;
    itemRows = Math.max(1, (searchY - 24) / TILE);
    int header = y + 5;
    int half = (w - 17) / 2;
    add(x + 6, y - 18, half, "Recipes", view == View.RECIPES,
        () -> changeView(View.RECIPES)).setTooltip(tip("Recipes · " + helper.keyName(Action.RECIPES)));
    add(x + 8 + half, y - 18, half, "Uses", view == View.USES,
        () -> changeView(View.USES)).setTooltip(tip("Uses and obtainment · " + helper.keyName(Action.USES)));
    int pageY = header + 18;
    prev = add(x + 6, pageY, 16, "‹", () -> pageRecipe(-1));
    next = add(x + w - 22, pageY, 16, "›", () -> pageRecipe(1));
    add(4, 4, Math.min(48, x - 8), "Pins", view == View.PINS, () -> changeView(View.PINS))
        .setTooltip(tip("Pinned items and recipes"));
    add(right, 4, 16, "‹", () -> itemPage(-1)).setTooltip(tip("Previous items"));
    add(right + itemCols * TILE - 16, 4, 16, "›", () -> itemPage(1)).setTooltip(tip("Next items"));

    var search = addRenderableWidget(new FlowTextField(font, right, searchY, Math.max(12, itemCols * TILE - 19), 17,
        Component.literal("Search items")));
    search.setHint(Ui.label("Search…").copy().withStyle(style -> style.withColor(MUTED)));
    search.setMaxLength(80);
    search.setValue(query);
    search.setResponder(text -> {
      query = text;
      itemPage = 0;
      filterItems();
    });
    add(right + itemCols * TILE - 16, searchY, 16, "⚙", this::toggleOptions)
        .setTooltip(tip("Recipe helper settings"));
    if (options) addOptions();

    int actionY = y + h - 22;
    int small = (w - 12) / 4;
    int middle = w - 16 - 2 * small;
    pin = add(x + 6, actionY, small, "Pin", this::togglePin);
    materialsButton = add(x + 8 + small, actionY, middle, "Materials", this::toggleMaterials);
    craft = add(x + w - 6 - small, actionY, small, "Craft", true, () -> craft(false));
    pin.setTooltip(tip("Pin or unpin · " + helper.keyName(Action.PIN)));
    materialsButton.setTooltip(tip("Calculate raw materials · " + helper.keyName(Action.MATERIALS)));
    if (!options && materials && helper.materialRecipe() != null) {
      if (selected == null) selected = helper.materialRecipe();
      addChecklistControls();
    }
    updateIndex();
    updateResults();
  }

  private void toggleOptions() {
    options = !options;
    rebuildWidgets();
  }

  private void addOptions() {
    int left = x + 14, wide = w - 28;
    add(left, y + 72, wide, "Item info · " + infoLabel(), this::cycleInspection)
        .setTooltip(tip("Choose where item descriptions appear"));
    boolean hidden = helper.settings().hideBackgroundOverlays;
    add(left, y + 95, wide, hidden ? "Background HUD · Hidden" : "Background HUD · Shown", hidden, () -> {
      helper.settings().hideBackgroundOverlays = !hidden;
      helper.save();
      rebuildWidgets();
    }).setTooltip(tip("Hide this mod's HUD while viewing recipes"));
  }

  private void addChecklistControls() {
    int rowY = y + 42;
    int third = (w - 18) / 3;
    add(x + 6, rowY, third, "HUD", helper.settings().showChecklist, () -> {
      helper.settings().showChecklist = !helper.settings().showChecklist;
      helper.save();
      rebuildWidgets();
    }).setTooltip(tip("Pin or unpin the checklist as an overlay"));
    add(x + 8 + third, rowY, third, "Chest", helper.settings().includeContainer, () -> {
      helper.settings().includeContainer = !helper.settings().includeContainer;
      helper.save();
      rebuildWidgets();
    }).setTooltip(tip("Include items in the open storage container"));
    var batches = addRenderableWidget(new FlowTextField(font, x + 10 + third * 2, rowY, third, 16,
        Component.literal("Recipe batches")));
    batches.setTooltip(tip("Number of recipe batches"));
    batches.setMaxLength(4);
    batches.setValue(Integer.toString(helper.settings().batches));
    batches.setResponder(text -> {
      if (text.isBlank() || !text.chars().allMatch(ch -> ch >= '0' && ch <= '9')) return;
      helper.settings().batches = Math.clamp(Integer.parseInt(text), 1, 9999);
      helper.save();
    });
  }

  private FlowButton add(int x, int y, int w, String label, Runnable action) {
    return add(x, y, w, label, false, action);
  }

  private FlowButton add(int x, int y, int w, String label, boolean primary, Runnable action) {
    return addRenderableWidget(new FlowButton(x, y, Math.max(1, w), 16, label, action, primary));
  }

  private static Tooltip tip(String label) { return Tooltip.create(Ui.label(label), Optional.empty(), TOOLTIP_STYLE); }

  private String infoLabel() {
    return switch (helper.settings().inspection) {
      case RECIPE_HELPER -> "Recipe helper";
      case EVERYWHERE -> "All item screens";
      case OFF -> "Off";
    };
  }

  private void cycleInspection() {
    var modes = Inspection.values();
    helper.settings().inspection = modes[(helper.settings().inspection.ordinal() + 1) % modes.length];
    helper.save();
    rebuildWidgets();
  }

  private void updateIndex() {
    indexed = helper.catalog();
    filterItems();
  }

  private void filterItems() {
    filtered = helper.items(query, false);
    itemPage = Math.clamp(itemPage, 0, itemPages() - 1);
  }

  private int itemPages() { return Math.max(1, Math.ceilDiv(filtered.size(), itemCols * itemRows)); }

  private void itemPage(int delta) { itemPage = Math.floorMod(itemPage + delta, itemPages()); }

  private void changeView(View nextView) {
    if (item == null && selected != null) item = selected.output();
    view = nextView;
    recipePage = 0;
    selected = null;
    materials = craftGuide = options = false;
    rebuildWidgets();
  }

  private void show(String item, View nextView, Recipe preferred) {
    this.item = item;
    view = nextView;
    recipePage = 0;
    selected = preferred;
    materials = craftGuide = options = false;
    setFocused(null);
    rebuildWidgets();
  }

  private void updateResults() {
    pins = helper.settings().pins.stream().map(indexed::get).filter(Objects::nonNull).toList();
    if (item == null && view != View.PINS && !filtered.isEmpty()) item = filtered.getFirst().id();
    results = switch (view) {
      case PINS -> pins;
      case USES -> item == null ? List.of() : indexed.uses(item);
      case RECIPES -> item == null ? List.of() : indexed.produces(helper.recipeLookup(item));
    };
    int known = results.indexOf(selected);
    if (known >= 0) recipePage = known;
    select(Math.clamp(recipePage, 0, Math.max(0, results.size() - 1)));
  }

  private void select(int page) {
    recipePage = page;
    selected = results.isEmpty() ? null : results.get(recipePage);
    boolean present = selected != null;
    prev.active = next.active = results.size() > 1;
    pin.active = materialsButton.active = present;
    if (present) pin.setMessage(Component.literal(helper.pinned(selected) ? "Unpin" : "Pin"));
    if (!present || !helper.canCraft(selected)) craftGuide = false;
    materialsButton.setMessage(Component.literal(materials ? "Recipe" : "Materials"));
    currentCraftButton = null;
    updateCraftButton();
  }

  private void updateCraftButton() {
    var state = helper.craftButton(this, selected, tableParent());
    if (currentCraftButton == state) return;
    currentCraftButton = state;
    craft.active = !options && selected != null && helper.canCraft(selected)
        && (craftGuide || state != CraftButton.LOADING && state != CraftButton.APPLYING);
    craft.setMessage(Component.literal(craftGuide ? "Grid" : switch (state) {
      case CRAFT -> "Craft";
      case OPEN -> "Open";
      case RETRY -> "Retry";
      case LOADING -> "Wait…";
      case APPLYING -> "Apply…";
      case BOOK -> "Book";
      case GUIDE -> "Guide";
    }));
    craft.setTooltip(tip(craftGuide ? "Show the recipe grid" : switch (state) {
      case CRAFT -> helper.serverId(selected) != null
          ? "Fill the crafting grid · Shift-click for max"
          : "Appliquer la recette · Shift-click forwards Shift";
      case OPEN -> "Open the server's crafting menu";
      case RETRY -> "The crafting menu did not open · click to retry";
      case LOADING -> "Waiting for the crafting menu";
      case APPLYING -> "Waiting for the server to apply the recipe";
      case BOOK -> "Open the server book to choose the correct recipe";
      case GUIDE -> "Crafting instructions";
    }));
  }

  private void pageRecipe(int delta) {
    if (results.size() < 2) return;
    selected = null;
    int page = Math.floorMod(recipePage + delta, results.size());
    select(page);
    if (materials) {
      helper.showMaterials(selected);
      checklistScroll = 0;
    }
  }

  private void togglePin() {
    if (selected == null) return;
    helper.togglePin(selected);
    updateResults();
  }

  private void toggleMaterials() {
    if (selected == null) return;
    materials = !materials;
    craftGuide = options = false;
    if (materials) helper.showMaterials(selected);
    checklistScroll = 0;
    rebuildWidgets();
  }

  private void craft(boolean max) {
    if (selected == null || !craft.active) return;
    if (craftGuide) {
      craftGuide = false;
      select(recipePage);
    } else helper.pressCraft(this, selected, tableParent(), max);
    updateCraftButton();
  }

  public void showCraftGuide(Recipe recipe) {
    if (!recipe.equals(selected)) {
      item = recipe.output();
      view = View.RECIPES;
      selected = recipe;
      recipePage = 0;
    }
    materials = options = false;
    craftGuide = true;
    if (craft != null) rebuildWidgets();
  }

  @Override
  public void tick() {
    super.tick();
    if (indexed != helper.catalog()) {
      updateIndex();
      updateResults();
    }
    updateCraftButton();
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (options) return super.keyPressed(event);
    if (getFocused() instanceof EditBox box && box.isFocused() && event.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
      return super.keyPressed(event);
    var action = helper.action(event);
    Hit target = hover != null ? hover : selected == null ? null : new Hit(selected.output(), selected);
    if (action == null || target == null) return super.keyPressed(event);
    Recipe recipe = target.recipe() != null ? target.recipe() : helper.recipeFor(target.item());
    switch (action) {
      case RECIPES -> show(target.item(), View.RECIPES, recipe);
      case USES -> show(target.item(), View.USES, null);
      case PIN -> { if (recipe != null) helper.togglePin(recipe); updateResults(); }
      case MATERIALS -> {
        if (recipe != null) {
          item = recipe.output();
          view = View.RECIPES;
          selected = recipe;
          materials = true;
          craftGuide = false;
          checklistScroll = 0;
          helper.showMaterials(recipe);
          rebuildWidgets();
        }
      }
      case CRAFT -> {
        if (recipe != null && helper.usesExcaliaBook(recipe) && helper.serverId(recipe) == null) {
          show(target.item(), View.RECIPES, recipe);
          helper.prepareCraft(this, recipe, tableParent());
        } else if (recipe != null) helper.craft(recipe, tableParent(), event.hasShiftDown());
      }
    }
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
    double mx = event.x(), my = event.y();
    if (event.button() == 0 && craft.active && craft.isMouseOver(mx, my) && event.hasShiftDown()) {
      lastItemClick = null;
      craft(true);
      return true;
    }
    Hit target = tileAt(mx, my);
    boolean sidebar = target != null;
    if (!sidebar && !options && !craftGuide && !materials && selected != null && event.button() == 0
        && inside(mx, my, x + 7, y + 44, w - 14, h - 70))
      target = hover;
    if (target != null && (event.button() == 0 || event.button() == 1)) {
      long now = System.nanoTime();
      boolean repeat = event.button() == 0 && target.equals(lastItemClick) && now - lastClick < 320_000_000L;
      lastItemClick = repeat || event.button() != 0 ? null : target;
      lastClick = now;
      Recipe recipe = target.recipe() == null ? helper.recipeFor(target.item()) : target.recipe();
      if (repeat && recipe != null) {
        helper.togglePin(recipe);
        updateResults();
      } else if (sidebar) {
        show(target.item(), event.button() == 1 ? View.USES
            : target.recipe() == null ? View.RECIPES : View.PINS, recipe);
        if (event.button() == 0) {
          helper.prepareCraft(this, selected, tableParent());
          updateCraftButton();
        }
      } else if (target.recipe() != null && target.recipe().equals(selected)) {
        helper.prepareCraft(this, selected, tableParent());
        updateCraftButton();
      }
      return true;
    }
    lastItemClick = null;
    return super.mouseClicked(event, twice);
  }

  private Hit tileAt(double mx, double my) {
    if (my < 23 || my >= 23 + itemRows * TILE) return null;
    int row = (int) (my - 23) / TILE, perPage = itemRows * itemCols;
    if (mx >= right && mx < right + itemCols * TILE) {
      int index = itemPage * perPage + row * itemCols + (int) (mx - right) / TILE;
      if (index < filtered.size()) return new Hit(filtered.get(index).id(), null);
    }
    if (mx >= 4 && mx < 4 + leftCols * TILE) {
      int index = pinPage * itemRows * leftCols + row * leftCols + (int) (mx - 4) / TILE;
      if (index < pins.size()) {
        var recipe = pins.get(index);
        return new Hit(recipe.output(), recipe);
      }
    }
    return null;
  }

  @Override
  public boolean mouseScrolled(double mx, double my, double dx, double dy) {
    int delta = -(int) Math.signum(dy);
    if (mx >= right) { itemPage(delta); return true; }
    if (mx < x) {
      int pages = Math.max(1, Math.ceilDiv(pins.size(), leftCols * itemRows));
      pinPage = Math.floorMod(pinPage + delta, pages);
      return true;
    }
    if (materials) {
      int shown = Math.max(1, (h - 120) / 19);
      checklistScroll = Math.clamp(checklistScroll + delta, 0,
          Math.max(0, helper.checklist().materials().size() - shown));
      return true;
    }
    pageRecipe(delta);
    return true;
  }

  private Screen tableParent() {
    Screen candidate = parent;
    while (candidate instanceof RecipeScreen recipes) candidate = recipes.parent;
    return candidate;
  }

  public boolean hasStorageParent() {
    return tableParent() instanceof AbstractContainerScreen<?> container && helper.isStorage(container);
  }

  @Override
  public boolean isPauseScreen() { return false; }

  @Override
  public void onClose() {
    Screen next = helper.closeCraftViewer(this);
    if (next == null) next = parent;
    if (next instanceof AbstractContainerScreen<?> container
        && (minecraft.player == null || minecraft.player.containerMenu != container.getMenu())) next = null;
    minecraft.gui.setScreen(next);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float dt) {
    g.fill(0, 0, width, height, 0x68000000);
    hover = tileAt(mouseX, mouseY);
    drawItems(g, mouseX, mouseY);
    drawPins(g, mouseX, mouseY);
    int rightWidth = itemCols * TILE;
    centered(g, (itemPage + 1) + "/" + itemPages(), right + rightWidth / 2, 8, MUTED);
    Ui.surface(g, x, y, w, h, PANEL, BORDER);
    String title = view == View.PINS ? "Pinned recipes" : selected == null ? "Recipes" : stationLabel(selected);
    centered(g, title, x + w / 2, y + 8, TEXT);
    Ui.rounded(g, x + 24, y + 24, w - 48, 14, 4, CARD);
    centered(g, results.isEmpty() ? "0/0" : (recipePage + 1) + "/" + results.size(),
        x + w / 2, y + 27, MUTED);

    if (options) {
      Ui.surface(g, x + 7, y + 44, w - 14, h - 70, CARD, BORDER);
      centered(g, "Recipe helper settings", x + w / 2, y + 53, ACCENT);
    } else if (materials && helper.materialRecipe() != null) {
      helper.drawChecklist(g, x + 6, y + 62, w - 12, h - 86, checklistScroll);
    } else if (selected != null) {
      if (craftGuide) drawCraftGuide(g);
      else drawRecipe(g, mouseX, mouseY);
    }
    else if (view == View.PINS) {
      centered(g, "No pinned recipes", x + w / 2, y + 71, MUTED);
    } else if (item != null) {
      g.item(helper.icon(item), x + w / 2 - 8, y + 58);
      centered(g, cut(helper.name(item), w - 18), x + w / 2, y + 80, TEXT);
      centered(g, view == View.USES ? "No known uses" : "No known recipe", x + w / 2, y + 93, MUTED);
    }
    super.extractRenderState(g, mouseX, mouseY, dt);
    if (hover != null) {
      g.setTooltipForNextFrame(font, Ui.label(helper.name(hover.item())), mouseX, mouseY, TOOLTIP_STYLE);
      if (helper.inspectInside()) helper.inspectBrowser(g, hover.item(), mouseX, mouseY);
    }
  }

  private void drawCraftGuide(GuiGraphicsExtractor g) {
    boolean excalia = helper.usesExcaliaBook(selected);
    int top = y + 44;
    Ui.surface(g, x + 7, top, w - 14, h - 70, CARD, BORDER);
    var steps = excalia
        ? List.of("Open /cc", "Open the recipe book", "Select the recipe", "Appliquer la recette")
        : helper.serverId(selected) != null
            ? List.of("Open a regular crafting table", "Find this recipe in the helper",
                "Click Place · Shift-click for max")
            : List.of("Open a regular crafting table", "Use the grid to place ingredients");
    centered(g, excalia ? "Excalia recipe book" : "Crafting table", x + w / 2, top + 13, ACCENT);
    for (int i = 0; i < steps.size(); i++)
      centered(g, cut(steps.get(i), w - 26), x + w / 2, top + 32 + i * 16, TEXT);
  }

  private void drawPins(GuiGraphicsExtractor g, int mouseX, int mouseY) {
    int perPage = itemRows * leftCols;
    pinPage = Math.clamp(pinPage, 0, Math.max(0, Math.ceilDiv(pins.size(), perPage) - 1));
    int first = pinPage * perPage;
    for (int index = 0; index < perPage && first + index < pins.size(); index++) {
      Recipe recipe = pins.get(first + index);
      drawTile(g, recipe.output(), 4 + index % leftCols * TILE, 23 + index / leftCols * TILE,
          recipe.equals(selected), mouseX, mouseY);
    }
  }

  private void drawItems(GuiGraphicsExtractor g, int mouseX, int mouseY) {
    int perPage = itemRows * itemCols, first = itemPage * perPage;
    for (int index = 0; index < perPage && first + index < filtered.size(); index++) {
      String id = filtered.get(first + index).id();
      drawTile(g, id, right + index % itemCols * TILE, 23 + index / itemCols * TILE,
          id.equals(item), mouseX, mouseY);
    }
  }

  private void drawTile(GuiGraphicsExtractor g, String id, int left, int top, boolean active, int mx, int my) {
    if (active || inside(mx, my, left, top, TILE, TILE))
      Ui.rounded(g, left, top, TILE, TILE, 4, active ? 0xa4478171 : CARD);
    g.item(helper.icon(id), left + 1, top + 1);
  }

  private String stationLabel(Recipe recipe) {
    return switch (recipe.station()) {
      case VANILLA -> "Crafting";
      case CUSTOM -> "Custom crafting";
      case OTHER -> recipe.note().startsWith("Furnace") ? "Smelting" : "Obtainment";
    };
  }

  private void drawRecipe(GuiGraphicsExtractor g, int mouseX, int mouseY) {
    int top = y + 44;
    int end = y + h - 26;
    var recipe = selected;
    Ui.surface(g, x + 7, top, w - 14, end - top, CARD, BORDER);
    if (recipe.hasGrid()) {
      int size = Math.min(18, Math.min((w - 49) / recipe.size(), (end - top - 20) / recipe.size()));
      if (size < 7) return;
      int left = x + (w - (recipe.size() * size + 33)) / 2;
      int gy = top + Math.max(5, (end - top - 14 - recipe.size() * size) / 2);
      for (int row = 0; row < recipe.size(); row++) for (int col = 0; col < recipe.size(); col++)
        slot(g, left + col * size, gy + row * size, size);
      for (var cell : recipe.cells()) {
        int sx = left + cell.x() * size, sy = gy + cell.y() * size;
        var choices = cell.ingredient().choices();
        String input = choices.get(Math.floorMod(System.currentTimeMillis() / 1500, choices.size()));
        drawIngredient(g, input, cell.ingredient().amount(), sx, sy, size);
        if (inside(mouseX, mouseY, sx, sy, size, size)) hover = new Hit(input, null);
      }
      int arrowX = left + recipe.size() * size + 4, middle = gy + recipe.size() * size / 2;
      g.fill(arrowX, middle - 2, arrowX + 10, middle + 2, ACCENT);
      for (int column = 0; column < 5; column++)
        g.fill(arrowX + 9 + column, middle - 5 + column, arrowX + 10 + column, middle + 6 - column, ACCENT);
      int ox = arrowX + 15, oy = middle - 9;
      slot(g, ox, oy, TILE);
      drawIngredient(g, recipe.output(), recipe.amount(), ox, oy, TILE);
      if (inside(mouseX, mouseY, ox, oy, TILE, TILE)) hover = new Hit(recipe.output(), recipe);
    } else {
      int iy = top + 5;
      for (var req : recipe.ingredients()) {
        if (iy + TILE > end - 12) break;
        String input = req.choices().getFirst();
        slot(g, x + 12, iy, TILE);
        drawIngredient(g, input, req.amount(), x + 12, iy, TILE);
        Ui.text(g, cut(req.amount() + " × " + helper.name(input), w - 47), x + 34, iy + 5, TEXT);
        if (inside(mouseX, mouseY, x + 12, iy, TILE, TILE)) hover = new Hit(input, null);
        iy += TILE;
      }
    }
    String label = helper.name(recipe.output()) + (recipe.note().isBlank() ? "" : " · " + recipe.note());
    centered(g, cut(label, w - 22), x + w / 2, end - 11, MUTED);
  }

  private void drawIngredient(GuiGraphicsExtractor g, String item, int amount, int x, int y, int size) {
    ItemStack icon = helper.icon(item);
    g.pose().pushMatrix();
    g.pose().translate(x + 1, y + 1);
    float scale = Math.min(1, (size - 2) / 16f);
    g.pose().scale(scale, scale);
    g.item(icon, 0, 0);
    if (amount > 1) g.itemDecorations(font, icon, 0, 0, Integer.toString(amount));
    g.pose().popMatrix();
  }

  private static void slot(GuiGraphicsExtractor g, int x, int y, int size) {
    g.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT);
  }

  private static boolean inside(double mx, double my, int x, int y, int w, int h) {
    return mx >= x && mx < x + w && my >= y && my < y + h;
  }

  private void centered(GuiGraphicsExtractor g, String text, int x, int y, int color) {
    Ui.text(g, text, x - Ui.width(text) / 2, y, color);
  }

  private String cut(String text, int width) { return Ui.trim(text, width); }
}
