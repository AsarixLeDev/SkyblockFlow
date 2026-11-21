package dev.asarix.skyblockflow.core;

import java.util.*;

/** Excalia S3 energy model. Source: wiki energy and patch 3.7 (2026-11-15). */
public final class EnergyPlan {
  public enum Device {
    SOLAR_I("Solar I", 12, 2400),
    SOLAR_II("Solar II", 60, 12000),
    SOLAR_III("Solar III", 300, 60000),
    GRINDER("Broyeur", -2, 0),
    COMPOSTER("Composteur", -2, 0),
    SAP("Extracteur de sève", -2, 0),
    DISINTEGRATOR("Désintégrateur", -2, 0),
    RECYCLER("Recycleur", -1, 0),
    CHISELER("Ciseleur", 0, 0),
    OXYGEN("Compresseur oxygène", -6, 0),
    ATMOSPHERE_I("Atmosphère I", -4, 0),
    ATMOSPHERE_II("Atmosphère II", -8, 0),
    ATMOSPHERE_III("Atmosphère III", -12, 0),
    URANIUM("Nuclear · Uranium", 200, 360000),
    ENRICHED("Nuclear · Enriched uranium", 300, 810000),
    PLUTONIUM("Nuclear · Plutonium", 500, 1350000),
    EMITTER_SPEED("Vitesse I", -20, 0),
    EMITTER_SPEED_II("Vitesse II", -80, 0),
    EMITTER_SPEED_III("Vitesse III", -320, 0),
    EMITTER_SPEED_IV("Vitesse IV", -1280, 0),
    EMITTER_JUMP("Saut amélioré I", -5, 0),
    EMITTER_JUMP_II("Saut amélioré II", -20, 0),
    EMITTER_JUMP_III("Saut amélioré III", -80, 0),
    EMITTER_JUMP_IV("Saut amélioré IV", -320, 0),
    EMITTER_STRENGTH("Force I", -15, 0),
    EMITTER_STRENGTH_II("Force II", -60, 0),
    EMITTER_STRENGTH_III("Force III", -240, 0),
    EMITTER_STRENGTH_IV("Force IV", -960, 0),
    EMITTER_WATER_BREATHING("Respiration aquatique", -25, 0),
    EMITTER_DOLPHIN("Grâce du dauphin", -500, 0),
    EMITTER_NIGHT_VISION("Vision nocturne", -30, 0),
    EMITTER_HEALTH("Bonus de vie I", -40, 0),
    EMITTER_HEALTH_II("Bonus de vie II", -320, 0),
    EMITTER_SLOW_FALLING("Chute lente", -60, 0),
    EMITTER_REGENERATION("Régénération I", -60, 0),
    EMITTER_REGENERATION_II("Régénération II", -480, 0),
    EMITTER_FIRE_RESISTANCE("Résistance au feu", -40, 0),
    EMITTER_INVISIBILITY("Invisibilité", -30, 0),
    EMITTER_SATURATION("Saturation", -400, 0),
    CUSTOM("Custom consumer", -1, 0),
    UNKNOWN("Unrecognized block", 0, 0);
    public final String title;
    public final double rate, capacity;

    Device(String title, double rate, double capacity) {
      this.title = title;
      this.rate = rate;
      this.capacity = capacity;
    }

    public boolean emitter() { return name().startsWith("EMITTER_"); }

    public Device emitterFamily() {
      return switch (this) {
        case EMITTER_SPEED_II, EMITTER_SPEED_III, EMITTER_SPEED_IV -> EMITTER_SPEED;
        case EMITTER_JUMP_II, EMITTER_JUMP_III, EMITTER_JUMP_IV -> EMITTER_JUMP;
        case EMITTER_STRENGTH_II, EMITTER_STRENGTH_III, EMITTER_STRENGTH_IV -> EMITTER_STRENGTH;
        case EMITTER_HEALTH_II -> EMITTER_HEALTH;
        case EMITTER_REGENERATION_II -> EMITTER_REGENERATION;
        default -> this;
      };
    }

