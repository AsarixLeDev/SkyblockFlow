package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.EnergyPlan.Device;
import java.util.*;
import java.util.regex.*;

/** Conservative recognition of a machine's own labels/model, never a nearby machine or inventory. */
public final class EnergyIdentification {
  public static String plain(String raw) {
    return JobXp.normalize(raw).replaceAll("[_/:-]+", " ").replaceAll("\\s+", " ").strip();
  }
  public static Device device(String raw) {
    String s = plain(raw);
    if (s.matches(".*\\b(cable|prise|card|carte|decor|deco|lune|lunaire|batterie|battery)\\b.*")) return null;
    Device effect = emitter(s);
    if (effect != null) return effect;
    int tier = 1;
    var m = Pattern.compile("(?:\\b(?:t|tier|palier)\\s*|(?:solaire|solar|atmosphere)\\s+)([123])\\b").matcher(s);
    if (m.find()) tier = Integer.parseInt(m.group(1));
    else if (s.matches(".*\\biii\\b.*")) tier = 3;
    else if (s.matches(".*\\bii\\b.*")) tier = 2;
    if (s.contains("compresseur") && s.contains("oxygene")) return Device.OXYGEN;
    if (s.contains("panneau solaire") || s.matches(".*\\bsolar(?: panel)?(?: [123]| i{1,3})?\\b.*")) return Device.values()[tier - 1];
    if (s.contains("atmosphere")) return Device.values()[Device.ATMOSPHERE_I.ordinal() + tier - 1];
    if (s.matches(".*\\b(?:cobble ?grinder|grinder)\\b.*")) return Device.GRINDER;
    if (s.matches(".*\\brecycler\\b.*")) return Device.RECYCLER;
    if (s.contains("extracteur") && s.contains("seve")) return Device.SAP;
    for (var d : List.of(Device.GRINDER, Device.COMPOSTER, Device.DISINTEGRATOR, Device.RECYCLER, Device.CHISELER))
      if (Pattern.compile("\\b" + Pattern.quote(plain(d.title)) + "\\b").matcher(s).find()) return d;
    if (s.contains("nucleaire") || s.contains("nuclear")) {
      if (s.contains("plutonium")) return Device.PLUTONIUM;
      if (s.contains("enrichi") || s.contains("enriched")) return Device.ENRICHED;
      if (s.contains("uranium")) return Device.URANIUM;
      // The same model supports different fuels. Do not invent which rod is installed.
    }
    return null;
  }
  private static Device emitter(String s) {
    if (!s.matches(".*\\b(?:emetteur|emitter)\\b.*")) return null;
    if (s.matches(".*\\b(?:vitesse|speed)\\b.*")) return Device.EMITTER_SPEED;
    if (s.matches(".*\\b(?:saut|jump)\\b.*")) return Device.EMITTER_JUMP;
    if (s.matches(".*\\b(?:force|strength)\\b.*")) return Device.EMITTER_STRENGTH;
    if (s.matches(".*\\b(?:respiration|water breathing)\\b.*")) return Device.EMITTER_WATER_BREATHING;
    if (s.matches(".*\\b(?:dauphin|dolphin)\\b.*")) return Device.EMITTER_DOLPHIN;
    if (s.contains("vision nocturne") || s.contains("night vision")) return Device.EMITTER_NIGHT_VISION;
    if (s.matches(".*\\bbonus (?:de )?vie\\b.*") || s.contains("health boost")) return Device.EMITTER_HEALTH;
    if (s.contains("chute lente") || s.contains("slow falling")) return Device.EMITTER_SLOW_FALLING;
    if (s.contains("regeneration")) return Device.EMITTER_REGENERATION;
    if (s.matches(".*\\bresistance (?:au )?feu\\b.*") || s.contains("fire resistance")) return Device.EMITTER_FIRE_RESISTANCE;
    if (s.matches(".*\\b(?:invisibilite|invisibility)\\b.*")) return Device.EMITTER_INVISIBILITY;
    if (s.contains("saturation")) return Device.EMITTER_SATURATION;
    return null;
  }

