package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.JobXp;
import dev.asarix.skyblockflow.core.RecipeCatalog.*;
import dev.asarix.skyblockflow.ui.RecipeScreen;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** Keeps the requested server menu behind the browser; only Craft authorizes placement. */
final class RecipePlacement {
  private enum Route { VANILLA, EXCALIA }
  private static final long OPEN_TIMEOUT = TimeUnit.SECONDS.toNanos(5);
  private static final long MENU_SETTLE = TimeUnit.MILLISECONDS.toNanos(350);
  private final RecipeHelper helper;
  private final Minecraft mc = Minecraft.getInstance();
  private RecipeScreen viewer;
  private Recipe expected;
  private String output;
  private Route route;
  private AbstractContainerScreen<?> background;
  private long deadline, menuArrived;
  private int readySlot = -1;
  private boolean waiting, received, applying, failed, contents, intent, maximum, selectedResult;

  RecipePlacement(RecipeHelper helper) { this.helper = helper; }
  private boolean portable() { return mc.getConnection() != null && mc.getConnection().getCommands().getRoot().getChild("craft") != null; }
  private Route route(Recipe recipe, Screen parent) {
    if (recipe == null) return Route.EXCALIA;
    if (!helper.canCraft(recipe)) return null;
    if (recipe.station() == Station.VANILLA && recipe.hasGrid()
        && (portable() || activeTable(parent))) return Route.VANILLA;
    return helper.usesExcaliaBook(recipe) ? Route.EXCALIA : null;
  }
  private boolean active(AbstractContainerScreen<?> screen) {
    return screen != null && mc.player != null && screen.getMenu() == mc.player.containerMenu;
  }
  private boolean activeTable(Screen screen) {
    return screen instanceof AbstractContainerScreen<?> table && active(table) && table.getMenu() instanceof CraftingMenu;
  }
  private boolean emptyCursor() {
    return mc.player != null && mc.gameMode != null && mc.player.isAlive() && mc.player.containerMenu.getCarried().isEmpty();
  }
  private AbstractContainerScreen<?> vanillaTable(RecipeScreen owner, Screen parent) {
    if (viewer == owner && activeTable(background)) return background;
    return activeTable(parent) ? (AbstractContainerScreen<?>)parent : null;
  }
  private boolean covers(RecipeScreen owner, Recipe recipe) {
    String item = recipe == null ? owner.requestedItem() : recipe.output();
    if (item != null) item = helper.recipeLookup(item);
    return viewer == owner && Objects.equals(output,item);
  }
  private boolean canLookup(RecipeScreen owner, Recipe recipe, Screen parent) {
    return helper.onExcalia() && route(recipe,parent) != null && emptyCursor()
        && (recipe != null || owner.requestedItem() != null && !owner.requestedItem().startsWith("minecraft:"));
  }
  void begin(RecipeScreen owner, Recipe recipe, Screen parent) {
    if (!canLookup(owner,recipe,parent) || mc.gui.screen()!=owner || applying) return;
    Route target = route(recipe,parent);
    if (target==Route.VANILLA && vanillaTable(owner,parent)!=null) return;
    if (covers(owner,recipe) && (waiting || active(background)) && !failed) return;
    reset(); viewer=owner; expected=recipe; output=helper.recipeLookup(recipe==null?owner.requestedItem():recipe.output()); route=target;
    open(target==Route.VANILLA ? "craft" : bookCommand(helper.name(output)));
    owner.refreshCraftButton();
  }
  private void open(String command) {
    if (command==null) { failed=true; return; }
    waiting=true; received=contents=failed=false; readySlot=-1;
    deadline=System.nanoTime()+OPEN_TIMEOUT;
    mc.player.connection.sendCommand(command);
  }
  private static String bookCommand(String name) {
    String item=name.replaceAll("[\\p{Cc}\\p{Cf}]"," ").strip();
    return item.isEmpty()||item.length()>120?null:"cc livre "+item;
  }
  boolean capture(Screen current, Screen incoming) {
    if (viewer==null || current!=viewer || !(incoming instanceof AbstractContainerScreen<?> table) || !active(table)) return false;
    if (applying) { reset(); return false; }
    if (!waiting || System.nanoTime()>=deadline) { reset(); return false; }
    boolean valid=route==Route.VANILLA ? table.getMenu() instanceof CraftingMenu
        : table.getMenu() instanceof ChestMenu chest && chest.getRowCount()==6;
    if (!valid) { reset(); return false; }
    background=table; contents=false; received=true; menuArrived=System.nanoTime();
    if (route==Route.VANILLA) waiting=false;
    return true;
  }
  boolean keepViewer(Screen current) { return viewer!=null && current==viewer && (waiting||applying); }
  void tick() {
    if (viewer==null) return;
    if (mc.gui.screen()!=viewer || mc.player==null || !mc.player.isAlive()) { reset(); return; }
    long now=System.nanoTime();
    if (applying) { if (now>=deadline || !active(background)) reveal(); return; }
    if (received && active(background) && route==Route.EXCALIA) {
      readySlot=applySlot(background.getMenu());
      if (readySlot>=0) {
        waiting=false;
        var cells=new ArrayList<Cell>();
        for(int y=0;y<6;y++) for(int x=0;x<6;x++) {
          var stack=background.getMenu().getSlot(y*9+x+1).getItem();
          if(!stack.isEmpty()) cells.add(new Cell(x,y,new Ingredient(helper.key(stack),stack.getCount())));
        }
        expected=helper.learnGrid(expected,output,cells,background.getMenu().getSlot(26).getItem().getCount());
      }
      if(now-menuArrived>=MENU_SETTLE) contents=hasContents(background.getMenu());
    }
    if (intent && received && active(background)) {
      if(route==Route.VANILLA) { placeVanilla(); return; }
      if(readySlot>=0) { apply(); return; }
      if(contents && !selectedResult) {
        int result=resultSlot(background.getMenu());
        if(result>=0) {
          selectedResult=true; contents=false; waiting=true; menuArrived=now; deadline=now+OPEN_TIMEOUT;
          mc.gameMode.handleContainerInput(background.getMenu().containerId,result,0,ContainerInput.PICKUP,mc.player);
          return;
        }
        helper.message("Choose the matching recipe in the crafting menu"); reveal(); return;
      }
    }
    if(waiting && now>=deadline) { waiting=false; failed=true; if(intent) { helper.message("The crafting menu did not return the requested recipe"); reveal(); } }
  }
  private boolean matches(ItemStack stack) {
    if(stack.isEmpty()) return false;
    String id=helper.recipeLookup(helper.key(stack));
    return id.equals(output) || id.startsWith("named:") && normalized(stack.getHoverName().getString()).equals(normalized(helper.name(output)));
  }
  private int applySlot(AbstractContainerMenu menu) {
    if(!(menu instanceof ChestMenu chest) || chest.getRowCount()!=6 || !matches(menu.getSlot(26).getItem())) return -1;
    long variants=helper.catalog().produces(output).stream().filter(Recipe::hasGrid).limit(2).count();
    if(expected!=null && variants>1 && (!expected.hasGrid() || expected.cells().stream().anyMatch(cell->{
      var stack=menu.getSlot(cell.y()*9+cell.x()+1).getItem();
      return stack.getCount()!=cell.ingredient().amount() || !cell.ingredient().choices().contains(helper.key(stack));
    }))) return -1;
    int found=-1;
    for(int i=0;i<54;i++) {
      String label=normalized(menu.getSlot(i).getItem().getHoverName().getString());
      if(!label.equals("appliquer la recette") && !label.startsWith("appliquer la recette (")) continue;
      if(found>=0) return -1; found=i;
    }
    return found;
  }
  private int resultSlot(AbstractContainerMenu menu) {
    int found=-1;
    // The result list occupies three rows below the category tabs.
    for(int i=18;i<45;i++) if(matches(menu.getSlot(i).getItem())) { if(found>=0) return -1; found=i; }
    return found;
  }
  private static String normalized(String name) { return JobXp.normalize(name).replaceAll("^[^\\p{L}\\p{N}]+","").strip(); }
  private static boolean hasContents(AbstractContainerMenu menu) {
    for(int i=0;i<54;i++) if(menu.getSlot(i).hasItem()) return true; return false;
  }
  RecipeHelper.CraftButton button(RecipeScreen owner, Recipe recipe, Screen parent) {
    if(owner==viewer && (applying || intent)) return RecipeHelper.CraftButton.APPLYING;
    if(recipe!=null && recipe.station()==Station.VANILLA && vanillaTable(owner,parent)!=null && emptyCursor())
      return RecipeHelper.CraftButton.CRAFT;
    if(covers(owner,recipe)) {
      if(waiting && !contents) return RecipeHelper.CraftButton.LOADING;
      if(received && active(background) && emptyCursor()) return RecipeHelper.CraftButton.CRAFT;
      if(failed && canLookup(owner,recipe,parent)) return RecipeHelper.CraftButton.RETRY;
    }
    return canLookup(owner,recipe,parent)?RecipeHelper.CraftButton.OPEN:RecipeHelper.CraftButton.GUIDE;
  }
  void press(RecipeScreen owner, Recipe recipe, Screen parent, boolean max) {
    var button=button(owner,recipe,parent);
    if(button==RecipeHelper.CraftButton.GUIDE) { if(recipe!=null) owner.showCraftGuide(recipe); return; }
    if(button==RecipeHelper.CraftButton.APPLYING || button==RecipeHelper.CraftButton.LOADING) return;
    var table=vanillaTable(owner,parent);
    if(recipe!=null && recipe.station()==Station.VANILLA && table!=null && emptyCursor()) {
      reset(); helper.placeVanilla(recipe,table,max); return;
    }
    if(!covers(owner,recipe) || failed) begin(owner,recipe,parent);
    if(viewer!=owner) return;
    intent=true; maximum=max; deadline=System.nanoTime()+OPEN_TIMEOUT;
    tick();
  }
  private void placeVanilla() {
    var table=background; var recipe=expected; boolean max=maximum;
    reset(); helper.placeVanilla(recipe,table,max);
  }
  private void apply() {
    if(!emptyCursor()) { helper.message("Empty the cursor before crafting"); reveal(); return; }
    if(expected!=null && expected.station()==Station.VANILLA && expected.hasGrid() && portable()) {
      route=Route.VANILLA; open("craft"); return;
    }
    applying=true; deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    mc.gameMode.handleContainerInput(background.getMenu().containerId,readySlot,0,
        maximum?ContainerInput.QUICK_MOVE:ContainerInput.PICKUP,mc.player);
  }
  private void reveal() {
    var table=background; reset(); if(active(table)) mc.gui.setScreen(table);
  }
  Screen close(RecipeScreen owner) {
    if(viewer!=owner) return null; var table=active(background)?background:null; reset(); return table;
  }
  void reset() {
    viewer=null; expected=null; output=null; route=null; background=null;
    waiting=received=applying=failed=contents=intent=maximum=selectedResult=false; readySlot=-1;
  }
}