    public int emitterLevel() {
      if (name().endsWith("_IV")) return 4;
      if (name().endsWith("_III")) return 3;
      if (name().endsWith("_II")) return 2;
      return 1;
    }

    public int emitterMaxLevel() {
      return switch (emitterFamily()) {
        case EMITTER_SPEED, EMITTER_JUMP, EMITTER_STRENGTH -> 4;
        case EMITTER_HEALTH, EMITTER_REGENERATION -> 2;
        default -> 1;
      };
    }

    public String emitterTitle() { return emitterFamily().title.replaceFirst(" I$", ""); }

    public Device emitterAtLevel(int level) {
      for (var d : values())
        if (d.emitter() && d.emitterFamily() == emitterFamily() && d.emitterLevel() == level) return d;
      return null;
    }

    public boolean solar() {
      return ordinal() < 3;
    }

    public boolean nuclear() {
      return ordinal() >= URANIUM.ordinal() && ordinal() <= PLUTONIUM.ordinal();
    }
  }

  public static final class Located {
    public String scope = "";
    public int x, y, z;
    public Device type = Device.GRINDER;
    public boolean selected = true, battery;
    public boolean verified, manualType, batteryKnown, manualBattery, menuVerified, emitterLevelKnown;
    public String evidence = "", blockObservation = "", menuObservation = "";
    public long observedAt;
    public int batterySlot = -1;
    public double customDraw = 1;

    public boolean needsEmitterLevel() {
      return type.emitter() && type.emitterMaxLevel() > 1 && !emitterLevelKnown;
    }

    public String displayName() {
      return needsEmitterLevel() ? type.emitterTitle() + " · level?" : type.title;
    }

    public String key() {
      return scope + "|" + x + "," + y + "," + z;
    }
  }

  public Map<Device, Integer> counts = new EnumMap<>(Device.class),
      batteries = new EnumMap<>(Device.class);
  public List<Located> locations = new ArrayList<>();
  public double customDemand, weather = 1, startingCharge = 100;
  public boolean renewableFuel = true, autoReadMenus = true, highlightMachines = true;

  public void normalize() {
    if (counts == null) counts = new EnumMap<>(Device.class);
    if (batteries == null) batteries = new EnumMap<>(Device.class);
    if (locations == null) locations = new ArrayList<>();
    locations.removeIf(l -> l == null || l.type == null || l.scope == null);
    if (locations.size() > 2048) locations = new ArrayList<>(locations.subList(0, 2048));
    for (var l : locations) {
      l.customDraw = Double.isFinite(l.customDraw) ? Math.clamp(l.customDraw, 0, 1e7) : 1;
      l.evidence = bounded(l.evidence, 256);
      l.blockObservation = bounded(l.blockObservation, 2048);
      l.menuObservation = bounded(l.menuObservation, 4096);
      l.batterySlot = Math.clamp(l.batterySlot, -1, 255);
    }
    counts.replaceAll((k, v) -> v == null ? 0 : Math.clamp(v, 0, 10000));
    batteries.replaceAll((k, v) -> v == null ? 0 : Math.clamp(v, 0, counts.getOrDefault(k, 0)));
    customDemand = Double.isFinite(customDemand) ? Math.clamp(customDemand, 0, 1e7) : 0;
    weather = Double.isFinite(weather) ? Math.clamp(weather, .15, 1) : 1;
    startingCharge = Double.isFinite(startingCharge) ? Math.clamp(startingCharge, 0, 100) : 100;
  }

  public static String bounded(String text, int limit) {
    return text == null ? "" : text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
  }

  public double demand() {
    double n = customDemand;
    for (var d : Device.values())
      if (d.rate < 0 && d != Device.CUSTOM) n -= counts.getOrDefault(d, 0) * d.rate;
    return n;
  }

