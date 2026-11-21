package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.DiscordAccount;
import dev.asarix.skyblockflow.core.DiscordOAuth;
import java.net.URI;
import net.minecraft.client.gui.screens.Screen;

public final class DiscordConnectionScreen extends FormScreen {
  private String shown = "";
  public DiscordConnectionScreen(SkyblockFlowClient app, Screen parent) { super(app, parent, "Discord connection"); }
  protected boolean readOnly() { return true; }
  protected String description() { return "Status: " + app.voice.status(); }
  private void browser(URI uri) { minecraft.schedule(() -> net.minecraft.util.Util.getPlatform().openUri(uri)); }
  private void connect(boolean controls) {
    minecraft.gui.setScreen(new DiscordTrustScreen(app, this, controls, () -> beginConnection(controls)));
  }
  private void beginConnection(boolean controls) {
    app.store.config.hud.voice = true;
    app.store.config.hud.discord = true;
    app.save();
    app.voice.connect(controls, this::browser);
  }
  protected void buildForm() {
    var account = app.voice.account;
    shown = signature();
    int y = paragraph(app.voice.help(), 0) + 16;
    if (!app.voice.available()) {
      action("Manage access in Discord", 0, y, bodyW, () -> browser(DiscordOAuth.AUTHORIZED_APPS));
      return;
    }
    if (account.view().phase() == DiscordAccount.Phase.WAITING) {
      action("Reopen Discord sign-in", 0, y, bodyW, () -> account.reopen(this::browser));
      action("Cancel sign-in", 0, y + 34, bodyW, account::cancel);
      y += 80;
    } else if (account.view().phase() == DiscordAccount.Phase.OPENING) {
      action("Cancel sign-in", 0, y, bodyW, account::cancel);
      y += 46;
    } else if (account.pendingRevocation()) {
      if (!account.busy()) {
        action("Retry removing authorization", 0, y, bodyW, account::retryRevocation);
        y += 34;
      }
    } else if (!account.busy()) {
      if (!account.linked()) {
        action("Connect Discord", 0, y, bodyW, () -> connect(false));
        y += 34;
      } else {
        if (!account.controls()) {
          action("Enable mute / deafen controls", 0, y, bodyW, () -> connect(true));
          y += 34;
          y = paragraph("Optional: asks Discord for permission to change your microphone and deafen settings.", y) + 10;
        }
        action("Disconnect and revoke access", 0, y, bodyW, app.voice::disconnect);
        y += 34;
      }
      toggle("Remember this device", account.remembered(), y, () -> account.remember(!account.remembered()));
      y += 36;
      y = paragraph(account.remembered()
          ? "Your login is saved in a private local file. Anyone running software as your computer account could read it."
          : "Session only: your login is removed when you quit. Failed removals are saved only so they can be retried.", y) + 18;
    }
    action("How to connect · privacy and help", 0, y, bodyW,
        () -> minecraft.gui.setScreen(new DiscordGuideScreen(app, this)));
    action("Manage access in Discord", 0, y + 34, bodyW, () -> browser(DiscordOAuth.AUTHORIZED_APPS));
  }
  int paragraph(String text, int y) { return DiscordGuideScreen.paragraph(this, text, y, bodyW); }
  private String signature() {
    var a = app.voice.account;
    return app.voice.help() + a.view().phase() + a.remembered() + a.linked() + a.controls();
  }
  @Override public void tick() {
    super.tick();
    if (!shown.equals(signature())) rebuildWidgets();
  }
  protected void commit() { onClose(); }
}
