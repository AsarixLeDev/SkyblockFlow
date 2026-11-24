package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.mixin.TooltipTextAccess;
import java.util.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

public final class CraftingPriceTooltip {
  public static final String PREFIX = "skyblockflow:ingredient:";
  private CraftingPriceTooltip() {}
  public static List<ClientTooltipComponent> decorate(List<ClientTooltipComponent> lines) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app == null || app.recipes == null) return lines;
    var result = new ArrayList<ClientTooltipComponent>(lines.size());
    for (var line : lines) {
      String[] item = {null};
      if (line instanceof TooltipTextAccess text) text.skyblockflow$text().accept((i, style, cp) -> {
        String marker = style.getInsertion();
        if (marker != null && marker.startsWith(PREFIX)) { item[0] = marker.substring(PREFIX.length()); return false; }
        return true;
      });
      result.add(item[0] == null ? line : new Row(line, app.recipes.icon(item[0])));
    }
    return result;
  }
  private record Row(ClientTooltipComponent text, ItemStack icon) implements ClientTooltipComponent {
    public int getWidth(Font font) { return 20 + text.getWidth(font); }
    public int getHeight(Font font) { return Math.max(18, text.getHeight(font)); }
    public void extractText(GuiGraphicsExtractor g, Font font, int x, int y) { text.extractText(g, font, x + 20, y + 4); }
    public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) { g.item(icon, x, y); }
  }
}
