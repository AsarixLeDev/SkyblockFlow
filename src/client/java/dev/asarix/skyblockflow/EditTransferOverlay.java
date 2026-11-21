package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.EditTransfer;
import dev.asarix.skyblockflow.ui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class EditTransferOverlay {
  public static void render(GuiGraphicsExtractor g, EditTransfer progress, boolean menu) {
    if (progress == null) return;
    int w = Math.min(320, g.guiWidth() - 24), x = (g.guiWidth() - w) / 2;
    int y = g.guiHeight() - (menu ? 87 : 103);
    var hud = SkyblockFlowClient.INSTANCE.store.config.hud;
    var p =
        new dev.asarix.skyblockflow.ui.HudPaint(
            hud.theme(dev.asarix.skyblockflow.core.HudSettings.Panel.TRANSFER), hud.legacy);
    if (!hud.legacy) p.panel(g, x, y, w, 49);
    else Ui.rounded(g, x, y, w, 49, 6, 0xf0182533);
    p.text(g, p.trim(progress.phase(), w - 20), x + 10, y + 8, hud.legacy ? 0xffecf2f8 : p.ink());
    p.text(
        g,
        p.trim(progress.detail(), w - 20),
        x + 10,
        y + 22,
        hud.legacy ? 0xffa6b9c9 : p.secondary());
    Ui.rounded(g, x + 10, y + 37, w - 20, 4, 2, 0xff35485a);
    double f = progress.fraction();
    if (f >= 0)
      Ui.rounded(
          g, x + 10, y + 37, (int) ((w - 20) * f), 4, 2, hud.legacy ? 0xff79dfbe : p.accent());
    else {
      int span = Math.max(8, (w - 20) / 4);
      int offset = (int) ((System.nanoTime() / 1000000 % 1400) / 1400d * (w - 20 - span));
      Ui.rounded(g, x + 10 + offset, y + 37, span, 4, 2, hud.legacy ? 0xff79dfbe : p.accent());
    }
  }
}
