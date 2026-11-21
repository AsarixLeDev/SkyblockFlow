package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.HudSettings.*;
import dev.asarix.skyblockflow.ui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class SpotifyOverlay {
  private static final int[] BARS = {9, 18, 24, 14, 7};

  public static JobOverlay.Bounds render(
      SkyblockFlowClient app, GuiGraphicsExtractor g, Music m, boolean preview, boolean draw) {
    var s = app.media.current();
    if (!preview
        && (!m.enabled
            || app.celebrations.hideOverlays()
            || s == null
            || s.title.isBlank()
            || !s.playing && !m.showPaused)) return null;
    boolean sample = s == null || s.title.isBlank();
    String title = sample ? "Night Drive" : s.title,
        artist = sample ? "Spotify" : s.artist,
        album = sample ? "Late Sessions" : s.album;
    long duration = sample ? 215000 : s.duration, pos = sample ? 78000 : s.elapsed();
    int w = 226, h = m.album ? 72 : 60;
    float scale = OverlayLayout.fittedScale(g.guiWidth(), g.guiHeight(), w, h, m.scale);
    var r = OverlayLayout.bounds(g.guiWidth(), g.guiHeight(), w, h, m.x, m.y, m.scale);
    var bounds = new JobOverlay.Bounds((float) r.x(), (float) r.y(), w * scale, h * scale);
    if (!draw) return bounds;
    var p = new HudPaint(app.store.config.hud.theme(Panel.SPOTIFY));
    g.pose().pushMatrix();
    g.pose().translate(bounds.x(), bounds.y());
    g.pose().scale(scale, scale);
    p.panel(g, 0, 0, w, h);
    int tx = m.art ? 59 : 12;
    if (m.art) {
      if (!sample && app.media.upload(s)) g.blit(DesktopMedia.COVER, 9, 8, 51, 50, 0, 1, 0, 1);
      else {
        Ui.rounded(g, 9, 8, 42, 42, 5, 0xff18392d);
        for (int i = 0; i < 5; i++) {
          int bar = BARS[i];
          Ui.rounded(g, 18 + 5 * i, 29 - bar / 2, 3, bar, 1, 0xff67df9b);
        }
      }
    }
    p.text(g, p.trim(title, w - tx - 12), tx, 9, p.ink());
    p.text(g, p.trim(artist, w - tx - 12), tx, 23, p.secondary());
    if (m.album) p.text(g, p.trim(album, w - tx - 12), tx, 35, p.secondary());
    int by = h - 17;
    String time = duration > 0 ? clock(pos) + " / " + clock(duration) : "LIVE";
    int end = w - p.width(time) - 17;
    g.fill(tx, by, end, by + 3, 0x5595aab4);
    if (duration > 0)
      g.fill(
          tx,
          by,
          tx + (int) ((end - tx) * Math.clamp((double) pos / duration, 0, 1)),
          by + 3,
          p.accent());
    p.text(g, time, end + 5, by - 3, p.secondary());
    g.pose().popMatrix();
    return bounds;
  }

  private static String clock(long ms) {
    long t = Math.max(0, ms) / 1000;
    return (t / 60) + ":" + (t % 60 < 10 ? "0" : "") + (t % 60);
  }
}
