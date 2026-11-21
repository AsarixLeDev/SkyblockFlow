package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import dev.asarix.skyblockflow.ui.Ui;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class PetOverlay {
  private static final int PADDING = 12, PROGRESS_WIDTH = 125;
  public static JobOverlay.Bounds render(
      SkyblockFlowClient app, GuiGraphicsExtractor g, PetSettings settings, boolean preview) {
    return render(app, g, settings, preview, true);
  }

  public static JobOverlay.Bounds render(
      SkyblockFlowClient app,
      GuiGraphicsExtractor g,
      PetSettings settings,
      boolean preview,
      boolean draw) {
    if (app.celebrations != null && app.celebrations.hideOverlays()) return null;
    var pet = app.pets.tracker.data;
    if (!preview
        && (Minecraft.getInstance().player == null
            || !settings.showOverlay
            || !app.store.config.enabled(Feature.PETS)
            || !app.store.config.visible(Feature.PETS)
            || app.pets.hidden()
            || settings.hideNoData && pet.state == PetTracker.State.UNKNOWN
            || settings.hideNoPet && pet.state == PetTracker.State.NONE)) return null;
    if (preview && pet.state != PetTracker.State.EQUIPPED) {
      pet = new PetTracker.Snapshot();
      pet.state = PetTracker.State.EQUIPPED;
      pet.name = "Croc";
      pet.type = "croc";
      pet.rarity = "Legendary";
      pet.level = 42;
      pet.totalXp = PetCatalog.xpAtLevel(42) + 12450;
      pet.foodSeconds = 9300;
      pet.observedAt = pet.foodObservedAt = System.currentTimeMillis();
    }
    var hud = app.store.config.hud;
    var paint =
        new dev.asarix.skyblockflow.ui.HudPaint(
            hud.theme(dev.asarix.skyblockflow.core.HudSettings.Panel.PETS), hud.legacy);
    boolean equipped = pet.state == PetTracker.State.EQUIPPED;
    String title = pet.name + " · " + pet.rarity;
    String level = "Level " + (pet.level < 0 ? "?" : pet.level);
    long food = pet.foodLeft(System.currentTimeMillis());
    String hunger =
        food < 0 || pet.foodLowerBound && food == 0
            ? "Food unknown · open /pets"
            : pet.foodLowerBound
                ? "Food: ≥" + JobXp.duration(food * 1000) + " · open /pets"
                : food == 0
                    ? "HUNGRY · bonuses inactive"
                    : "Hungry in ~" + JobXp.duration(food * 1000);
    boolean knownXp = pet.totalXp >= 0 && pet.level >= 1;
    long needed = PetCatalog.xpToNext(pet.level);
    double current = knownXp ? Math.max(0, pet.totalXp - PetCatalog.xpAtLevel(pet.level)) : 0;
    String progress =
        pet.level >= 100
            ? "MAX LEVEL"
            : knownXp
                ? JobXp.amount(current) + " / " + JobXp.amount(needed) + " XP"
                : "XP unknown · open /pets";
    var foods = equipped ? app.pets.foods().stream().limit(3).toList() : List.<PetService.Food>of();
    int foodStart = PADDING + paint.width("Food:") + 10;
    int contentWidth;
    if (equipped) {
      contentWidth = Math.max(PROGRESS_WIDTH, 28 + paint.width(title));
      for (String line : List.of(level, hunger, progress))
        contentWidth = Math.max(contentWidth, paint.width(line));
      int foodWidth = paint.width("Food in inventory: none");
      if (!foods.isEmpty()) {
        foodWidth = foodStart - PADDING;
        for (var f : foods) foodWidth += 18 + paint.width("×" + f.count()) + 12;
        foodWidth -= 12;
      }
      contentWidth = Math.max(contentWidth, foodWidth);
    } else {
      contentWidth = Math.max(paint.width("Pet tracker"), paint.width("Open /pets to refresh"));
      contentWidth = Math.max(contentWidth,
          paint.width(pet.state == PetTracker.State.UNKNOWN ? "Unknown: open /pets" : "No pet equipped"));
    }
    int w = contentWidth + PADDING * 2, h = equipped ? 120 : 62;
    float scale = OverlayLayout.fittedScale(g.guiWidth(), g.guiHeight(), w, h, settings.scale);
    var rect =
        OverlayLayout.bounds(
            g.guiWidth(), g.guiHeight(), w, h, settings.x, settings.y, settings.scale);
    float x = (float) rect.x(), y = (float) rect.y();
    if (!draw) return new JobOverlay.Bounds(x, y, w * scale, h * scale);
    var c = hud.legacy ? settings.colors : paint.theme.colors;
    int accent = 0xff000000 | c.accent, text = 0xff000000 | c.text, muted = 0xff000000 | c.muted;
    g.pose().pushMatrix();
    g.pose().translate(x, y);
    g.pose().scale(scale, scale);
    if (!hud.legacy) paint.panel(g, 0, 0, w, h);
    else {
      Ui.rounded(
          g, 0, 0, w, h, 7, ((int) (255 * settings.opacity) << 24) | (c.background & 0xffffff));
      Ui.rounded(g, 0, 8, 3, h - 16, 1, accent);
    }
    if (pet.state != PetTracker.State.EQUIPPED) {
      paint.text(g, "Pet tracker", 12, 12, accent);
      paint.text(
          g,
          pet.state == PetTracker.State.UNKNOWN ? "Unknown: open /pets" : "No pet equipped",
          12,
          32,
          text);
      paint.text(g, "Open /pets to refresh", 12, 47, muted);
    } else {
      var icon = app.pets.icon();
      if (!icon.isEmpty()) {
        g.pose().pushMatrix();
        g.pose().translate(12, 8);
        g.pose().scale(1.25f, 1.25f);
        g.item(icon, 0, 0);
        g.pose().popMatrix();
      } else {
        // A small paw keeps previews and older saved pets tidy until /pets supplies their icon.
        Ui.rounded(g, 17, 17, 10, 8, 4, accent);
        Ui.rounded(g, 12, 12, 5, 6, 2, accent);
        Ui.rounded(g, 18, 8, 5, 6, 2, accent);
        Ui.rounded(g, 24, 9, 5, 6, 2, accent);
        Ui.rounded(g, 29, 14, 4, 6, 2, accent);
      }
      paint.text(g, title, 40, 12, accent);
      paint.text(g, level, 12, 33, text);
      paint.text(g, hunger, 12, 49, food == 0 && !pet.foodLowerBound ? 0xffffa0a0 : text);
      paint.text(g, progress, 12, 66, text);
      int progressWidth = PROGRESS_WIDTH;
      Ui.rounded(g, 12, 83, progressWidth, 4, 2, 0x884f6074);
      if (knownXp)
        Ui.rounded(
            g,
            12,
            83,
            (int)
                (progressWidth
                    * (pet.level >= 100 ? 1 : Math.clamp(current / Math.max(1, needed), 0, 1))),
            4,
            2,
            accent);
      if (foods.isEmpty()) paint.text(g, "Food in inventory: none", 12, 96, muted);
      else {
        paint.text(g, "Food:", 12, 99, muted);
        int ix = foodStart;
        for (var f : foods) {
          g.item(f.icon(), ix, 93);
          paint.text(g, "×" + f.count(), ix + 18, 99, text);
          ix += 18 + paint.width("×" + f.count()) + 12;
        }
      }
    }
    g.pose().popMatrix();
    return new JobOverlay.Bounds(x, y, w * scale, h * scale);
  }
}