  /** Retain unidentified machines, but reject ordinary blocks and cable infrastructure. */
  public static boolean machineCandidate(Collection<String> identity) {
    for (String raw : identity) {
      String s = plain(raw);
      if (s.matches(".*\\b(cable|prise|card|carte|decor|deco|lune|lunaire|batterie|battery)\\b.*")) continue;
      if (device(raw) != null || s.matches(".*\\b(machine|emetteur|emitter|generateur|generator|concasseur|eviscerateur)\\b.*")) return true;
    }
    return false;
  }

  /** null means the server did not disclose an installed/absent battery. */
  public static Boolean battery(Collection<String> lines) {
    Boolean result = null;
    for (String raw : lines) {
      String s = JobXp.normalize(raw).replaceAll("[_:-]+", " ").replaceAll("\\s+", " ");
      if (!s.contains("batterie") && !s.contains("battery")) continue;
      Boolean value = null;
      if (s.matches(".*\\b(aucune|sans|absente|absent|none|no|non|false)\\b.*")
          || s.matches(".*(?:batterie|battery)\\s+installee?\\s+0\\s*$")) value = false;
      else if (s.matches(".*\\b(installee?|installed|presente?|present|oui|true)\\b.*")
          && !s.matches(".*\\b(installer|install|acheter|buy|ajouter|add|requise|required)\\b.*")) value = true;
      if (value == null && !s.matches(".*\\b(installer|install|acheter|buy|ajouter|add|requise|required)\\b.*")
          && (s.matches(".*(?:batterie|battery).*\\b(chargee|charged|en charge|charging)\\b.*")
              || s.matches(".*(?:batterie|battery).*\\d+(?:[.,]\\d+)?\\s*/\\s*[1-9][0-9 ]*.*"))) value = true;
      if (value != null) { if (result != null && result != value) return null; result = value; }
    }
    return result;
  }
  public record MenuItem(int slot, List<String> identity, List<String> status, String model, boolean empty) {}
  public record MenuReading(Device type, Boolean battery, int batterySlot, boolean emitterLevelKnown) {
    MenuReading(Device type, Boolean battery, int batterySlot) { this(type, battery, batterySlot, false); }
  }

  private static final Pattern LEVEL = Pattern.compile("\\b(?:niveau|level)\\s*[:=]?\\s*(iv|iii|ii|i|[1-9][0-9]*)\\b");
  private static final Pattern CHOSEN_LEVEL = Pattern.compile("\\b(?:(?:niveau|level)\\s+(?:choisi|selectionne|actuel|demande|selected|chosen)|(?:selected|chosen)\\s+level)\\s*[:=]?\\s*(iv|iii|ii|i|[0-9]+)\\b");
  private static int levelNumber(String value) {
    return switch (value) { case "i" -> 1; case "ii" -> 2; case "iii" -> 3; case "iv" -> 4;
      default -> { try { yield Integer.parseInt(value); } catch (NumberFormatException ex) { yield 0; } } };
  }

  /** Only a chosen-level status or the server's selected-level button identifies the setting. */
  private static Integer selectedEmitterLevel(List<MenuItem> items) {
    Set<Integer> levels = new HashSet<>();
    for (var item : items) {
      for (String line : item.status()) {
        String s = plain(line);
        if (s.matches(".*\\b(?:clic|cliquez|click|pour|to)\\b.*")) continue;
        var m = CHOSEN_LEVEL.matcher(s);
        while (m.find()) levels.add(levelNumber(m.group(1)));
      }
      String model = plain(item.model());
      if (!model.matches(".*\\bemetteur gui selection\\b.*")) continue;
      for (String name : item.identity()) {
        String s = plain(name);
        var m = LEVEL.matcher(s);
        if (m.find()) levels.add(levelNumber(m.group(1)));
        else {
          m = Pattern.compile("\\b(iv|iii|ii|i|[1-9][0-9]*)$").matcher(s);
          if (m.find()) levels.add(levelNumber(m.group(1)));
        }
      }
    }
    return levels.size() == 1 ? levels.iterator().next() : null;
  }

  /** Menu titles are authoritative; a generic GUI model must not downgrade an explicit tier. */
  public static MenuReading menu(String title, List<MenuItem> items) {
    return menu(title, items, null);
  }

