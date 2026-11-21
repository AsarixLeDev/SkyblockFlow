package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.EnergyPlan;
import dev.asarix.skyblockflow.core.EnergyPlan.*;
import java.util.*;
import net.minecraft.client.gui.screens.Screen;

/** One saved coordinate, with explicit manual overrides when the server hides its identity. */
public final class EnergyBlockScreen extends FormScreen {
  private final EnergyPlan plan;
  private final Located location;
  public EnergyBlockScreen(SkyblockFlowClient app, Screen parent, EnergyPlan plan, Located location) {
    super(app, parent, location.verified ? location.displayName() : "Identify this block");
    this.plan = plan; this.location = location; app.tools.energy.focus(location);
  }
  @Override protected String heading() { return location.verified ? location.displayName() : "Identify this block"; }
  protected String description() { return "Saved position · " + location.x + ", " + location.y + ", " + location.z; }
  public static List<ChoiceScreen.Choice<Device>> choices(boolean automatic) {
    var result = new ArrayList<ChoiceScreen.Choice<Device>>();
    if (automatic) result.add(new ChoiceScreen.Choice<>("Automatic detection", "Read the block and its machine menu", "Automatic", Device.UNKNOWN));
    for (var d : Device.values()) if (d != Device.UNKNOWN) {
      result.add(new ChoiceScreen.Choice<>(d.title,
          d == Device.CUSTOM ? "Enter its consumption in UE/s" : d.solar() ? (int)d.rate + " UE/s peak"
              : d.nuclear() ? (int)d.rate + " UE/s · choose the installed fuel" : (-d.rate) + " UE/s",
          d.solar() ? "Solar" : d.nuclear() ? "Generators" : d.emitter() ? "Effect emitters" : "Machines", d));
    }
    return result;
  }
  protected void buildForm() {
    section(location.needsEmitterLevel() ? "Choose the effect level" : location.verified ? "Machine identified" : "This block needs your attention", 0);
    label(location.needsEmitterLevel() ? "Open its machine menu, or choose its current level below." : location.verified ? location.evidence.isEmpty() ? "Type selected by you" : location.evidence
        : "Open its machine menu in the world, or choose a type below.", 0, 26);
    action("Machine type · " + (location.verified ? location.type.emitter() ? location.type.emitterTitle() : location.type.title : "Choose…"), 0, 58, bodyW, () -> {
      minecraft.gui.setScreen(new ChoiceScreen<>(app, this, "Choose this machine's type", choices(false), d -> {
        location.type = d; location.verified = location.manualType = true; location.emitterLevelKnown = d.emitter(); location.evidence = "Type selected by you";
        minecraft.gui.setScreen(this);
      }));
    });
    int y = 92;
    if (location.type.solar()) { action("Matching battery · " + (!location.batteryKnown ? "Not checked" : location.battery ? "Installed" : "None"), 0, 92, bodyW, () -> {
      if (!location.batteryKnown) { location.batteryKnown = true; location.battery = true; }
      else if (location.battery) location.battery = false;
      else location.batteryKnown = false;
      location.manualBattery = location.batteryKnown; rebuildWidgets();
    });
      label("Open this panel once to read its battery, or set it manually here.", 0, 124); y = 154;
    }
    if (location.type.emitter()) {
      action("Effect level · " + (location.needsEmitterLevel() ? "Choose…" : location.type.emitterLevel()
          + " · " + dev.asarix.skyblockflow.core.Numbers.integer((long)-location.type.rate) + " UE/s"), 0, y, bodyW, () -> {
        var levels = choices(false).stream().filter(c -> c.value().emitterFamily() == location.type.emitterFamily()).toList();
        minecraft.gui.setScreen(new ChoiceScreen<>(app, this, "Choose the emitter's level", levels, d -> {
          location.type = d; location.emitterLevelKnown = location.manualType = true; location.evidence = "Effect level selected by you";
          minecraft.gui.setScreen(this);
        }));
      });
      label("Use its chosen level. Included emitters are calculated as switched on.", 0, y + 32); y += 64;
    }
    if (location.type == Device.CUSTOM) { field("draw", "Power consumption (UE/s)", "" + location.customDraw, y); y += 52; }
    toggle("Include in calculation", location.selected, y, () -> location.selected = !location.selected);
    action("Locate in world", 0, y + 34, bodyW, () -> {
      saveLocation(); closeAfterSave(null); app.tools.energy.start(Device.UNKNOWN, false); app.tools.energy.focus(location);
    });
    action("Try automatic detection again", 0, y + 68, bodyW, () -> {
      location.manualType = location.verified = location.menuVerified = location.emitterLevelKnown = false; location.batterySlot = -1; location.manualBattery = location.batteryKnown = false;
      app.tools.energy.rescan(plan); rebuildWidgets();
    });
    action("Detected data", 0, y + 102, bodyW, () -> minecraft.gui.setScreen(new EnergyDataScreen(app, this, location)));
    action("Delete this saved block", 0, y + 136, bodyW, () -> {
      plan.locations.remove(location); app.tools.energy.clearFocus(); saveLocation(); closeAfterSave();
    });
  }
  private void saveLocation() {
    if (plan.locations.contains(location) && location.type == Device.CUSTOM) location.customDraw = decimal("draw", 0, 1e7f);
    app.tools.energy.fill(plan); app.store.config.tools.energy = plan;
    if (parent instanceof EnergyScreen screen) screen.selectionChanged();
    if (!app.save()) throw new IllegalStateException("Could not save this machine. Please try again.");
  }
  protected void commit() { saveLocation(); closeAfterSave(); }
}
