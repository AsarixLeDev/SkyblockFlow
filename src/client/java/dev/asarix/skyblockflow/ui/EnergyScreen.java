package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.EnergyPlan.Device;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class EnergyScreen extends FormScreen {
  private final EnergyPlan draft;
  private Device selected = Device.UNKNOWN;
  private boolean selectedBattery;
  private java.util.Map<String, String> lastInputs = java.util.Map.of();
  private boolean lastFuel;
  private double lastWeather;
  private long nextUpdate, timeBucket = Long.MIN_VALUE;
  private EnergyPlan.Result barResult;
  private String barError = "";

  public EnergyScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Energy planner");
    draft = ConfigStore.copy(app.store.config.tools.energy, EnergyPlan.class);
    app.tools.energy.stop();
    app.tools.energy.rescan(draft);
  }

  protected String description() {
    return "One connected cable network · save on close · Excalia S3";
  }

  protected void buildForm() {
    page("Solar");
    int y = 0;
    for (var d : Device.values())
      if (d.solar()) {
        section(d.title + " · " + (int) d.rate + " UE/s peak", y);
        field(d.name(), "Panels", "" + draft.counts.getOrDefault(d, 0), y + 26);
        field(
            "bat_" + d.name(),
            "Matching batteries (one per panel)",
            "" + draft.batteries.getOrDefault(d, 0),
            y + 74);
        y += 132;
      }
    field("charge", "Initial battery charge (%)", "" + draft.startingCharge, y);
    action(
        "Weather · " + (draft.weather == 1 ? "Clear" : draft.weather == .4 ? "Rain" : "Thunder"),
        0,
        y + 52,
        bodyW,
        () -> {
          draft.weather = draft.weather == 1 ? .4 : draft.weather == .4 ? .15 : 1;
          rebuildWidgets();
        });
    label("Panels need clear sky. Model assumes unobstructed panels.", 0, y + 90);
    page("Machines");
    y = 0;
    for (var d : Device.values())
      if (d.rate <= 0 && !d.emitter() && d != Device.CUSTOM && d != Device.UNKNOWN) {
        field(
            d.name(),
            d.title + " · " + (-d.rate) + " UE/s",
            "" + draft.counts.getOrDefault(d, 0),
            y);
        y += 48;
      }
    field("custom", "Other consumers (total UE/s)", "" + draft.customDemand, y);
    page("Emitters");
    label("Included emitters run at their chosen level. Enter counts below.", 0, 0);
    y = 30;
    for (var family : Device.values()) if (family.emitter() && family.emitterFamily() == family) {
      section(family.emitterTitle(), y);
      int columns = bodyW >= 360 && family.emitterMaxLevel() > 1 ? 2 : 1;
      int fieldWidth = (bodyW - (columns - 1) * 12) / columns;
      for (int level = 1; level <= family.emitterMaxLevel(); level++) {
        var d = family.emitterAtLevel(level);
        field(d.name(), (family.emitterMaxLevel() == 1 ? "Count" : "Level " + level + " count")
            + " · " + Numbers.integer((long)-d.rate) + " UE/s each", "" + draft.counts.getOrDefault(d, 0),
            ((level - 1) % columns) * (fieldWidth + 12), y + 26 + ((level - 1) / columns) * 48, fieldWidth);
      }
      y += 38 + ((family.emitterMaxLevel() + columns - 1) / columns) * 48;
    }
    page("Nuclear");
    y = 0;
    for (var d : Device.values())
      if (d.nuclear()) {
        field(
            d.name(),
            d.title + " · " + (int) d.rate + " UE/s",
            "" + draft.counts.getOrDefault(d, 0),
            y);
        y += 48;
      }
    toggle(
        "Fuel continuously replenished",
        draft.renewableFuel,
        y + 8,
        () -> draft.renewableFuel = !draft.renewableFuel);
    label("Otherwise: one fresh rod per generator, consumed on demand.", 0, y + 46);
    label("Uranium: 30 min · enriched / plutonium: 45 min at full load.", 0, y + 68);
    label("Requires cooling: 8 cold blocks within 2 blocks at full load.", 0, y + 90);
    label("Machines share output; the planner assumes no overheating.", 0, y + 112);
    page("Selector");
    section("Saved machines · " + draft.locations.stream().filter(l -> l.scope.equals(app.tools.energy.scope())).count(), 0);
    label(app.tools.energy.unresolvedCount(draft) + " need attention · pale red = check type, battery or effect level", 0, 26);
    action("Device · " + (selected == Device.UNKNOWN ? "Automatic detection" : selected.title), 0, 56, bodyW, () -> {
      minecraft.gui.setScreen(new ChoiceScreen<>(app, this, "Selection mode", EnergyBlockScreen.choices(true), d -> {
        selected = d; minecraft.gui.setScreen(this);
      }));
    });
    toggle("Update saved machines when opening their menu", draft.autoReadMenus, 90, () -> draft.autoReadMenus = !draft.autoReadMenus);
    toggle("Highlight machine blocks", draft.highlightMachines, 124, () -> draft.highlightMachines = !draft.highlightMachines);
    int selectorY = 162;
    if (selected.solar()) { toggle("Selected solar panel has matching battery", selectedBattery, selectorY, () -> selectedBattery = !selectedBattery); selectorY += 34; }
    if (selected == Device.CUSTOM) { field("selectedDraw", "Selected custom machine draw (UE/s)", "1", selectorY); selectorY += 52; }
    action("Select machines in the world", 0, selectorY, bodyW, () -> {
      read(); double customDraw = selected == Device.CUSTOM ? decimal("selectedDraw", 0, 1e7f) : 1;
      app.store.config.tools.energy = draft;
      if (app.save()) { closeAfterSave(null); app.tools.energy.start(selected, selectedBattery, customDraw); }
    });
    label("Open each panel once to read its battery. The planner remembers it.", 0, selectorY + 36);
    label("Click a red block again to assign or delete it. /skyblockflowenergy returns here.", 0, selectorY + 58);
    action("Rescan saved blocks", 0, selectorY + 90, bodyW, () -> {
      read(); app.tools.energy.rescan(draft); selectionChanged();
      notice = "Scanned loaded blocks · " + app.tools.energy.unresolvedCount(draft) + " need attention"; rebuildWidgets();
    });
    action("Use selected machines to fill counts", 0, selectorY + 124, bodyW, () -> {
      read(); app.tools.energy.fill(draft); selectionChanged();
      notice = "Counts filled from identified machines in this world"; rebuildWidgets();
    });
    int i = 0;
    var locations = draft.locations.stream().filter(l -> l.scope.equals(app.tools.energy.scope()))
        .sorted(java.util.Comparator.comparing(l -> !dev.asarix.skyblockflow.EnergySelector.unresolved(l))).toList();
    for (var l : locations) {
      int row = selectorY + 168 + i++ * 30;
      String state = !l.verified || l.type == Device.UNKNOWN ? "? Unrecognized block"
          : l.displayName() + (l.type.solar() ? !l.batteryKnown ? " · open to check battery" : l.battery ? " · battery ✓" : " · no battery" : "");
      action((l.selected ? "✓ " : "○ ") + state + " · " + l.x + ", " + l.y + ", " + l.z, 0, row, bodyW, () -> {
        read(); app.store.config.tools.energy = draft;
        // Mark this form committed before opening the child so Locate can return directly to play.
        closeAfterSave(new EnergyBlockScreen(app, this, draft, l));
      });
    }
    page("Result");
    try {
      read();
    } catch (RuntimeException invalid) {
      notice = invalid.getMessage();
    }
    action(
        "Calculate with current fields",
        0,
        0,
        bodyW,
        () -> {
          read();
          rebuildWidgets();
        });
    var r = draft.simulate(minecraft.level == null ? 0 : minecraft.level.getOverworldClockTime());
    label("Demand at full efficiency: " + r.demand() + " UE/s", 0, 42);
    label("Solar peak: " + r.peak() + " UE/s · storage: " + r.storage() + " UE", 0, 66);
    label(
        app.tools.energy.unresolvedCount(draft) > 0
            ? "Incomplete · resolve " + app.tools.energy.unresolvedCount(draft) + " red blocks in Selector first"
            : r.sustainable()
            ? "100% efficiency throughout every day and night"
            : r.firstShortage().isEmpty()
                ? "100% for 3 days; stored energy / fuel is not sustainable"
                : "First shortage: " + r.firstShortage(),
        0,
        96);
    label(
        (app.tools.energy.unresolvedCount(draft) > 0 ? "Identified machines only · lowest efficiency: " : "Lowest efficiency: ")
            + String.format(java.util.Locale.ROOT, "%.1f", r.minimumEfficiency() * 100)
            + "%",
        0,
        124);
    label("Clock starts now; fixed weather, connected cables and full machine load.", 0, 156);
    label("Set the initial charge to match your batteries.", 0, 178);
  }

  @Override
  protected int formBottom() {
    return height - 66;
  }

  @Override
  public void tick() {
    super.tick();
    long clock = minecraft.level == null ? 0 : minecraft.level.getOverworldClockTime();
    long now = System.nanoTime();
    long bucket = Math.floorDiv(clock, 400);
    if (now < nextUpdate || lastInputs.equals(values) && lastWeather == draft.weather
        && lastFuel == draft.renewableFuel && timeBucket == bucket) return;
    nextUpdate = now + 250_000_000L;
    lastInputs = java.util.Map.copyOf(values);
    lastWeather = draft.weather;
    lastFuel = draft.renewableFuel;
    timeBucket = bucket;
    try {
      read();
      barResult = draft.simulate(clock);
      barError = "";
    } catch (IllegalArgumentException invalid) {
      barResult = null;
      barError = "Check the planner inputs";
    }
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    super.extractRenderState(g, mx, my, dt);
    int y = height - 54;
    long unresolved = app.tools.energy.unresolvedCount(draft);
    String title;
    String detail = "";
    int color;
    var result = barResult;
    if (result == null) {
      title = barError.isEmpty() ? "Calculating supply…" : barError;
      color = 0xff50647a;
    } else {
      color = switch (result.status()) {
        case ENOUGH -> 0xff215c45;
        case STORAGE -> 0xff854918;
        case DAYTIME_SHORTFALL -> 0xff842e3a;
      };
      title = switch (result.status()) {
        case ENOUGH -> result.demand() == 0 ? "No machine demand" : "Enough energy";
        case STORAGE -> result.minimumNightEfficiency() < .9999 ? "Storage runs out overnight"
            : result.firstShortage().isEmpty() ? "Supply cannot last" : "Storage or fuel runs out";
        case DAYTIME_SHORTFALL -> "Short at full daylight";
      };
      detail = (result.demand() == 0 ? rate(result.fullSunSupply()) + " UE/s available"
          : rate(result.fullSunSupply()) + " supply / " + rate(result.demand()) + " demand · UE/s at noon")
          + (result.firstShortage().isEmpty() ? "" : " · first shortfall " + result.firstShortage());
    }
    if (unresolved > 0) title += " · " + unresolved + " unverified";
    Ui.rounded(g, bodyX - 9, y, bodyW + 18, 20, 5, color);
    String message = title + (detail.isEmpty() ? "" : "  ·  " + detail);
    text(g, cut(message, bodyW + 4), bodyX, y + 6, 0xfff6fbff);
    if (inside(mx, my, bodyX - 9, y, bodyW + 18, 20))
      g.setTooltipForNextFrame(Ui.label(message), mx, my);
  }

  private static String rate(double value) {
    return Numbers.decimal(java.math.BigDecimal.valueOf(value), 0, 1);
  }

  public void selectionChanged() {
    String draw = values.getOrDefault("selectedDraw", "1");
    values.clear(); values.put("selectedDraw", draw);
  }

  private void read() {
    for (var d : Device.values()) {
      if (d == Device.CUSTOM || d == Device.UNKNOWN) continue;
      draft.counts.put(d, (int) number(d.name(), 0, 10000));
      if (d.solar())
        draft.batteries.put(d, (int) number("bat_" + d.name(), 0, draft.counts.get(d)));
    }
    draft.customDemand = decimal("custom", 0, 1e7f);
    draft.startingCharge = decimal("charge", 0, 100);
    draft.normalize();
  }

  protected void commit() {
    read();
    app.store.config.tools.energy = draft;
    app.tools.energy.stop();
    if (app.save()) closeAfterSave();
  }
}