  public static MenuReading menu(String title, List<MenuItem> items, Device blockType) {
    Device found = device(title);
    if (found == null) found = unique(items.stream().map(MenuItem::model)
        .filter(m -> m.contains("gui/")).toList());
    if (found == null && plain(title).matches(".*\\b(nucleaire|nuclear)\\b.*"))
      found = unique(items.stream().flatMap(i -> i.status().stream()).map(t -> "nucleaire " + t).toList());
    boolean emitterMenu = plain(title).matches(".*\\b(?:emetteur|emitter)\\b.*")
        || items.stream().anyMatch(i -> plain(i.model()).matches(".*\\bemetteur gui (?:selection|verrou|on|off)\\b.*"));
    if (found == null && emitterMenu) found = unique(items.stream().flatMap(i -> i.identity().stream()).toList());
    if (found == null && emitterMenu && blockType != null && blockType.emitter()) found = blockType.emitterFamily();
    if (found != null && found.emitter()) {
      Integer level = selectedEmitterLevel(items);
      var selected = level == null ? null : found.emitterAtLevel(level);
      boolean known = found.emitterMaxLevel() == 1 || selected != null;
      return new MenuReading(selected == null ? found : selected, null, -1, known);
    }
    Boolean installed = null; int batterySlot = -1;
    if (found != null && found.solar()) {
      // The panel GUI's battery slot contains the real item, including when its charge is zero.
      // Its model is more reliable than a translated or temporarily stale status label.
      boolean panelLayout = items.stream().anyMatch(i -> i.model().matches(
          "excalia:gui/animated/panneau_solaire(?:_[23])?/tile_[0-2]_(?:on|off)"));
      MenuItem installedItem = null;
      for (var item : items) {
        if (item.empty() || panelLayout && item.slot() != 13) continue;
        int tier = batteryModelTier(item.model());
        if (tier == 0 || batteryInstruction(plain(String.join(" ", item.status())))) continue;
        if (tier != found.ordinal() + 1 || installedItem != null) return new MenuReading(found, null, -1);
        installedItem = item;
      }
      if (installedItem != null) return new MenuReading(found, true, installedItem.slot());
    }
    if (found != null && found.solar()) for (var item : items) {
      Boolean state = battery(item.status());
      String name = plain(String.join(" ", item.identity()));
      String text = plain(String.join(" ", item.status()));
      boolean batteryItem = name.matches(".*\\b(batterie|battery)\\b.*")
          && !name.matches(".*\\b(statut|status)\\b.*");
      boolean instruction = batteryInstruction(text);
      if (state == null && batteryItem && !instruction
          && (text.matches(".*\\bcharge\\s*[=:]?\\s*\\d+(?:[.,]\\d+)?\\s*%.*")
              || text.matches(".*\\b(chargee|charged|charging)\\b.*"))) state = true;
      if (Boolean.TRUE.equals(state) && batteryItem) {
        int tier = name.contains("avancee") || name.contains("advanced") ? 2
            : name.contains("industrielle") || name.contains("industrial") ? 3
            : name.contains("basique") || name.contains("basic") ? 1 : 0;
        if (tier != 0 && tier != found.ordinal() + 1) return new MenuReading(found, null, -1);
        batterySlot = item.slot();
      }
      if (state != null) {
        if (installed != null && installed != state) return new MenuReading(found, null, -1);
        installed = state;
      }
    }
    return new MenuReading(found, installed, batterySlot);
  }

  private static int batteryModelTier(String model) {
    return switch (model) {
      case "oraxen:batterie" -> 1;
      case "oraxen:batterie_avancee" -> 2;
      case "oraxen:batterie_industrielle" -> 3;
      default -> 0;
    };
  }

  private static boolean batteryInstruction(String text) {
    return text.matches(".*\\b(installer|install|acheter|buy|ajouter|add|requise|required|emplacement|slot)\\b.*");
  }

  public static Device unique(Collection<String> candidates) {
    Device result = null;
    for (String text : candidates) {
      var found = device(text);
      if (found != null) { if (result != null && result != found) return null; result = found; }
    }
    return result;
  }
}
