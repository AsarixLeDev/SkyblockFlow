package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.EnergyPlan.*;
import dev.asarix.skyblockflow.ui.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

public final class EnergySelector {
  private final SkyblockFlowClient app;
  private boolean active, battery;
  private Device type = Device.UNKNOWN;
  private double customDraw = 1;
  private String selectionNotice = "";
  private long selectionNoticeUntil;
  private BlockPos pending;
  private Device pendingType;
  private long until, nextScan, observedSince, menuOpenedAt;
  private String pendingScope = "";
  private net.minecraft.client.gui.components.Button menuButton;
  private int scanCursor;
  private Object menu, receivedMenu;
  private String menuFingerprint = "", focus = "";
  private final EnergyDetection detection = new EnergyDetection();
  private final SpawnerRangeRenderer renderer = new SpawnerRangeRenderer(), unknownRenderer = new SpawnerRangeRenderer();

  public EnergySelector(SkyblockFlowClient app) {
    this.app = app;
    net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, world, hand, pos, face) -> {
      if (!active || player != Minecraft.getInstance().player) return InteractionResult.PASS;
      var plan = app.store.config.tools.energy;
      var l = find(plan, pos);
      var found = detection.block(pos);
      if ((found == null || !found.machineCandidate()) && (l == null || !l.verified)) {
        if (l != null && l.selected) { l.selected = false; fill(plan); app.save(); }
        selectionNotice = "Not a machine · selection ignored";
        selectionNoticeUntil = System.nanoTime() + 2_000_000_000L;
        return InteractionResult.FAIL;
      }
      if (l != null && l.selected && unresolved(l)) {
        focus(l);
        Minecraft.getInstance().gui.setScreen(new EnergyBlockScreen(app, null, plan, l));
      } else if (l != null) l.selected = !l.selected;
      else {
        if (plan.locations.size() >= 2048) return InteractionResult.FAIL;
        l = new Located(); l.scope = scope(); l.x = pos.getX(); l.y = pos.getY(); l.z = pos.getZ();
        l.type = type; l.verified = type != Device.UNKNOWN; l.manualType = l.verified;
        l.emitterLevelKnown = l.verified && type.emitter();
        l.battery = battery; l.batteryKnown = l.manualBattery = l.verified && type.solar(); l.customDraw = customDraw;
        plan.locations.add(l); scan(l); if (unresolved(l)) focus(l);
      }
      fill(plan); app.save(); return InteractionResult.FAIL;
    });
    net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
      var mc = Minecraft.getInstance();
      if (player == mc.player && world.isClientSide() && hand == net.minecraft.world.InteractionHand.MAIN_HAND
          && mc.gui.screen() == null && (active || app.store.config.tools.energy.autoReadMenus)) {
        var block = detection.block(hit.getBlockPos());
        pendingType = block == null ? null : block.type();
        pending = hit.getBlockPos().immutable(); pendingScope = scope(); until = System.nanoTime() + 10_000_000_000L;
        menu = receivedMenu = null; menuFingerprint = ""; observedSince = menuOpenedAt = 0;
      }
      return InteractionResult.PASS;
    });
    net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
      if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
      var button = net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal("Energy planner"), b -> {
        var location = pending == null ? null : find(app.store.config.tools.energy, pending);
        if (location != null) mc.gui.setScreen(new EnergyBlockScreen(app, null, app.store.config.tools.energy, location));
      }).bounds(Math.max(4, (width - Math.min(300, width - 8)) / 2), 8, Math.min(300, width - 8), 20).build();
      button.visible = false;
      net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(button);
      menuButton = button;
    });
    net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents.END_EXTRACTION.register(c -> {
      var mc = Minecraft.getInstance();
      if (mc.level == null || mc.player == null || !app.store.config.tools.energy.highlightMachines) { renderer.clear(); unknownRenderer.clear(); return; }
      List<Vec3> known = new ArrayList<>(), unknown = new ArrayList<>();
      for (var l : app.store.config.tools.energy.locations) {
        if (!l.selected || !l.scope.equals(scope())) continue;
        if (mc.player.distanceToSqr(l.x + .5, l.y + .5, l.z + .5) > 256 * 256 && !l.key().equals(focus)) continue;
        (unresolved(l) ? unknown : known).add(Vec3.atCenterOf(new BlockPos(l.x, l.y, l.z)));
      }
      renderer.markers(known, 0x79dfbe, false);
      unknownRenderer.markers(unknown, 0xffb3bd, true);
    });
    net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(c -> {
      renderer.submit(c.submitNodeCollector(), c.poseStack(), c.levelState().cameraRenderState.pos);
      unknownRenderer.submit(c.submitNodeCollector(), c.poseStack(), c.levelState().cameraRenderState.pos);
    });
  }
  public String scope() {
    var mc = Minecraft.getInstance();
    return mc.level == null ? "" : (mc.getSingleplayerServer() == null ? app.serverKey()
        : mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize().toString())
        + "|" + mc.level.dimension().identifier();
  }
  public static boolean unresolved(Located l) { return !l.verified || l.type == Device.UNKNOWN || l.type.solar() && !l.batteryKnown || l.needsEmitterLevel(); }
  public void focus(Located l) { focus = l.key(); }
  public void clearFocus() { focus = ""; }
  public void start(Device type, boolean battery) { start(type, battery, 1); }
  public void start(Device type, boolean battery, double customDraw) {
    active = true; this.type = type; this.battery = battery;
    this.customDraw = Double.isFinite(customDraw) ? Math.clamp(customDraw, 0, 1e7) : 1;
    detection.invalidate();
  }
  public void stop() { active = false; clearPending(); clearFocus(); }
  private void clearPending() { pending = null; menu = receivedMenu = null; pendingScope = ""; if (menuButton != null) menuButton.visible = false; }
  private Located find(EnergyPlan plan, BlockPos pos) {
    return plan.locations.stream().filter(l -> l.scope.equals(scope()) && l.x == pos.getX() && l.y == pos.getY() && l.z == pos.getZ()).findFirst().orElse(null);
  }
  public void fill(EnergyPlan plan) {
    plan.counts.clear(); plan.batteries.clear(); plan.customDemand = 0;
    for (var l : plan.locations) if (l.selected && l.scope.equals(scope()) && l.verified && l.type != Device.UNKNOWN) {
      if (l.needsEmitterLevel()) continue;
      plan.counts.merge(l.type, 1, Integer::sum);
      if (l.type == Device.CUSTOM) plan.customDemand += l.customDraw;
      if (l.batteryKnown && l.battery && l.type.solar()) plan.batteries.merge(l.type, 1, Integer::sum);
    }
  }
  public long unresolvedCount(EnergyPlan plan) {
    return plan.locations.stream().filter(l -> l.selected && l.scope.equals(scope()) && unresolved(l)).count();
  }
  public void rescan(EnergyPlan plan) {
    detection.invalidate();
    for (var l : plan.locations) if (l.scope.equals(scope())) scan(l);
    if (plan.locations.stream().anyMatch(l -> l.scope.equals(scope()))) fill(plan);
  }
  private boolean scan(Located l) {
    var found = detection.block(new BlockPos(l.x, l.y, l.z));
    if (found == null) return false; // Unloaded chunks must not erase a previous observation.
    boolean observationChanged = !l.blockObservation.equals(found.observation());
    l.blockObservation = found.observation();
    if (observationChanged) l.observedAt = System.currentTimeMillis();
    var oldType = l.type; boolean oldVerified = l.verified, oldBattery = l.battery, oldKnown = l.batteryKnown;
    if (!l.manualType && !l.menuVerified) {
      // A previously read machine menu remains valid when its model carries no identity.
      l.type = found.type() == null ? l.verified ? l.type : Device.UNKNOWN : found.type();
      l.verified = l.type != Device.UNKNOWN;
      if (found.type() != null || !l.verified) l.evidence = found.evidence();
    }
    if (!l.manualBattery && !l.menuVerified && found.battery() != null) { l.battery = found.battery(); l.batteryKnown = true; }
    if (l.type != oldType) l.emitterLevelKnown = l.type.emitter() && l.type.emitterMaxLevel() == 1;
    if (l.type != oldType && !l.manualBattery && found.battery() == null) l.batteryKnown = false;
    boolean deselected = l.selected && !l.verified && !found.machineCandidate();
    if (deselected) l.selected = false;
    return deselected || observationChanged || oldType != l.type || oldVerified != l.verified || oldBattery != l.battery || oldKnown != l.batteryKnown;
  }
  /** Called after vanilla has applied a complete inventory snapshot to this exact open menu. */
  public void menuContent(int id) {
    var mc = Minecraft.getInstance();
    if (pending == null || mc.player == null || id <= 0
        || !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)
        || id != s.getMenu().containerId || mc.player.containerMenu != s.getMenu()) return;
    receivedMenu = s.getMenu();
    tick(mc);
  }

  /** Follow the server's slot changes; player-inventory slots cannot alter a machine reading. */
  public void menuSlot(int id, int index) {
    var mc = Minecraft.getInstance();
    if (pending == null || mc.player == null || id <= 0
        || !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)
        || id != s.getMenu().containerId || receivedMenu != s.getMenu() || mc.player.containerMenu != s.getMenu()
        || index < 0 || index >= s.getMenu().slots.size()
        || s.getMenu().getSlot(index).container == mc.player.getInventory()) return;
    tick(mc);
  }

  public void tick(Minecraft mc) {
    if (mc.level == null || mc.player == null) { stop(); clearFocus(); return; }
    var plan = app.store.config.tools.energy;
    if (active && mc.gui.screen() == null && System.nanoTime() >= nextScan && !plan.locations.isEmpty()) {
      nextScan = System.nanoTime() + 250_000_000L;
      boolean changed = false;
      for (int i = 0; i < Math.min(4, plan.locations.size()); i++) {
        var l = plan.locations.get(Math.floorMod(scanCursor++, plan.locations.size()));
        if (l.scope.equals(scope()) && !l.verified) changed |= scan(l);
      }
      if (changed) { fill(plan); app.save(); }
    }
    if (pending == null) return;
    if ((!active && !plan.autoReadMenus) || !pendingScope.equals(scope()) || menu == null && System.nanoTime() > until) { clearPending(); return; }
    if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s) {
      if (s.getMenu().containerId == 0 || menu != null && menu != s) { clearPending(); return; }
      if (menu == null) { menu = s; menuOpenedAt = System.nanoTime(); }
      List<EnergyIdentification.MenuItem> items = new ArrayList<>();
      StringBuilder observation = new StringBuilder("Menu: " + s.getTitle().getString());
      for (int i = 0; i < Math.min(256, s.getMenu().slots.size()); i++) {
        var slot = s.getMenu().slots.get(i);
        if (slot.container == mc.player.getInventory()) continue;
        var item = slot.getItem();
        var model = item.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
        List<String> identity = new ArrayList<>(), status = new ArrayList<>();
        EnergyDetection.item(item, identity, status);
        items.add(new EnergyIdentification.MenuItem(i, identity, status, model == null ? "" : model.toString(), item.isEmpty()));
        if (!item.isEmpty() && observation.length() < 4096) observation.append("\nSlot ").append(i).append(": ")
            .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem())).append("\n  ")
            .append(String.join(" | ", identity)).append("\n  ").append(String.join(" | ", status));
      }
      String fingerprint = EnergyPlan.bounded(observation.toString(), 4096);
      var reading = EnergyIdentification.menu(s.getTitle().getString(), items, pendingType);
      String signature = reading.toString() + items.stream().filter(EnergyIdentification.MenuItem::empty).map(EnergyIdentification.MenuItem::slot).toList();
      if (!signature.equals(menuFingerprint)) { menuFingerprint = signature; observedSince = System.nanoTime(); }
      // A complete snapshot is usable immediately, even if the player closes the menu this tick.
      boolean completeContents = receivedMenu == s.getMenu();
      if (!completeContents && (System.nanoTime() - menuOpenedAt < 500_000_000L
          || System.nanoTime() - observedSince < 200_000_000L)) return;
      var l = find(plan, pending);
      if (l == null) {
        if (!active || reading.type() == null || plan.locations.size() >= 2048) return;
        l = new Located(); l.type = Device.UNKNOWN; l.scope = scope(); l.x = pending.getX(); l.y = pending.getY(); l.z = pending.getZ(); plan.locations.add(l);
      }
      var found = reading.type();
      if (found != null && found.emitter() && !reading.emitterLevelKnown()
          && l.verified && l.emitterLevelKnown && l.type.emitterFamily() == found.emitterFamily()) found = l.type;
      var hasBattery = reading.battery();
      if (completeContents && found != null && found.solar() && l.type == found && l.batterySlot >= 0) {
        int previousSlot = l.batterySlot;
        if (items.stream().anyMatch(i -> i.slot() == previousSlot && i.empty())) hasBattery = false;
      }
      boolean changed = !fingerprint.equals(l.menuObservation) && System.currentTimeMillis() - l.observedAt >= 2000 || l.menuObservation.isEmpty() || found != null && (l.type != found || !l.verified)
          || hasBattery != null && (!l.batteryKnown || l.battery != hasBattery)
          || completeContents && found != null && found.solar() && l.batteryKnown && hasBattery == null
          || reading.emitterLevelKnown() && !l.emitterLevelKnown;
      if (changed) {
        l.menuObservation = fingerprint;
        scan(l);
        l.observedAt = System.currentTimeMillis();
        if (found != null) {
          // A fresh server menu supersedes an earlier guess or manual assignment.
          boolean changedType = l.type != found;
          l.type = found; l.verified = l.menuVerified = true; l.manualType = false; l.evidence = "Updated from this machine's menu";
          if (changedType) { l.batteryKnown = false; l.batterySlot = -1; l.emitterLevelKnown = false; }
          if (found.emitter() && reading.emitterLevelKnown()) l.emitterLevelKnown = true;
          if (found.solar()) {
            l.manualBattery = false; l.batteryKnown = hasBattery != null;
            if (hasBattery != null) l.battery = hasBattery;
            if (reading.batterySlot() >= 0) l.batterySlot = reading.batterySlot();
          } else { l.battery = l.batteryKnown = l.manualBattery = false; l.batterySlot = -1; }
        }
        fill(plan); app.save();
      }
      if (menuButton != null) {
        menuButton.visible = true;
        String state = !l.verified ? "Identify this block" : l.displayName() + (l.type.solar() ? !l.batteryKnown ? " · battery not identified" : l.battery ? " · battery installed" : " · no battery" : "");
        menuButton.setMessage(DisplayText.literal(Ui.trim(state + " · Planner", menuButton.getWidth() - 12)));
      }
    } else if (menu != null) clearPending();
  }

  public static Device detect(String title) { return EnergyIdentification.device(title); }
  public void renderLabels(net.minecraft.client.gui.GuiGraphicsExtractor g) {
    var mc = Minecraft.getInstance(); if (!active || mc.player == null) return;
    for (var l : app.store.config.tools.energy.locations) if (l.key().equals(focus) && l.scope.equals(scope())) {
      double distance = Math.sqrt(mc.player.distanceToSqr(l.x + .5, l.y + .5, l.z + .5));
      int[] at = WaypointRenderer.project(l.x + .5, l.y + 1.4, l.z + .5, g.guiWidth(), g.guiHeight());
      String label = (l.verified ? l.displayName() : "Unrecognized block") + " · " + Numbers.integer(Math.round(distance)) + " m";
      label = Ui.trim(label, g.guiWidth() - 32);
      int w = Ui.width(label), x = at == null ? (g.guiWidth() - w) / 2 : Math.clamp(at[0] - w / 2, 12, g.guiWidth() - w - 12);
      int y = at == null ? 36 : Math.clamp(at[1], 26, g.guiHeight() - 80);
      g.fill(x - 7, y - 6, x + w + 7, y + 15, 0xeb261c28);
      g.fill(x - 7, y - 6, x - 5, y + 15, 0xffffb3bd);
      Ui.text(g, label, x, y, 0xffffd1d8);
      break;
    }
    String hint = System.nanoTime() < selectionNoticeUntil ? selectionNotice
        : "Select machines: left-click · Inspect red: click again · Read menu: right-click";
    String mode = "Machine selector · " + (type == Device.UNKNOWN ? "Automatic" : type.title) + " · /skyblockflowenergy to finish";
    int w = Math.min(g.guiWidth() - 24, Math.max(Ui.width(hint), Ui.width(mode))), x = (g.guiWidth() - w) / 2, y = g.guiHeight() - 85;
    g.fill(x - 6, y - 6, x + w + 6, y + 28, 0xed17212b);
    Ui.text(g, Ui.trim(hint, w), x, y, 0xffffb3bd);
    Ui.text(g, Ui.trim(mode, w), x, y + 14, 0xff79dfbe);
  }
}