  public double peak() {
    return Arrays.stream(Device.values())
        .filter(Device::solar)
        .mapToDouble(d -> counts.getOrDefault(d, 0) * d.rate)
        .sum();
  }

  public double capacity() {
    return Arrays.stream(Device.values())
        .filter(Device::solar)
        .mapToDouble(
            d -> Math.min(counts.getOrDefault(d, 0), batteries.getOrDefault(d, 0)) * d.capacity)
        .sum();
  }

  private static final double SOLAR_AVERAGE =
      java.util.stream.IntStream.range(0, 1200).mapToDouble(second -> sunlight(second * 20L)).average().orElseThrow();

  public static double sunlight(long tick) {
    long t = Math.floorMod(tick, 24000);
    return t >= 12000 ? 0 : Math.max(.25, Math.sin(Math.PI * t / 12000));
  }

  public static String time(long tick) {
    int m = (int) Math.floorMod(tick * 1440 / 24000 + 360, 1440);
    return String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60);
  }

  public record Result(
      double demand,
      double peak,
      double storage,
      double minimumEfficiency,
      String firstShortage,
      boolean sustainable,
      double fullSunSupply,
      double minimumNightEfficiency) {
    public enum Status { ENOUGH, STORAGE, DAYTIME_SHORTFALL }

    public Status status() {
      if (fullSunSupply + 0.000001 < demand) return Status.DAYTIME_SHORTFALL;
      return sustainable ? Status.ENOUGH : Status.STORAGE;
    }
  }

  public Result simulate(long start) {
    normalize();
    double cap = capacity(), charge = cap * startingCharge / 100, load = demand(), min = 1;
    String first = "";
    double nuclear = 0;
    double[] fuel = new double[3], rates = new double[3];
    for (var d : Device.values())
      if (d.nuclear()) {
        int i = d.ordinal() - Device.URANIUM.ordinal();
        rates[i] = d.rate * counts.getOrDefault(d, 0);
        fuel[i] = d.capacity * counts.getOrDefault(d, 0);
        nuclear += rates[i];
      }
    double peakSolar = peak(), nightMin = 1;
    // Integrate the same sunlight curve used by the load simulation.
    double average = peakSolar * SOLAR_AVERAGE * weather + nuclear;
    // One-second integration, three game days: includes an initial partial cycle and steady daily
    // cycle.
    for (int second = 0; second < 3600; second++) {
      long t = start + second * 20L;
      double solar = peakSolar * sunlight(t) * weather;
      double needed = Math.max(0, load - solar), gen = 0;
      // Share the load in proportion to each fueled generator's available output.
      double available = 0;
      for (int i = 0; i < 3; i++)
        available += renewableFuel ? rates[i] : Math.min(rates[i], fuel[i]);
      double fraction = available == 0 ? 0 : Math.min(1, needed / available);
      for (int i = 0; i < 3; i++) {
        double used = (renewableFuel ? rates[i] : Math.min(rates[i], fuel[i])) * fraction;
        gen += used;
        if (!renewableFuel) fuel[i] -= used;
      }
      double net = solar + gen - load;
      if (net >= 0) charge = Math.min(cap, charge + net);
      else {
        double deficit = -net;
        double served = Math.min(deficit, charge);
        charge -= served;
        double efficiency = load == 0 ? 1 : Math.max(0, 1 - (deficit - served) / load);
        min = Math.min(min, efficiency);
        if (Math.floorMod(t, 24000) >= 12000) nightMin = Math.min(nightMin, efficiency);
        if (efficiency < .999999 && first.isEmpty())
          first = time(t) + " (+" + (second / 1200) + " day)";
      }
    }
    return new Result(
        load,
        peakSolar,
        cap,
        min,
        first,
        first.isEmpty()
            && (load == 0 || (renewableFuel || nuclear == 0) && average >= load),
        peakSolar * weather + nuclear,
        nightMin);
  }
}
