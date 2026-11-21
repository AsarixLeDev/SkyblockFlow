package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Reads only server menu data; never equips or feeds a pet automatically. */
public final class PetService {
  private final SkyblockFlowClient app;
  public PetTracker tracker = new PetTracker();
  private PetStore store;
  private JobTracker xpReadings = new JobTracker();
  private Object menu;
  private long revision = -1;
  private int loadedMenu = -1;
  private long menuFood = -1;
  private String menuPet = "";
  private boolean dirty, hidden;
  private String hiddenIdentity = "";
  private long lastSave;
  private Object iconLevel;
  private String decodedIcon = "";
  private ItemStack petIcon = ItemStack.EMPTY;
  public String status = "Open /pets to load your pet";
  public final List<String> diagnostic = new ArrayList<>();

  public PetService(SkyblockFlowClient app) {
    this.app = app;
  }

  public void connect() {
    flush();
    store =
        new PetStore(
            app.store.directory(),
            app.serverKey(),
            Minecraft.getInstance().getUser().getProfileId().toString());
    tracker = new PetTracker();
    xpReadings = new JobTracker();
    try {
      tracker.data = store.load();
      // Saved snapshots contain remaining online time, never elapsed time while offline.
      // Older caches migrate using their last known remaining value until /pets refreshes it.
      tracker.data.foodPaused = true;
      tracker.data.resumeFood(System.currentTimeMillis());
    } catch (java.io.IOException e) {
      app.error("Pet tracker: " + e.getMessage());
    }
    menu = null;
    loadedMenu = -1;
    revision = -1;
    hidden = false;
    status =
        tracker.data.state == PetTracker.State.UNKNOWN
            ? "Open /pets to load your pet"
            : "Open /pets to refresh";
  }

  public void disconnect() {
    tracker.data.pauseFood(System.currentTimeMillis());
    dirty = true;
    flush();
    store = null;
    menu = null;
    loadedMenu = -1;
    diagnostic.clear();
    iconLevel = null;
    decodedIcon = "";
    petIcon = ItemStack.EMPTY;
  }

  public void flush() {
    if (!dirty || store == null) return;
    try {
      var checkpoint = ConfigStore.copy(tracker.data, PetTracker.Snapshot.class);
      checkpoint.pauseFood(System.currentTimeMillis());
      store.save(checkpoint);
      dirty = false;
    } catch (java.io.IOException e) {
      app.error("Could not save pet tracker: " + e.getMessage());
    }
    lastSave = System.currentTimeMillis();
  }

  public void menuContent(int id) {
    loadedMenu = id;
  }

  public void message(String text) {
    long now = System.currentTimeMillis();
    if (tracker.hungryMessage(text, now) || tracker.feedingMessage(text, now)) {
      dirty = true;
      status = "Pet updated";
      flush();
    }
  }

  public void jobReading(JobXp.Reading reading) {
    if (!app.store.config.enabled(Feature.PETS)) return;
    var before = xpReadings.data.jobs.get(reading.job());
    var session = before == null ? null : before.active;
    double xp = session == null ? 0 : session.xp;
    xpReadings.observe(reading, System.currentTimeMillis(), app.store.config.jobs.timeoutMs);
    var after = xpReadings.data.jobs.get(reading.job());
    if (after != null && after.active != null) {
      double gained = after.active.xp - (session == after.active ? xp : 0);
      if (gained > 0) onJobGain(reading.job(), gained);
    }
  }

  public void onJobGain(String job, double gain) {
    if (!app.store.config.enabled(Feature.PETS) || !app.alertsAllowed()) return;
    if (tracker.gain(job, gain, System.currentTimeMillis(), app.store.config.pets))
      app.notifyGeneral(
          "Pets",
          "Your pet is hungry",
          tracker.data.name + " · " + JobXp.name(job) + " XP bonus inactive · open /pets to feed",
          new SoundSettings());
  }

  public void hideUntilEquipped() {
    hidden = true;
    hiddenIdentity = tracker.data.identity();
  }

  public boolean hidden() {
    return hidden;
  }

