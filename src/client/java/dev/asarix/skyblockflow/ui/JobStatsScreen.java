package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.*;

/** The command and settings shortcut open the Jobs tab of the same player profile. */
public final class JobStatsScreen extends PlayerViewScreen {
  public JobStatsScreen(SkyblockFlowClient app,Screen parent){this(app,parent,Minecraft.getInstance().getUser().getName());}
  public JobStatsScreen(SkyblockFlowClient app,Screen parent,String player){super(app,parent,player,ProfileContent.Section.JOBS,null);}
  public static Item icon(String id) {
    return switch (id) {
      case "mineur" -> Items.DIAMOND_PICKAXE;
      case "bucheron" -> Items.IRON_AXE;
      case "fermier" -> Items.WHEAT;
      case "chasseur" -> Items.DIAMOND_SWORD;
      case "artisan" -> Items.ANVIL;
      case "aventurier" -> Items.ENDER_EYE;
      default -> Items.FISHING_ROD;
    };
  }
}
