package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.OverlayColors;
import java.util.EnumMap;
import java.util.Map;

public final class HudSettings {
  public enum Panel {
    JOBS("Job tracker"),
    PETS("Pet tracker"),
    SPOTIFY("Spotify"),
    DISCORD("Discord · Experimental"),
    MAP("Waypoint map"),
    TRANSFER("Edit transfers"),
    CLOCK("Clock"),
    MINERALS("Minerals"),
    ARTISAN("Artisan"),
    ESPORTS("Rocket League"),
    CELEBRATION("Celebrations");
    public final String title;

    Panel(String title) {
      this.title = title;
    }
  }

  public enum Surface {
    GLASS,
    UNDERLINE,
    ROUNDED,
    SQUARE_OUTLINE,
    DOUBLE_FRAME,
    CORNERS,
    GRADIENT,
    RIBBON,
    MINIMAL
  }

  public enum Face {
    SKYBLOCKFLOW,
    MINECRAFT
  }

  public enum Buttons {
    PILL,
    TEXT
  }

  public static final class Theme {
    public Surface surface = Surface.GLASS;
    public Face font = Face.SKYBLOCKFLOW;
    public Buttons buttons = Buttons.PILL;
    public OverlayColors colors = new OverlayColors();
    public int opacity = 58;
    public int radius = 7, borderWidth = 1, accentWidth = 2, shadow = 25;
    public int borderColor = 0x79dfbe, gradientColor = 0x24354b;
    public boolean accentStripe = true;

    public void preset(Surface style) {
      surface = style;
      radius = style == Surface.SQUARE_OUTLINE || style == Surface.CORNERS ? 0 : 7;
      borderWidth = 1;
      accentWidth = 2;
      shadow = 25;
      opacity = 70;
      borderColor = colors.accent;
      gradientColor = 0x24354b;
      accentStripe = style == Surface.GLASS;
      if (style == Surface.MINIMAL) {
        opacity = 0;
        shadow = 0;
      }
      if (style == Surface.GRADIENT) {
        opacity = 90;
        gradientColor = 0x233b54;
      }
      if (style == Surface.DOUBLE_FRAME) {
        radius = 4;
        shadow = 35;
      }
      if (style == Surface.RIBBON) {
        radius = 5;
        accentWidth = 3;
      }
    }

    void normalize() {
      if (surface == null) surface = Surface.GLASS;
      if (font == null) font = Face.SKYBLOCKFLOW;
      if (buttons == null) buttons = Buttons.PILL;
      if (colors == null) colors = new OverlayColors();
      opacity = Math.clamp(opacity, 0, 100);
      radius = Math.clamp(radius, 0, 20);
      borderWidth = Math.clamp(borderWidth, 0, 4);
      accentWidth = Math.clamp(accentWidth, 0, 6);
      shadow = Math.clamp(shadow, 0, 100);
    }
  }

  public static class Placement {
    public double x = .02, y = .78;
    public float scale = 1;
  }

  public static final class Music extends Placement {
    public boolean enabled = false, album, art = true, showPaused = true;
  }

  public boolean legacy, discord, voice;

  public static Placement badgePlacement() {
    var m = new Placement();
    m.x = .45;
    m.y = .06;
    return m;
  }

  public Placement discordLayout = badgePlacement();
  public Theme base = new Theme();
  public Music music = new Music();
  public Map<Panel, Theme> overrides = new EnumMap<>(Panel.class);

  public Theme theme(Panel p) {
    return overrides.getOrDefault(p, base);
  }

  void inheritOld(Panel panel, OverlayColors colors, float opacity, float defaultOpacity) {
    var d = new OverlayColors();
    if (colors == null) return;
    if (colors.background == d.background
        && colors.accent == d.accent
        && colors.text == d.text
        && colors.muted == d.muted
        && opacity == defaultOpacity) return;
    var t = ConfigStore.copy(base, Theme.class);
    t.colors = ConfigStore.copy(colors, OverlayColors.class);
    if (opacity != defaultOpacity) t.opacity = Math.clamp(Math.round(opacity * 100), 0, 100);
    overrides.put(panel, t);
  }

  public void normalize() {
    if (base == null) base = new Theme();
    base.normalize();
    if (discordLayout == null) discordLayout = badgePlacement();
    if (music == null) music = new Music();
    for (var p : java.util.List.of(music, discordLayout)) {
      p.x = Double.isFinite(p.x) ? Math.clamp(p.x, 0, 1) : 0;
      p.y = Double.isFinite(p.y) ? Math.clamp(p.y, 0, 1) : 0;
      p.scale = Float.isFinite(p.scale) ? Math.clamp(p.scale, .5f, 3f) : 1;
    }
    if (overrides == null) overrides = new EnumMap<>(Panel.class);
    overrides.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
    overrides.values().forEach(Theme::normalize);
  }
}