  public void tick() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) {
      if (store != null) disconnect();
      return;
    }
    if (store == null) connect();
    if (System.currentTimeMillis() - lastSave > 5000) {
      dirty = true;
      flush();
    }
    // Job correction still needs the equipped-pet reading when its overlay is disabled.
    if (!app.store.config.enabled(Feature.PETS) && !app.store.config.enabled(Feature.JOBS)) return;
    if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
      var container = screen.getMenu();
      if (menu != container) {
        menu = container;
        revision = -1;
        menuFood = -1;
        menuPet = "";
      }
      if (revision != app.inventoryRevision && loadedMenu == container.containerId) {
        revision = app.inventoryRevision;
        String title = screen.getTitle().getString();
        boolean main = PetTracker.mainMenu(title), feeding = PetTracker.feedMenu(title);
        if ((main || feeding) && container.slots.size() >= 9 + 36) {
          var stack = container.getSlot(4).getItem();
          List<String> lore = lore(stack);
          // The main menu's Nourrir button also stores saturation in its lore.
          // Read food lines only, so reserve pets cannot replace the active pet's stats.
          for (int i = 0; i < Math.min(18, container.slots.size() - 36); i++) {
            if (i == 4) continue;
            var info = container.getSlot(i).getItem();
            String name = info.getHoverName().getString();
            List<String> infoLore = lore(info);
            if (PetTracker.foodLine(name)) lore.add(name + " " + String.join(" ", infoLore));
            else for (String line : infoLore) if (PetTracker.foodLine(line)) lore.add(line);
          }
          diagnostic.clear();
          diagnostic.add("Menu: " + title);
          diagnostic.add("Active slot 4: " + stack.getHoverName().getString());
          diagnostic.addAll(lore);
          PetTracker.Snapshot reading = PetTracker.parse(stack.getHoverName().getString(), lore);
          if (main && reading == null) {
            String name = JobXp.normalize(stack.getHoverName().getString());
            // Only a fully received main menu can establish that no pet is equipped.
            if (stack.isEmpty()
                || name.contains("aucun")
                || name.contains("pas de pet")
                || name.contains("equiper un pet")) {
              reading = new PetTracker.Snapshot();
              reading.state = PetTracker.State.NONE;
            }
          }
          if (reading != null && (main || reading.type.equals(tracker.data.type))) {
            if (main && reading.state == PetTracker.State.EQUIPPED)
              reading.iconItemJson = captureIcon(stack);
            // Inventory updates can arrive before the feed button refresh. Do not let an
            // unchanged menu value undo a newer feeding message or restart the countdown.
            if (!reading.identity().equals(menuPet)) {
              menuPet = reading.identity();
              menuFood = -1;
            }
            if (reading.foodSeconds >= 0) {
              if (reading.foodSeconds == menuFood) reading.foodSeconds = -1;
              else menuFood = reading.foodSeconds;
            }
            String before = tracker.data.identity();
            tracker.observe(reading, System.currentTimeMillis());
            dirty = true;
            if (hidden
                && reading.state == PetTracker.State.EQUIPPED
                && (!before.equals(reading.identity())
                    || !hiddenIdentity.equals(reading.identity()))) hidden = false;
            // Remember unequip so re-equipping the same pet restores a hidden panel.
            if (reading.state != PetTracker.State.EQUIPPED) hiddenIdentity = reading.identity();
            status = "Pet updated";
            flush();
          } else if (main) status = "Could not read pet · reopen /pets";
        }
      }
    } else {
      menu = null;
      loadedMenu = -1;
    }
    if (dirty && System.currentTimeMillis() - lastSave > 5000) flush();
  }

  private static String captureIcon(ItemStack stack) {
    var mc = Minecraft.getInstance();
    if (mc.level == null || stack.isEmpty()) return "";
    try {
      var visual = stack.copyWithCount(1);
      visual.remove(DataComponents.LORE);
      var ops =
          net.minecraft.resources.RegistryOps.create(
              com.mojang.serialization.JsonOps.INSTANCE, mc.level.registryAccess());
      String json = ItemStack.CODEC.encodeStart(ops, visual).getOrThrow().toString();
      return json.length() <= PetTracker.MAX_ICON_LENGTH ? json : "";
    } catch (RuntimeException unavailable) {
      return "";
    }
  }

  public ItemStack icon() {
    var mc = Minecraft.getInstance();
    String json = tracker.data.iconItemJson;
    if (mc.level == null
        || tracker.data.state != PetTracker.State.EQUIPPED
        || json == null
        || json.isEmpty()
        || json.length() > PetTracker.MAX_ICON_LENGTH) return ItemStack.EMPTY;
    if (iconLevel != mc.level || !json.equals(decodedIcon)) {
      iconLevel = mc.level;
      decodedIcon = json;
      petIcon = ItemStack.EMPTY;
      try {
        var ops =
            net.minecraft.resources.RegistryOps.create(
                com.mojang.serialization.JsonOps.INSTANCE, mc.level.registryAccess());
        petIcon =
            ItemStack.CODEC
                .parse(ops, com.google.gson.JsonParser.parseString(json))
                .getOrThrow()
                .copyWithCount(1);
      } catch (RuntimeException unavailable) {
        // An old icon must not prevent the rest of the tracker from rendering.
      }
    }
    return petIcon;
  }

  private static List<String> lore(ItemStack stack) {
    var lore = stack.get(DataComponents.LORE);
    List<String> lines = new ArrayList<>();
    if (lore != null) for (var line : lore.lines()) lines.add(line.getString());
    return lines;
  }

  public record Food(ItemStack icon, int count, int secondsEach) {}

  public List<Food> foods() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || tracker.data.state != PetTracker.State.EQUIPPED) return List.of();
    Map<String, Food> found = new LinkedHashMap<>();
    for (int i = 0; i < 41; i++) {
      if (i >= 36 && i != 40) continue;
      ItemStack stack = mc.player.getInventory().getItem(i);
      if (stack.isEmpty()) continue;
      int seconds = foodSeconds(tracker.data.type, stack);
      if (seconds == 0) continue;
      String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + "#" + seconds;
      Food old = found.get(id);
      found.put(
          id,
          new Food(
              stack.copyWithCount(1), stack.getCount() + (old == null ? 0 : old.count()), seconds));
    }
    return found.values().stream()
        .sorted(Comparator.comparingInt(Food::secondsEach).reversed())
        .toList();
  }

  public static int foodSeconds(String type, ItemStack stack) {
    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    // Oraxen custom food-lookalikes are rejected; only the server's croquette is special.
    var custom = stack.get(DataComponents.CUSTOM_DATA);
    var model = stack.get(DataComponents.ITEM_MODEL);
    if (model != null && model.toString().equals("oraxen:croquette_pet")) return 14400;
    boolean changedModel =
        !Objects.equals(model, stack.getItem().getDefaultInstance().get(DataComponents.ITEM_MODEL));
    if (custom != null || stack.has(DataComponents.CUSTOM_MODEL_DATA) || changedModel) {
      String name = JobXp.normalize(stack.getHoverName().getString());
      return custom != null
              && name.contains("croquette")
              && name.contains("pet")
              && custom.copyTag().toString().toLowerCase(Locale.ROOT).contains("croquette")
          ? 14400
          : 0;
    }
    for (var food : PetCatalog.foods(type))
      if (id.equals("minecraft:" + food.item())) return food.seconds();
    return 0;
  }
}
