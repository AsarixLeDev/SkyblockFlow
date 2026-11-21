package dev.asarix.skyblockflow.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.ConfigStore;
import dev.asarix.skyblockflow.core.Models.CommandBinding;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

public final class CommandBindingEditorScreen extends FormScreen {
  private final CommandBinding original, draft;
  private boolean capturing;

  public CommandBindingEditorScreen(SkyblockFlowClient app, Screen parent, CommandBinding original) {
    super(app, parent, original == null ? "New command keybind" : "Edit command keybind");
    this.original = original;
    draft =
        original == null ? new CommandBinding() : ConfigStore.copy(original, CommandBinding.class);
  }

  @Override
  protected void buildForm() {
    field("name", "Keybind name", draft.name, 0);
    action(
        "Enabled: " + (draft.enabled ? "ON" : "OFF"),
        0,
        46,
        bodyW,
        () -> {
          draft.enabled = !draft.enabled;
          rebuildWidgets();
        });
    String key =
        draft.keyCode < 0
            ? "Unassigned"
            : InputConstants.Type.KEYSYM.getOrCreate(draft.keyCode).getDisplayName().getString();
    action(
        capturing ? "Press a key now (Esc cancels)..." : "Key: " + key + " · Click to change",
        0,
        82,
        bodyW,
        () -> {
          capturing = true;
          rebuildWidgets();
        });
    field("command", "Command (with or without /)", draft.command, 122);
    label("Example: /home or /warp mine", 0, 169);
    label("Runs once per press. Holding the key does not repeat it.", 0, 190);
    label("Only active in gameplay; typing in chat or menus is ignored.", 0, 211);
    label("SkyblockFlow's editor and loop controls are reserved.", 0, 232);
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (!capturing) return super.keyPressed(e);
    if (e.key() != GLFW.GLFW_KEY_ESCAPE && e.key() >= 32 && e.key() <= 348) draft.keyCode = e.key();
    capturing = false;
    rebuildWidgets();
    return true;
  }

  @Override
  protected void commit() {
    draft.name = value("name").strip();
    draft.command = value("command").trim();
    ConfigStore.validateCommandBinding(draft, app.store.config.commandBindings);
    if (app.isControlKey(draft.keyCode))
      throw new IllegalArgumentException(
          "This key is used by a SkyblockFlow control. Choose another key.");
    if (original == null) app.store.config.commandBindings.add(draft);
    else {
      int i = app.store.config.commandBindings.indexOf(original);
      if (i >= 0) app.store.config.commandBindings.set(i, draft);
    }
    if (app.save()) closeAfterSave();
    else notice = app.lastError;
  }
}
