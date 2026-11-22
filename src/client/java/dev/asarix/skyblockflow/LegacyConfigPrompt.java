package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.LegacyConfigMigration;
import dev.asarix.skyblockflow.ui.FeatureBrowserScreen;
import dev.asarix.skyblockflow.ui.FormScreen;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

final class LegacyConfigPrompt {
  private final SkyblockFlowClient app;
  private boolean checked;
  private LegacyConfigMigration afterDisconnect;

  LegacyConfigPrompt(SkyblockFlowClient app) {
    this.app = app;
  }

  void register() {
    ClientTickEvents.START_CLIENT_TICK.register(this::tick);
    ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
      if (!(screen instanceof TitleScreen)) return;
      Component label = migrationText("source_title");
      Screens.getWidgets(screen).add(Button.builder(label, button -> openFromSettings(mc, screen))
          .bounds(8, 8, mc.font.width(label) + 16, 20).build());
      ScreenKeyboardEvents.allowKeyPress(screen).register((menu, key) -> {
        if (mc.gui.overlay() != null || !app.editorKey.matches(key)) return true;
        app.openEditor();
        return false;
      });
    });
  }

  void tick(Minecraft mc) {
    if (!atMainMenu(mc) || mc.gui.overlay() != null) return;
    if (afterDisconnect != null) {
      var migration = afterDisconnect;
      afterDisconnect = null;
      checked = true;
      var title = new TitleScreen();
      confirm(mc, new SourceScreen(title, migration.source()), title, migration, false);
      return;
    }
    if (checked || !(mc.gui.screen() instanceof TitleScreen title)) return;
    var migration = discover();
    if (migration.shouldOffer()) confirm(mc, title, title, migration, true);
    else checked = true;
  }

  private LegacyConfigMigration discover() {
    Path home = Path.of(System.getProperty("user.home"));
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    Path game = home.resolve(".minecraft");
    if (os.startsWith("windows")) {
      String appData = System.getenv("APPDATA");
      Path roaming = appData == null || appData.isBlank() ? home.resolve("AppData/Roaming") : Path.of(appData);
      game = roaming.resolve(".minecraft");
    } else if (os.startsWith("mac")) {
      game = home.resolve("Library/Application Support/minecraft");
    }
    return LegacyConfigMigration.discover(app.store.directory(), game.resolve("config"));
  }

  void openFromSettings(Minecraft mc, Screen parent) {
    mc.gui.setScreen(new SourceScreen(parent, discover().source()));
  }

  private void confirm(Minecraft mc, Screen parent, Screen done, LegacyConfigMigration migration, boolean startup) {
    var confirm = new ConfirmScreen(
        yes -> {
          if (startup) checked = true;
          if (yes) importSettings(mc, parent, done, migration);
          else {
            try {
              if (startup) migration.decline();
              mc.gui.setScreen(parent);
            } catch (Exception error) {
              failed(mc, parent, "decline_error", error);
            }
          }
        },
        migrationText("title"), migrationText("description", migration.source().toString()),
        migrationText("import"), migrationText("keep_current"));
    mc.gui.setScreen(confirm);
  }

  private final class SourceScreen extends FormScreen {
    private final Path suggested;

    SourceScreen(Screen parent, Path suggested) {
      super(LegacyConfigPrompt.this.app, parent, migrationText("source_title").getString());
      this.suggested = suggested;
    }

    @Override
    protected boolean readOnly() { return true; }

    @Override
    protected String description() {
      return migrationText(atMainMenu(minecraft) ? "source_description" : "source_disconnect").getString();
    }

    @Override
    protected void buildForm() {
      field("folder", migrationText("source_folder").getString(), suggested.toString(), 0);
      String action = atMainMenu(minecraft) ? "source_continue" : "source_disconnect_continue";
      action(migrationText(action).getString(), 0, 52, bodyW, this::selectSource);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
      if (files.size() != 1) {
        notice = migrationText("source_missing").getString();
        return;
      }
      values.put("folder", files.getFirst().toString());
      rebuildWidgets();
    }

    private void selectSource() {
      try {
        Path folder = Path.of(value("folder").strip()).toAbsolutePath().normalize();
        if (folder.getFileName() != null && folder.getFileName().toString().equalsIgnoreCase("excaliaflow.json"))
          folder = folder.getParent();
        var migration = new LegacyConfigMigration(app.store.directory(), folder);
        if (!migration.hasLegacySettings()) {
          notice = migrationText("source_missing").getString();
          return;
        }
        if (atMainMenu(minecraft)) {
          confirm(minecraft, this, new FeatureBrowserScreen(app), migration, false);
        } else {
          afterDisconnect = migration;
          minecraft.disconnectFromWorld(Component.translatable("disconnect.quitting"));
        }
      } catch (IllegalArgumentException invalid) {
        notice = migrationText("source_missing").getString();
      }
    }

    @Override
    protected void commit() { onClose(); }
  }

  private void importSettings(Minecraft mc, Screen parent, Screen done, LegacyConfigMigration migration) {
    var progress = new GenericMessageScreen(migrationText("copying"));
    mc.gui.setScreen(progress);
    CompletableFuture.supplyAsync(() -> {
      try {
        return migration.prepare();
      } catch (Exception error) {
        throw new CompletionException(error);
      }
    }).whenComplete((prepared, error) -> mc.execute(() -> {
      if (error != null) {
        if (mc.gui.screen() == progress) failed(mc, parent, "import_error", error);
        return;
      }
      try (prepared) {
        if (mc.gui.screen() != progress || !atMainMenu(mc)) return;
        app.pauseConfigMigration();
        var backup = migration.install(prepared);
        app.reloadMigratedSettings(prepared.settings());
        mc.gui.setScreen(new ConfirmScreen(
            delete -> {
              if (delete) deleteOldConfig(mc, done, migration, prepared);
              else mc.gui.setScreen(done);
            },
            migrationText("imported"), migrationText("delete_description", backup.getFileName().toString(), migration.source().toString()),
            migrationText("delete"), migrationText("keep_old")) {
          @Override
          protected void setInitialFocus() {
            if (noButton != null) setInitialFocus(noButton);
          }
        });
      } catch (Exception failure) {
        if (mc.gui.screen() == progress) {
          app.reloadMigratedSettings(app.store.config);
          failed(mc, parent, "import_error", failure);
        }
      }
    }));
  }

  private void deleteOldConfig(Minecraft mc, Screen parent, LegacyConfigMigration migration,
      LegacyConfigMigration.Prepared prepared) {
    var progress = new GenericMessageScreen(migrationText("deleting"));
    mc.gui.setScreen(progress);
    CompletableFuture.runAsync(() -> {
      try {
        migration.deleteLegacy(prepared);
      } catch (Exception error) {
        throw new CompletionException(error);
      }
    }).whenComplete((unused, error) -> mc.execute(() -> {
      if (mc.gui.screen() != progress) return;
      if (error == null) mc.gui.setScreen(parent);
      else failed(mc, parent, "delete_error", error);
    }));
  }

  private void failed(Minecraft mc, Screen title, String message, Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
    String details = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    app.error("Configuration migration: " + details);
    mc.gui.setScreen(new AlertScreen(() -> mc.gui.setScreen(title), migrationText("title"), migrationText(message, details)));
  }

  private static Component migrationText(String key, Object... arguments) {
    return Component.translatable("skyblockflow.migration." + key, arguments);
  }

  private static boolean atMainMenu(Minecraft mc) {
    return mc.level == null && mc.getConnection() == null;
  }
}
