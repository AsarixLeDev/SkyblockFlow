package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.HudSettings.*;
import dev.asarix.skyblockflow.ui.HudPaint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class DiscordBadge {
  private static final Identifier LOGO =
      Identifier.fromNamespaceAndPath("skyblockflow", "textures/gui/discord.png");
  public static final int WIDTH = 64;

  public static JobOverlay.Bounds render(
      SkyblockFlowClient app,
      GuiGraphicsExtractor g,
      Placement layout,
      boolean preview,
      boolean draw) {
    var session = app.media.current();
    int count = session == null ? -1 : session.discord;
    var hud = app.store.config.hud;
    var call = hud.voice ? app.voice.call : null;
    if (!hud.discord) count = -1;
    if (!preview && (!hud.discord && !hud.voice || app.celebrations.hideOverlays())) return null;
    if (preview && count <= 0) count = 3;
    int rows = call == null ? 0 : Math.min(8, call.members().size()),
        w = call == null && count > 0 ? WIDTH : 164,
        h = 28 + rows * 21;
    var rect =
        OverlayLayout.bounds(g.guiWidth(), g.guiHeight(), w, h, layout.x, layout.y, layout.scale);
    var bounds =
        new JobOverlay.Bounds(
            (float) rect.x(), (float) rect.y(), (float) rect.width(), (float) rect.height());
    if (draw) {
      var p = new HudPaint(app.store.config.hud.theme(Panel.DISCORD));
      float scale = bounds.width() / w;
      g.pose().pushMatrix();
      g.pose().translate(bounds.x(), bounds.y());
      g.pose().scale(scale, scale);
      p.panel(g, 0, 0, w, h);
      g.blit(LOGO, 8, 4, 28, 24, 0f, 1f, 0f, 1f);
      String label =
          count > 0 ? (count > 99 ? "99+" : Integer.toString(count)) : app.voice.status();
      if (call != null)
        label = "Voice · " + call.members().size() + (count > 0 ? "  (" + label + ")" : "");
      p.text(g, p.trim(label, w - 44), 34, 9, p.ink());
      for (int i = 0; i < rows; i++) {
        var member = call.members().get(i);
        int y = 27 + 21 * i;
        var portrait = app.portraits.texture(member);
        int tint = member.speaking() ? 0xffffffff : 0x99ffffff;
        if (portrait != null)
          g.blit(
              net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
              portrait,
              9,
              y,
              0,
              0,
              18,
              18,
              32,
              32,
              32,
              32,
              tint);
        else {
          dev.asarix.skyblockflow.ui.Ui.rounded(
              g, 9, y, 18, 18, 8, member.speaking() ? p.accent() : 0x99586277);
          p.text(
              g,
              member.name().isBlank()
                  ? "?"
                  : member.name().substring(0, member.name().offsetByCodePoints(0, 1)),
              14,
              y + 5,
              tint);
        }
        p.text(
            g,
            p.trim(member.name(), w - 51),
            33,
            y + 5,
            member.speaking() ? p.ink() : p.secondary());
        if (member.muted()) p.text(g, "×", w - 13, y + 5, p.secondary());
      }
      g.pose().popMatrix();
    }
    return bounds;
  }
}
