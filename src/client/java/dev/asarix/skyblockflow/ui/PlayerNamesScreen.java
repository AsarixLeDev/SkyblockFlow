package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.screens.Screen;

public final class PlayerNamesScreen extends FormScreen {
  public PlayerNamesScreen(SkyblockFlowClient app, Screen parent) { super(app, parent, "Community names"); }
  protected boolean readOnly() { return true; }
  protected String description() { return "Status: " + app.playerNames.statusText(); }
  protected void buildForm() {
    int y = 0;
    y = DiscordGuideScreen.paragraph(this, "Server name colors and fonts stay unchanged by default. An SF badge appears after participating players’ names in tab and supported player chat. Asarix can assign a personal style when you request one.", y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "Hide icons separately in chat and tab on your screen, hide your own badge from other participants, or refuse custom styling on your own name. Red and near-black are unavailable in custom styles; orange and gray are allowed. Changes reach other participants on their next refresh, usually within 30 seconds.", y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "Enabling connects to the publisher's Railway relay. It verifies your Minecraft identity with Mojang. Your Minecraft access token goes only to Mojang, never to the relay. The relay receives your account name and UUID, and ordinary connection information such as your IP address.", y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "Your UUID, opted-in presence and badge / custom-style preferences become visible to other participants. No chat or server address is sent. Presence expires within two minutes of your last heartbeat. Disable to leave immediately; interrupted connections expire. The hosting provider may retain operational logs.", y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "Enable only if you trust SkyblockFlow (asarix on Discord). This choice stays on this computer and is not included in shared profiles.", y, bodyW) + 20;
    toggle("I agree · community names", app.playerNames.enabled(), y, () -> app.playerNames.setEnabled(!app.playerNames.enabled()));
    y += 40;
    toggle("Show SF icons in chat", app.playerNames.showChatBadges(), y,
        () -> app.playerNames.setPreferences(!app.playerNames.showChatBadges(), app.playerNames.showTabBadges(), app.playerNames.shareBadge(), app.playerNames.allowCustomStyle()));
    y += 40;
    toggle("Show SF icons in tab", app.playerNames.showTabBadges(), y,
        () -> app.playerNames.setPreferences(app.playerNames.showChatBadges(), !app.playerNames.showTabBadges(), app.playerNames.shareBadge(), app.playerNames.allowCustomStyle()));
    y += 40;
    toggle("Show my SF badge to others", app.playerNames.shareBadge(), y,
        () -> app.playerNames.setPreferences(app.playerNames.showChatBadges(), app.playerNames.showTabBadges(), !app.playerNames.shareBadge(), app.playerNames.allowCustomStyle()));
    y += 40;
    toggle("Allow a custom style on my name", app.playerNames.allowCustomStyle(), y,
        () -> app.playerNames.setPreferences(app.playerNames.showChatBadges(), app.playerNames.showTabBadges(), app.playerNames.shareBadge(), !app.playerNames.allowCustomStyle()));
    y += 40;
    label("Your saved appearance loads automatically at launch.", 0, y);
    y = DiscordGuideScreen.paragraph(this, "Chat supports normal player messages and server formats such as Name: message. Only the sender name is styled; ranks and message text stay unchanged. Custom nicknames keep their appearance. Styles update within five minutes while connected, including visible chat history.", y + 34, bodyW);
  }
  @Override
  public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float dt) {
    super.extractRenderState(g, mx, my, dt);
    if (!app.playerNames.loading()) return;
    int length = Math.min(160, bodyW), segment = Math.min(32, length);
    double phase = (System.nanoTime() / 1_000_000L % 1600) / 800.0;
    int offset = (int)((length - segment) * (phase <= 1 ? phase : 2 - phase));
    g.fill(bodyX, 62, bodyX + length, 64, 0xff344750);
    g.fill(bodyX + offset, 62, bodyX + offset + segment, 64, 0xff80dcc7);
  }
  protected void commit() { onClose(); }
}
