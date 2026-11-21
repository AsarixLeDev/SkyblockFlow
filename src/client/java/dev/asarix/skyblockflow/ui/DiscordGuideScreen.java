package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.DiscordOAuth;
import net.minecraft.client.gui.screens.Screen;

/** The standard scrolling form keeps the complete guide usable at every GUI scale. */
public final class DiscordGuideScreen extends FormScreen {
  public DiscordGuideScreen(SkyblockFlowClient app, Screen parent) { super(app, parent, "Connect Discord · guide"); }
  protected boolean readOnly() { return true; }
  protected String description() { return "Connect your account. Scroll for steps, privacy and help."; }
  protected void buildForm() {
    int y = heading("Trust and permissions", 0);
    y = paragraph(this, "Only activate this feature if you trust SkyblockFlow and its author, asarix on Discord. This version sends no tokens or call data to the author, but the installed mod receives permissions on your computer. Review updates and Discord permissions before approving.", y, bodyW) + 18;
    y = heading("1 · Open Discord", y);
    y = paragraph(this, "Open the Discord desktop app on this computer and sign in to the account you want to use. A browser-only Discord session cannot supply the voice overlay.", y, bodyW) + 18;
    y = heading("2 · Connect and approve", y);
    y = paragraph(this, "Go back and click Connect Discord. Your browser opens discord.com. Check the publisher application, your account and the requested permissions, then approve. Discord may ask you to finish in its desktop app. Return to Minecraft when approval is received.", y, bodyW) + 8;
    y = paragraph(this, "You never need to create a developer app, copy a token, provide an application secret, or enter your Discord password into this mod.", y, bodyW) + 18;
    y = heading("3 · Join a call", y);
    y = paragraph(this, "Join a voice channel in the desktop app. The overlay shows the people in your call and who is speaking. Keep the desktop app running while you play.", y, bodyW) + 18;
    y = heading("Optional microphone controls", y);
    y = paragraph(this, "The first connection reads your profile and voice information. Enable mute / deafen controls asks for an additional permission. These buttons only act when you click them; they do not record or upload your audio.", y, bodyW) + 18;
    y = heading("What is stored and shared", y);
    y = paragraph(this, "Each player connects their own account. Your account is not shared with the mod author or other players. Sign-in and token renewal go directly to Discord. Voice events come from your local Discord app; avatars are downloaded from Discord.", y, bodyW) + 8;
    y = paragraph(this, "Remember this device is off by default. When enabled, access and refresh tokens are kept in a separate local file restricted to your computer account. They are not included in mod presets. This is not encryption: malicious software running as you could still read them. Use session-only mode on shared computers. On a normal exit, the mod asks Discord to remove access. After a crash or forced exit, remove it yourself in Discord Settings > Authorized Apps if needed.", y, bodyW) + 18;
    y = heading("Disconnect and remove access", y);
    y = paragraph(this, "Disconnect and revoke access stops the overlay immediately and asks Discord to remove the authorization. If Discord is offline, the mod clearly says removal is pending and retries later. Pending tokens can only be used for removal, never to reconnect.", y, bodyW) + 8;
    y = paragraph(this, "Revoking access also disconnects other installations authorized through this publisher application, including when you quit a session-only connection. Before uninstalling, disconnect while online or use Discord Settings > Authorized Apps > Deauthorize.", y, bodyW) + 18;
    y = heading("If the browser or overlay does not connect", y);
    y = paragraph(this, "Use Reopen Discord sign-in if the browser window was lost. Sign-in expires after five minutes; cancel and retry if needed. Use the same Discord account in the browser and desktop app. If Discord is unavailable, open the desktop app and wait for reconnection.", y, bodyW) + 8;
    y = paragraph(this, "If Discord says the app is not approved, or the redirect is invalid, the mod author must finish the release setup. You should not create your own app or paste credentials to work around it. Your game remains playable without this overlay.", y, bodyW) + 18;
    action("Manage authorized apps in Discord", 0, y, bodyW,
        () -> net.minecraft.util.Util.getPlatform().openUri(DiscordOAuth.AUTHORIZED_APPS));
  }
  private int heading(String text, int y) { section(text, y); return y + 26; }
  static int paragraph(FormScreen screen, String text, int y, int width) {
    String line = "";
    for (String word : text.split(" ")) {
      String next = line.isEmpty() ? word : line + " " + word;
      if (!line.isEmpty() && Ui.width(next) > width) {
        screen.label(line, 0, y);
        y += 14;
        line = word;
      } else line = next;
    }
    if (!line.isEmpty()) { screen.label(line, 0, y); y += 14; }
    return y;
  }
  protected void commit() { onClose(); }
}
