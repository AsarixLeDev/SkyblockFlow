package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.CraftingGridPlan;
import dev.asarix.skyblockflow.core.RecipeCatalog.Recipe;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;

/** A bounded, user-requested grid fill for recipes absent from the server recipe book. */
final class VanillaPlacement {
  private record Click(int slot, int button, ItemStack before, ItemStack cursor) {}
  private final RecipeHelper helper;
  private final Minecraft mc = Minecraft.getInstance();
  private final ArrayDeque<Click> clicks = new ArrayDeque<>();
  private AbstractContainerScreen<?> screen;
  private Recipe recipe;
  private boolean max, planned;
  private long deadline, mismatchSince;
  VanillaPlacement(RecipeHelper helper) { this.helper = helper; }
  void reset() { screen = null; recipe = null; clicks.clear(); }
  void start(Recipe recipe, AbstractContainerScreen<?> screen, boolean max) {
    reset(); mismatchSince = 0; this.screen = screen; this.recipe = recipe; this.max = max; planned = false;
    deadline = System.currentTimeMillis()+35_000;
  }
  private void stop(String message) { reset(); helper.message(message); }
  void tick() {
    if (screen == null) return;
    if (mc.player == null || mc.gameMode == null || mc.gui.screen() != screen
        || mc.player.containerMenu != screen.getMenu() || !(screen.getMenu() instanceof CraftingMenu)) {
      reset(); return;
    }
    var menu = screen.getMenu();
    if (System.currentTimeMillis() > deadline) { stop("Crafting stopped · check the table and cursor"); return; }
    if (!planned) {
      if (!menu.getCarried().isEmpty()) { stop("Empty the cursor before filling the table"); return; }
      // Clear existing ingredients into inventory. Never use an outside/drop slot.
      for (int i = 1; i <= 9; i++) if (menu.getSlot(i).hasItem()) {
        mc.gameMode.handleContainerInput(menu.containerId,i,0,ContainerInput.QUICK_MOVE,mc.player);
        if (menu.getSlot(i).hasItem()) stop("Make room in your inventory before filling the table");
        return;
      }
      var stacks = new ArrayList<CraftingGridPlan.Stack>();
      var virtual = new HashMap<Integer,ItemStack>();
      for (int i=1;i<menu.slots.size();i++) {
        var stack = menu.getSlot(i).getItem(); virtual.put(i,stack.copy());
        if (i>=10 && !stack.isEmpty()) stacks.add(new CraftingGridPlan.Stack(helper.key(stack),stack.getCount(),stack.getMaxStackSize()));
      }
      var plan = CraftingGridPlan.plan(recipe,stacks,max);
      if (plan.isEmpty()) { stop("Not enough ingredients to fill this recipe"); return; }
      for (var target : plan.get()) {
        int remaining = target.amount();
        for (int source=10;source<menu.slots.size() && remaining>0;source++) {
          var supply = virtual.get(source);
          if (supply.isEmpty() || !helper.key(supply).equals(target.item())) continue;
          var existing = virtual.get(target.slot());
          if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing,supply)) continue;
          int take = Math.min(remaining,supply.getCount());
          clicks.add(new Click(source,0,supply.copy(),ItemStack.EMPTY));
          var cursor = supply.copy(); virtual.put(source,ItemStack.EMPTY);
          if (take == supply.getCount()) {
            clicks.add(new Click(target.slot(),0,existing.copy(),cursor.copy()));
            virtual.put(target.slot(),supply.copyWithCount(existing.getCount()+take));
          } else {
            for (int i=0;i<take;i++) {
              clicks.add(new Click(target.slot(),1,existing.copy(),cursor.copy()));
              existing = supply.copyWithCount(existing.getCount()+1); cursor.shrink(1);
            }
            virtual.put(target.slot(),existing);
            clicks.add(new Click(source,0,ItemStack.EMPTY,cursor.copy())); virtual.put(source,cursor.copy());
          }
          remaining -= take;
        }
        if (remaining != 0) { stop("These ingredient stacks cannot be combined · fill this recipe manually"); return; }
      }
      if (clicks.size()>600) { stop("Use the server crafting menu for this recipe"); return; }
      planned = true;
    }
    // Vanilla predicts every click; verify that neither the player nor a server correction changed its inputs.
    for (int n=0;n<2 && !clicks.isEmpty();n++) {
      var click = clicks.getFirst();
      if (!ItemStack.matches(menu.getSlot(click.slot).getItem(),click.before)
          || !ItemStack.matches(menu.getCarried(),click.cursor)) {
        // A response to an earlier click can briefly precede the latest inventory update.
        long now = System.currentTimeMillis();
        if (mismatchSince == 0) mismatchSince = now;
        else if (now - mismatchSince >= 1500) stop("Crafting stopped because the inventory changed · check the table and cursor");
        return;
      }
      mismatchSince = 0; clicks.removeFirst();
      mc.gameMode.handleContainerInput(menu.containerId,click.slot,click.button,ContainerInput.PICKUP,mc.player);
    }
    if (clicks.isEmpty()) stop("Crafting grid filled");
  }
}
