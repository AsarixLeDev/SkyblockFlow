package dev.asarix.skyblockflow.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.Models.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;

public final class GeneralSettingsScreen extends FormScreen {
  private int browserKey;
  private boolean profiles, legacy;

  public GeneralSettingsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Settings");
    browserKey =
        ((dev.asarix.skyblockflow.mixin.KeyMappingAccess) app.editorKey)
            .skyblockflow$key()
            .getValue();
    legacy = app.store.config.hud.legacy;
    profiles = app.store.config.autoServerProfiles;
  }

  @Override
  protected void buildForm() {
    page("General");
    field(
        "grace",
        "Suppress all alerts after connection (seconds)",
        "" + app.store.config.connectionGraceMs / 1000.0,
        0);
    label("Alerts during the connection grace period are discarded.", 0, 46);
    keyField("Open feature browser", browserKey, 72, k -> browserKey = k);
    action("Mod updates", 0, 144, bodyW, () -> dev.asarix.flow.update.Updates.open(this));
    page("Profiles");
    toggle("Separate settings for each server/world", profiles, 0, () -> profiles = !profiles);
    label("Current profile: " + app.store.config.profileName, 0, 36);
    page("Backups");
    action(
        "Create configuration backup",
        0,
        0,
        bodyW,
        () -> {
          try {
            notice = "Saved " + app.store.backup().getFileName();
          } catch (Exception e) {
            notice = e.getMessage();
          }
        });
    action("Restore backup / import JSON", 0, 34, bodyW, () -> chooseImport());
    action(net.minecraft.network.chat.Component.translatable("skyblockflow.migration.source_title").getString(),
        0, 68, bodyW, () -> app.openConfigMigration(this));
    page("Profiles");
    action(
        "Export profile with custom sounds",
        0,
        66,
        bodyW,
        () -> {
          try {
            notice =
                "Exported "
                    + dev.asarix.skyblockflow.ProfileSharing.exportBundle(app.store).getFileName();
          } catch (Exception e) {
            notice = e.getMessage();
          }
        });
    action("Import profile bundle", 0, 100, bodyW, () -> chooseBundle());
    action(
        "Open settings folder",
        0,
        144,
        bodyW,
        () -> net.minecraft.util.Util.getPlatform().openPath(app.store.directory()));
    label("Put imported JSON/ZIP files in the imports folder.", 0, 182);
    label(app.store.loadWarning, 0, 204);
    page("Legacy");
    toggle("Legacy appearance", legacy, 0, () -> legacy = !legacy);
  }

  private void applyLoaded() {
    app.reloadPatterns();
    KeyMapping.resetMapping();
    minecraft.gui.setScreen(new FeatureBrowserScreen(app));
  }

  private void chooseImport() {
    try {
      var imports = app.store.directory().resolve("imports");
      Files.createDirectories(imports);
      List<ChoiceScreen.Choice<Path>> choices = new ArrayList<>();
      try (var paths = Files.walk(app.store.directory(), 2)) {
        paths
            .filter(
                p ->
                    Files.isRegularFile(p)
                        && p.toString().endsWith(".json")
                        && !p.getFileName().toString().equals("skyblockflow.json")
                        && !p.toString().contains("/profiles/"))
            .forEach(
                p ->
                    choices.add(
                        new ChoiceScreen.Choice<>(
                            app.store.directory().relativize(p).toString(), p)));
      }
      minecraft.gui.setScreen(
          new ChoiceScreen<>(
              app,
              this,
              "Restore / import configuration",
              choices,
              path -> {
                try {
                  app.store.restore(path);
                  applyLoaded();
                } catch (Exception e) {
                  app.error(e.getMessage());
                  minecraft.gui.setScreen(this);
                }
              }));
    } catch (Exception e) {
      notice = e.getMessage();
    }
  }

  private void chooseBundle() {
    try {
      var imports = app.store.directory().resolve("imports");
      Files.createDirectories(imports);
      List<ChoiceScreen.Choice<Path>> choices;
      try (var paths = Files.list(imports)) {
        choices =
            paths
                .filter(p -> p.toString().endsWith(".zip"))
                .map(p -> new ChoiceScreen.Choice<>(p.getFileName().toString(), p))
                .toList();
      }
      minecraft.gui.setScreen(
          new ChoiceScreen<>(
              app,
              this,
              "Import bundle",
              choices,
              path -> {
                try {
                  dev.asarix.skyblockflow.ProfileSharing.importBundle(app.store, path);
                  applyLoaded();
                } catch (Exception e) {
                  app.error(e.getMessage());
                  minecraft.gui.setScreen(this);
                }
              }));
    } catch (Exception e) {
      notice = e.getMessage();
    }
  }

  @Override
  protected void commit() {
    app.store.config.connectionGraceMs = Math.round(decimal("grace", 0, 3600) * 1000);
    app.store.config.hud.legacy = legacy;
    app.editorKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(browserKey));
    KeyMapping.resetMapping();
    minecraft.options.save();
    boolean enabling = profiles && !app.store.config.autoServerProfiles;
    app.store.config.autoServerProfiles = profiles;
    if (enabling && minecraft.player != null) app.loadServerProfile();
    if (app.save()) closeAfterSave();
  }
}
