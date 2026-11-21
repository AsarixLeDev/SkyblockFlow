package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.screens.Screen;

/** Scrollable disclosure shown before connecting to a relay. */
public final class SharedEditTrustScreen extends FormScreen {
  private final String relay;
  private final Runnable enable;
  public SharedEditTrustScreen(SkyblockFlowClient app, Screen parent, String relay, Runnable enable) {
    super(app, parent, "Share a moment with a friend");
    this.relay = relay; this.enable = enable;
  }
  protected boolean readOnly() { return true; }
  protected String description() { return "Optional, invitation-only edits. You choose what to send and what to watch."; }
  protected void buildForm() {
    int y = 0;
    y = DiscordGuideScreen.paragraph(this, "Only enable sharing if you trust SkyblockFlow, its author asarix on Discord, and the operator of your chosen relay: " + relay, y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "The relay connects the two players. It receives your Minecraft name and UUID, a connection identifier, and your blocking preferences. Your Minecraft access token goes only to Mojang to prove your identity; the relay never receives it. This feature does not use your Discord account.", y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "When you send an edit, its selected audio, optional image and presentation settings pass through the relay. If terrain sharing is on, the nearby scene includes blocks, positions and the subject's appearance. The relay operator can access this content while relaying it; it is not end-to-end encrypted.", y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "Shared content is temporary, with no permanent share history. The relay removes media when the share finishes or is cancelled, and expires abandoned uploads. By default, this client removes received media after playback or cancellation; crash leftovers are cleared on the next launch. You can turn on Keep downloaded songs to retain songs locally for faster repeat sharing. Turn it off or use Clear saved songs to remove these copies. Your own original files are kept.", y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "Connection and blocking information stays in relay memory while connected. Short-lived completion and anti-spam records can remain after a share. Your local preferences and block list stay on your computer. The hosting provider may retain operational logs; the relay does not log media or authentication tokens.", y, bodyW) + 18;
    y = DiscordGuideScreen.paragraph(this, "Every invitation needs acceptance. You can decline, block a sender, press Esc to stop, or turn sharing off. Completed shares have a five-minute cooldown. Failed or declined shares do not consume it; a separate short retry limit prevents spam.", y, bodyW) + 20;
    if (enable != null) action("I trust this relay · enable sharing", 0, y, bodyW, () -> {
      enable.run(); minecraft.gui.setScreen(parent);
    });
    action(enable == null ? "Back" : "Cancel", 0, y + 36, bodyW, this::onClose);
  }
  protected void commit() { onClose(); }
}
