package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import net.minecraft.client.gui.screens.Screen;

public final class ToolsScreen extends FormScreen {
  private final String tool;
  private final ToolSettings draft;

  public ToolsScreen(SkyblockFlowClient app, Screen parent, String tool) {
    super(app, parent, tool);
    this.tool = tool;
    draft = ConfigStore.copy(app.store.config.tools, ToolSettings.class);
  }

  protected void buildForm() {
    page("Options");
    switch (tool) {
      case "Calculator" -> {
        toggle(
            "Inventory calculator",
            draft.calculator,
            0,
            () -> draft.calculator = !draft.calculator);
        label("Shown in inventory and containers. Click the bar to type.", 0, 40);
        label("+ − × ÷ and parentheses · decimal point or comma", 0, 62);
        label("Left arrow reveals the expression. Esc releases keyboard focus.", 0, 84);
      }
      case "Shop values" -> {
        toggle(
            "Show adjusted unit and stack values",
            draft.shopValues,
            0,
            () -> draft.shopValues = !draft.shopValues);
        label("Excalia bonus: +1.5% every 10 levels in the item's job.", 0, 40);
        label("Earn job XP first so the mod knows your current level.", 0, 62);
        action("Public prices & rankings", 0, 100, bodyW,
            () -> minecraft.gui.setScreen(new PublicDataScreen(app, this)));
      }
      case "Clock" -> {
        toggle("Show game time", draft.clock, 0, () -> draft.clock = !draft.clock);
        toggle(
            "Fancy day / night colors",
            draft.fancyClock,
            36,
            () -> draft.fancyClock = !draft.fancyClock);
        toggle("Daily claims reminder", draft.dailyClaims, 72, () -> draft.dailyClaims = !draft.dailyClaims);
        label("Every day at 23:55 · computer's local time", 0, 110);
      }
      case "Minerals" -> {
        toggle(
            "Minerals mined this session",
            draft.minerals,
            0,
            () -> draft.minerals = !draft.minerals);
        int y = 38;
        for (String key :
            java.util.List.of(
                "coal",
                "iron",
                "copper",
                "gold",
                "redstone",
                "lapis",
                "diamond",
                "emerald",
                "quartz",
                "ancient_debris")) {
          toggle(
              key.replace('_', ' '),
              draft.tracked.contains(key),
              y,
              () -> {
                if (!draft.tracked.remove(key)) draft.tracked.add(key);
              });
          y += 32;
        }
        action(
            "Reset this session's counts",
            0,
            y + 8,
            bodyW,
            () -> {
              app.tools.mined.clear();
              notice = "Session counts reset";
            });
        label("Counts ore blocks you break, independent of Fortune drops.", 0, y + 42);
      }
      case "Artisan" -> {
        toggle("Show Artisan calculator", draft.artisan, 0, () -> draft.artisan = !draft.artisan);
        field("tiers", "Number of craftable tool tiers", String.valueOf(draft.artisanTiers), 38);
        int y = 92;
        for (String line : app.tools.artisan()) {
          label(line, 0, y);
          y += 20;
        }
        label("Command: /skyblockflowartisan", 0, y + 14);
      }
      case "Ancient debris" -> {
        toggle(
            "Alert on mining XP while aiming at ancient debris",
            draft.ancientDebris,
            0,
            () -> draft.ancientDebris = !draft.ancientDebris);
        label("Off by default · repeats at most once every 5 seconds.", 0, 78);
      }
      case "Rocket League" -> {
        toggle(
            "Live Rocket League overlay", draft.esports, 0, () -> draft.esports = !draft.esports);
        toggle("Hide when no live or upcoming match", draft.esportsHideInactive, 36, () -> draft.esportsHideInactive = !draft.esportsHideInactive);
        toggle("Show last update time", draft.esportsShowUpdated, 72, () -> draft.esportsShowUpdated = !draft.esportsShowUpdated);
        action("Update now", 0, 110, bodyW, () -> notice = app.tools.esports.updateNow());
        label("Hourly · every 30m within 24h · every 15m within 2h · every minute live", 0, 146);
        int y = 178;
        for (String line : app.tools.esports.rows()) {
          label(line, 0, y);
          y += 20;
        }
        label("Game goals appear only when published by the source.", 0, y + 20);
        action(
            "Open Liquipedia",
            0,
            y + 52,
            bodyW,
            () ->
                net.minecraft.util.Util.getPlatform()
                    .openUri("https://liquipedia.net/rocketleague/Main_Page"));
      }
    }
    String key = layoutKey();
    if (key != null) {
      page("Layout");
      var l = draft.layout(key);
      field("x", "Horizontal position (%)", "" + (l.x * 100), 0);
      field("y", "Vertical position (%)", "" + (l.y * 100), 48);
      field("scale", "Scale", "" + l.scale, 96);
      action(
          "Overlay appearance presets",
          0,
          150,
          bodyW,
          () -> minecraft.gui.setScreen(new HudOptions(app, this, null)));
    }
  }

  private String layoutKey() {
    return switch (tool) {
      case "Clock" -> "clock";
      case "Minerals" -> "minerals";
      case "Artisan" -> "artisan";
      case "Rocket League" -> "esports";
      default -> null;
    };
  }

  protected void commit() {
    String key = layoutKey();
    if (key != null) {
      var l = draft.layout(key);
      l.x = decimal("x", 0, 100) / 100;
      l.y = decimal("y", 0, 100) / 100;
      l.scale = (float) decimal("scale", .5f, 3);
    }
    if (tool.equals("Artisan")) draft.artisanTiers = (int) number("tiers", 1, 12);
    // Merge only this page: nested editors may have saved unrelated settings.
    var live = app.store.config.tools;
    switch (tool) {
      case "Calculator" -> live.calculator = draft.calculator;
      case "Shop values" -> live.shopValues = draft.shopValues;
      case "Clock" -> {
        live.clock = draft.clock;
        live.fancyClock = draft.fancyClock;
        live.dailyClaims = draft.dailyClaims;
      }
      case "Minerals" -> {
        live.minerals = draft.minerals;
        live.tracked = draft.tracked;
      }
      case "Artisan" -> {
        live.artisan = draft.artisan;
        live.artisanTiers = draft.artisanTiers;
      }
      case "Ancient debris" -> {
        live.ancientDebris = draft.ancientDebris;
      }
      case "Rocket League" -> {
        live.esports = draft.esports;
        live.esportsHideInactive = draft.esportsHideInactive;
        live.esportsShowUpdated = draft.esportsShowUpdated;
      }
    }
    if (key != null) live.layouts.put(key, draft.layout(key));
    if (app.save()) closeAfterSave();
  }
}
