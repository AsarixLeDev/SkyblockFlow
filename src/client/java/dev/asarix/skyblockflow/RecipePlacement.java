package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.JobXp;
import dev.asarix.skyblockflow.core.RecipeCatalog.Recipe;
import dev.asarix.skyblockflow.core.RecipeCatalog.Station;
import dev.asarix.skyblockflow.ui.RecipeScreen;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;

/** Keeps a requested server menu behind the browser until the player presses Craft. */
final class RecipePlacement {
  private enum Route { VANILLA, EXCALIA }
  private static final long OPEN_TIMEOUT = TimeUnit.SECONDS.toNanos(4);
  private static final long APPLY_TIMEOUT = TimeUnit.SECONDS.toNanos(2);
  private static final long MENU_SETTLE = TimeUnit.MILLISECONDS.toNanos(350);
  private static final int BOOK_OUTPUT = 26;

  private final RecipeHelper helper;
  private final Minecraft mc = Minecraft.getInstance();
  private RecipeScreen viewer;
  private Recipe expected;
  private Route route;
  private AbstractContainerScreen<?> background;
  private long deadline, menuArrived;
  private int readySlot = -1;
  private boolean waiting, received, applying, failed, bookVisible;

  RecipePlacement(RecipeHelper helper) { this.helper = helper; }

  private Route route(Recipe recipe) {
    if (recipe == null || !helper.canCraft(recipe)) return null;
    if (recipe.station() == Station.VANILLA && helper.serverId(recipe) != null) return Route.VANILLA;
    return helper.usesExcaliaBook(recipe) ? Route.EXCALIA : null;
  }

  private boolean active(AbstractContainerScreen<?> screen) {
    return screen != null && mc.player != null && screen.getMenu() == mc.player.containerMenu;
  }

  private boolean emptyCursor() {
    return mc.player != null && mc.gameMode != null && mc.player.isAlive()
        && mc.player.containerMenu.getCarried().isEmpty();
  }

  private AbstractContainerScreen<?> vanillaTable(RecipeScreen owner, Screen parent) {
    if (viewer == owner && active(background) && background.getMenu() instanceof CraftingMenu)
      return background;
    return parent instanceof AbstractContainerScreen<?> table && active(table)
        && table.getMenu() instanceof CraftingMenu ? table : null;
  }

  private boolean covers(RecipeScreen owner, Recipe recipe) {
    return owner == viewer && expected != null && (expected.id().equals(recipe.id())
        || route == Route.VANILLA && route(recipe) == Route.VANILLA);
  }

  private boolean canLookup(Recipe recipe) {
    return helper.onExcalia() && route(recipe) != null && emptyCursor();
  }

  void begin(RecipeScreen owner, Recipe recipe, Screen parent) {
    if (!canLookup(recipe) || mc.gui.screen() != owner || applying) return;
    Route target = route(recipe);
    if (target == Route.VANILLA && vanillaTable(owner, parent) != null) return;
    if (covers(owner, recipe) && (waiting || active(background)) && !failed) return;
    long now = System.nanoTime();
    String command = target == Route.VANILLA ? "craft" : bookCommand(helper.name(recipe.output()));
    if (command == null) return;
    if (viewer != owner) background = null;
    viewer = owner;
    expected = recipe;
    route = target;
    waiting = true;
    received = failed = bookVisible = false;
    readySlot = -1;
    deadline = now + OPEN_TIMEOUT;
    mc.player.connection.sendCommand(command);
  }

  private static String bookCommand(String name) {
    String item = name.replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip();
    return item.isEmpty() || item.length() > 120 ? null : "cc livre " + item;
  }

  boolean capture(Screen current, Screen incoming) {
    if (viewer == null || current != viewer || !(incoming instanceof AbstractContainerScreen<?> table)
        || !active(table)) return false;
    if (applying) {
      reset();
      return false;
    }
    if (!waiting || System.nanoTime() - deadline >= 0) {
      reset();
      return false;
    }
    boolean expectedMenu = route == Route.VANILLA ? table.getMenu() instanceof CraftingMenu
        : table.getMenu() instanceof ChestMenu chest && chest.getRowCount() == 6;
    if (!expectedMenu) {
      reset();
      return false;
    }
    background = table;
    bookVisible = false;
    received = true;
    menuArrived = System.nanoTime();
    if (route == Route.VANILLA) waiting = false;
    return true;
  }

  boolean keepViewer(Screen current) {
    return viewer != null && current == viewer && (waiting || applying);
  }

  void tick() {
    if (viewer == null) return;
    if (mc.gui.screen() != viewer || mc.player == null || !mc.player.isAlive()) {
      reset();
      return;
    }
    long now = System.nanoTime();
    if (applying && (now - deadline >= 0 || !active(background))) {
      if (active(background)) showServerMenu();
      else reset();
    } else if (waiting && received && active(background)
        && (readySlot = applySlot(background.getMenu(), expected)) >= 0) {
      waiting = false;
    } else if (waiting && now - deadline >= 0) {
      waiting = false;
      failed = !active(background);
    } else if (waiting && !bookVisible && received && active(background) && now - menuArrived >= MENU_SETTLE) {
      bookVisible = hasBookContents(background.getMenu());
    }
  }

