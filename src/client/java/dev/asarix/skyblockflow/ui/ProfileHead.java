package dev.asarix.skyblockflow.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.world.item.component.ResolvableProfile;

final class ProfileHead {
  private final String name;
  private final ResolvableProfile profile;
  ProfileHead(String name) {
    this.name = name;
    profile = ResolvableProfile.createUnresolved(name);
  }
  void draw(GuiGraphicsExtractor g, int x,int y,int size) {
    var connection = Minecraft.getInstance().getConnection();
    var player = connection == null ? null : connection.getPlayerInfo(name);
    if (player != null) {
      PlayerFaceExtractor.extractRenderState(g,player.getSkin(),x,y,size); return;
    }
    PlayerFaceExtractor.extractRenderState(g,profile,x,y,size);
  }
}
