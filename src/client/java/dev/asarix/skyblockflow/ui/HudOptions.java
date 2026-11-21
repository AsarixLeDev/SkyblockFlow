package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.HudSettings.*;
import dev.asarix.skyblockflow.core.Models.*;
import net.minecraft.client.gui.screens.Screen;

public final class HudOptions extends FormScreen {
  private final Panel panel;
  private boolean custom, musicEnabled, album, artwork, paused, discord, voice;
  private final Theme theme;

  public HudOptions(SkyblockFlowClient app, Screen parent, Panel panel) {
    super(app, parent, panel == null ? "Default overlay appearance" : panel.title);
    this.panel = panel;
    var hud = app.store.config.hud;
    custom = panel == null || hud.overrides.containsKey(panel);
    theme = ConfigStore.copy(panel == null ? hud.base : hud.theme(panel), Theme.class);
    var m = hud.music;
    musicEnabled = m.enabled;
    album = m.album;
    artwork = m.art;
    paused = m.showPaused;
    discord = hud.discord;
    voice = hud.voice;
  }

  protected String description() {
    return panel == null
        ? "Used by overlays that inherit this appearance."
        : panel.title + " overlay settings";
  }

  protected void buildForm() {
    if (panel != null) {
      page("Options");
      switch (panel) {
        case JOBS ->
            action(
                "Displayed job and fields",
                0,
                0,
                bodyW,
                () -> minecraft.gui.setScreen(new JobSettingsScreen(app, this, true)));
        case PETS ->
            action(
                "Visibility and pet data",
                0,
                0,
                bodyW,
                () -> minecraft.gui.setScreen(new PetSettingsScreen(app, this, true)));
        case MAP ->
            action(
                "Map size, corner and markers",
                0,
                0,
                bodyW,
                () -> minecraft.gui.setScreen(new WaypointSettingsScreen(app, this, true)));
        case CELEBRATION ->
            action(
                "Card position, timing and edit mode",
                0,
                0,
                bodyW,
                () ->
                    minecraft.gui.setScreen(
                        new CelebrationSettingsScreen(
                            app, this, app.store.config.jobs.celebration)));
        case CLOCK, MINERALS, ARTISAN, ESPORTS ->
            action(
                "Displayed fields and layout",
                0,
                0,
                bodyW,
                () -> minecraft.gui.setScreen(new ToolsScreen(app, this, panel.title)));
        case TRANSFER -> label("Shows upload and download progress during shared edits.", 0, 8);
        case DISCORD -> {
          toggle("Discord notification badge", discord, 0, () -> discord = !discord);
          label("Shows the count published in the desktop window title.", 0, 38);
          toggle("Show voice call", voice, 74, () -> voice = !voice);
          label(
              app.voice.canControl()
                  ? "Open chat during a call to mute your microphone."
                  : "Connect your account and manage voice permissions below.",
              0,
              110);
          action(
              "Connect / authenticate Discord",
              0,
              148,
              bodyW,
              () -> minecraft.gui.setScreen(new DiscordConnectionScreen(app, this)));
        }
        case SPOTIFY -> {
          toggle("Show Spotify", musicEnabled, 0, () -> musicEnabled = !musicEnabled);
          toggle("Show album", album, 34, () -> album = !album);
          toggle("Show cover art", artwork, 68, () -> artwork = !artwork);
          toggle("Show when paused", paused, 102, () -> paused = !paused);
        }
      }
    }
    page("Appearance");
    int y = 0;
    if (panel != null) {
      toggle("Override default appearance", custom, 0, () -> custom = !custom);
      y = 36;
      if (!custom) label("Inherits defaults · enable override to apply changes below.", 0, 38);
      y = 64;
    }
    action(
        "Style · " + Ui.readable(theme.surface),
        0,
        y,
        bodyW,
        () -> {
          theme.preset(Surface.values()[(theme.surface.ordinal() + 1) % Surface.values().length]);
          values.clear();
          rebuildWidgets();
        });
    action(
        "Font · " + Ui.readable(theme.font),
        0,
        y + 28,
        bodyW,
        () -> {
          theme.font = theme.font == Face.SKYBLOCKFLOW ? Face.MINECRAFT : Face.SKYBLOCKFLOW;
          rebuildWidgets();
        });
    action(
        "Action buttons · " + Ui.readable(theme.buttons),
        0,
        y + 56,
        bodyW,
        () -> {
          theme.buttons = theme.buttons == Buttons.PILL ? Buttons.TEXT : Buttons.PILL;
          rebuildWidgets();
        });
    field("alpha", "Background opacity (%)", Integer.toString(theme.opacity), y + 90);
    action(
        "Colors",
        0,
        y + 140,
        bodyW,
        () -> minecraft.gui.setScreen(new OverlayColorsScreen(app, this, theme.colors)));
    field("radius", "Corner radius (px)", "" + theme.radius, y + 180);
    field("border", "Outline thickness (px)", "" + theme.borderWidth, y + 228);
    field("stripe", "Accent thickness (px)", "" + theme.accentWidth, y + 276);
    field("shadow", "Shadow opacity (%)", "" + theme.shadow, y + 324);
    toggle(
        "Left accent stripe",
        theme.accentStripe,
        y + 374,
        () -> theme.accentStripe = !theme.accentStripe);
    action(
        "Outline color",
        0,
        y + 410,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new ColorPickerScreen(
                    app, this, "Outline color", theme.borderColor, v -> theme.borderColor = v)));
    action(
        "Gradient color",
        0,
        y + 442,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new ColorPickerScreen(
                    app,
                    this,
                    "Gradient color",
                    theme.gradientColor,
                    v -> theme.gradientColor = v)));
    label("Presets set a starting point. Every control stays editable.", 0, y + 480);
  }

  @Override
  public void extractRenderState(
      net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float dt) {
    super.extractRenderState(g, mx, my, dt);
    if (width >= 820) {
      var p = new HudPaint(theme);
      int x = width - 150;
      p.panel(g, x, 115, 140, 62);
      p.text(g, "PREVIEW", x + 12, 125, p.accent());
      p.text(g, "Artisan  ·  Lv. 109", x + 12, 143, p.ink());
      p.text(g, "Next level: 12 tools", x + 12, 160, p.secondary());
    }
  }

  protected void commit() {
    if (custom) {
      theme.radius = (int) number("radius", 0, 20);
      theme.borderWidth = (int) number("border", 0, 4);
      theme.accentWidth = (int) number("stripe", 0, 6);
      theme.shadow = (int) number("shadow", 0, 100);
    }
    var hud = app.store.config.hud;
    if (custom) theme.opacity = (int) number("alpha", 0, 100);
    if (panel == null) hud.base = theme;
    else if (custom) hud.overrides.put(panel, theme);
    else hud.overrides.remove(panel);
    if (panel == Panel.SPOTIFY) {
      var m = hud.music;
      m.enabled = musicEnabled;
      m.album = album;
      m.art = artwork;
      m.showPaused = paused;
    }
    if (panel == Panel.DISCORD) {
      hud.discord = discord;
      hud.voice = voice;
    }
    if (app.save()) closeAfterSave();
  }
}
