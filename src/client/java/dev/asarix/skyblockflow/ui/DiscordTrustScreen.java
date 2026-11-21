package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.screens.Screen;

/** Explicit trust notice shown before either initial sign-in or a permission upgrade. */
public final class DiscordTrustScreen extends FormScreen {
  private final boolean controls;
  private final Runnable connect;
  public DiscordTrustScreen(SkyblockFlowClient app, Screen parent, boolean controls, Runnable connect) {
    super(app, parent, "Before connecting Discord");
    this.controls = controls;
    this.connect = connect;
  }
  protected boolean readOnly() { return true; }
  protected String description() { return "Only continue if you trust the mod and its author."; }
  protected void buildForm() {
    int y = DiscordGuideScreen.paragraph(this,
        "Only activate this feature if you trust SkyblockFlow and its author, asarix on Discord.", 0, bodyW) + 16;
    y = DiscordGuideScreen.paragraph(this,
        "You are allowing the mod on your computer to access your Discord profile and voice-call information, including the people in your call and who is speaking. Read Discord's permission screen before approving.", y, bodyW) + 12;
    if (controls) y = DiscordGuideScreen.paragraph(this,
        "This connection also requests permission to change your mute and deafen settings when you use the overlay controls.", y, bodyW) + 12;
    y = DiscordGuideScreen.paragraph(this,
        "In this version, authorization goes directly to Discord. Tokens stay on your computer and are not sent to the author. Owning the Discord app does not automatically give its author access to your tokens or call data.", y, bodyW) + 12;
    y = DiscordGuideScreen.paragraph(this,
        "You still need to trust the code you install and future updates. A modified or malicious mod could misuse permissions or local credentials. You can disconnect and revoke access at any time, or remove the app in Discord Settings > Authorized Apps.", y, bodyW) + 20;
    action("I trust SkyblockFlow · continue", 0, y, bodyW, () -> {
      minecraft.gui.setScreen(parent);
      connect.run();
    });
    action("Cancel", 0, y + 34, bodyW, this::onClose);
  }
  protected void commit() { onClose(); }
}