  // The recipe detail shows its result in the right-hand column. The labelled Apply slot is
  // discovered from server items so search results or unrelated chests cannot be clicked.
  private int applySlot(AbstractContainerMenu menu, Recipe recipe) {
    if (!(menu instanceof ChestMenu chest) || chest.getRowCount() != 6) return -1;
    ItemStack output = menu.getSlot(BOOK_OUTPUT).getItem();
    if (output.isEmpty()) return -1;
    String identity = helper.recipeLookup(helper.key(output));
    if (!identity.equals(recipe.output()) && !(identity.startsWith("named:")
        && normalizedName(output.getHoverName().getString()).equals(normalizedName(helper.name(recipe.output())))))
      return -1;
    long alternatives = helper.catalog().produces(recipe.output()).stream().filter(Recipe::hasGrid).limit(2).count();
    if (alternatives > 1 && (!recipe.hasGrid() || recipe.cells().stream().anyMatch(cell -> {
      var shown = menu.getSlot(cell.y() * 9 + cell.x() + 1).getItem();
      return shown.getCount() != cell.ingredient().amount()
          || !cell.ingredient().choices().contains(helper.key(shown));
    }))) return -1;
    int candidate = -1;
    for (int i = 0; i < 54; i++) {
      ItemStack stack = menu.getSlot(i).getItem();
      if (stack.isEmpty()) continue;
      String label = normalizedName(stack.getHoverName().getString());
      if (!label.equals("appliquer la recette") && !label.startsWith("appliquer la recette (")) continue;
      if (candidate >= 0) return -1;
      candidate = i;
    }
    return candidate;
  }

  private static String normalizedName(String name) {
    return JobXp.normalize(name).replaceAll("^[^\\p{L}\\p{N}]+", "").strip();
  }

  private static boolean hasBookContents(AbstractContainerMenu menu) {
    for (int slot = 0; slot < 54; slot++) if (menu.getSlot(slot).hasItem()) return true;
    return false;
  }

  RecipeHelper.CraftButton button(RecipeScreen owner, Recipe recipe, Screen parent) {
    if (recipe == null) return RecipeHelper.CraftButton.GUIDE;
    if (owner == viewer && applying) return RecipeHelper.CraftButton.APPLYING;
    if (route(recipe) == Route.VANILLA && vanillaTable(owner, parent) != null && emptyCursor())
      return RecipeHelper.CraftButton.CRAFT;
    if (covers(owner, recipe)) {
      if (waiting) return bookVisible && active(background)
          ? RecipeHelper.CraftButton.BOOK : RecipeHelper.CraftButton.LOADING;
      if (received && active(background) && route == Route.EXCALIA) {
        if (emptyCursor() && readySlot >= 0)
          return RecipeHelper.CraftButton.CRAFT;
        return RecipeHelper.CraftButton.BOOK;
      }
      if (failed && canLookup(recipe)) return RecipeHelper.CraftButton.RETRY;
    }
    return canLookup(recipe) ? RecipeHelper.CraftButton.OPEN : RecipeHelper.CraftButton.GUIDE;
  }

  void press(RecipeScreen owner, Recipe recipe, Screen parent, boolean max) {
    switch (button(owner, recipe, parent)) {
      case CRAFT -> {
        if (route(recipe) == Route.VANILLA) {
          var table = vanillaTable(owner, parent);
          reset();
          helper.placeVanilla(recipe, table, max);
        } else if (covers(owner, recipe) && active(background) && emptyCursor()) {
          var menu = background.getMenu();
          int apply = applySlot(menu, recipe);
          if (apply < 0) {
            readySlot = -1;
            return;
          }
          applying = true;
          deadline = System.nanoTime() + APPLY_TIMEOUT;
          mc.gameMode.handleContainerInput(menu.containerId, apply, 0,
              max ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        }
      }
      case OPEN, RETRY -> begin(owner, recipe, parent);
      case BOOK -> showServerMenu();
      case GUIDE -> owner.showCraftGuide(recipe);
      case LOADING, APPLYING -> { }
    }
  }

  private void showServerMenu() {
    var table = background;
    reset();
    if (active(table)) mc.gui.setScreen(table);
  }

  Screen close(RecipeScreen owner) {
    if (viewer != owner) return null;
    var table = active(background) ? background : null;
    reset();
    return table;
  }

  void reset() {
    viewer = null;
    expected = null;
    route = null;
    background = null;
    waiting = received = applying = failed = bookVisible = false;
    readySlot = -1;
  }
}
