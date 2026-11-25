package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.ConfigStore;
import dev.asarix.skyblockflow.core.Models.*;
import net.minecraft.client.gui.screens.Screen;

public final class FeatureSettingsScreen extends FormScreen {
  private final Feature feature;
  private final Config draft;

  public FeatureSettingsScreen(SkyblockFlowClient app, Screen parent, Feature feature) {
    super(app, parent, FeatureBrowserScreen.name(feature));
    this.feature = feature;
    draft = ConfigStore.copy(app.store.config, Config.class);
  }

  @Override
  protected String description() {
    return FeatureBrowserScreen.description(feature);
  }

  @Override
  protected void buildForm() {
    int y = 0;
    if (feature == Feature.CHAT) {
      toggle(
          "Enable " + FeatureBrowserScreen.name(feature),
          draft.feature(feature).enabled,
          0,
          () -> draft.feature(feature).enabled = !draft.feature(feature).enabled);
      y = 40;
    }
    switch (feature) {
      case CHAT -> {
        toggle("Enable chat tabs", draft.chatTabs, y, () -> draft.chatTabs = !draft.chatTabs);
        y += 34;
        toggle(
            "Ctrl + left-click chat to copy",
            draft.copyChatOnClick,
            y,
            () -> draft.copyChatOnClick = !draft.copyChatOnClick);
        y += 34;
        toggle(
            "Compact repeated messages",
            draft.compactChat,
            y,
            () -> draft.compactChat = !draft.compactChat);
        y += 34;
        action(
            "+ Chat alert",
            0,
            y,
            bodyW,
            () -> minecraft.gui.setScreen(new AlertEditorScreen(app, this, null)));
        for (var rule : app.store.config.alerts) {
          y += 34;
          action(
              rule.name + " · " + (rule.enabled ? "ON" : "OFF"),
              0,
              y,
              bodyW,
              () -> minecraft.gui.setScreen(new AlertEditorScreen(app, this, rule)));
        }
      }

      case COORDINATES ->
          action(
              "Edit coordinate sounds",
              0,
              y,
              bodyW,
              () -> minecraft.gui.setScreen(new FeatureListScreen(app, this, true)));
      case COMMANDS ->
          action(
              "Edit command shortcuts",
              0,
              y,
              bodyW,
              () -> minecraft.gui.setScreen(new FeatureListScreen(app, this, false)));
    }
  }

  @Override
  protected void commit() {
    switch (feature) {
      case CHAT -> {
        if (app.store.config.chatTabs != draft.chatTabs) {
          app.store.config.chatTabs = draft.chatTabs;
          ((dev.asarix.skyblockflow.ChatTabAccess) minecraft.gui.hud.getChat()).skyblockflow$tab(dev.asarix.skyblockflow.ChatTabAccess.Tab.ALL);
          minecraft.gui.hud.getChat().rescaleChat();
        }
        app.store.config.copyChatOnClick = draft.copyChatOnClick;
        if (app.store.config.compactChat != draft.compactChat) {
          app.store.config.compactChat = draft.compactChat;
          minecraft.gui.hud.getChat().rescaleChat();
        }
      }

      default -> {}
    }
    app.store.config.features.put(feature, draft.feature(feature));
    if (app.save()) closeAfterSave();
  }
}
