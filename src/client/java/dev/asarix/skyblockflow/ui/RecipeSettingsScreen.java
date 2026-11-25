package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.screens.Screen;

public final class RecipeSettingsScreen extends FormScreen {
  private boolean enabled;

  public RecipeSettingsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Recipe helper");
    enabled = app.recipes.enabled();
  }

  @Override
  protected String description() {
    return "Disable the browser, item inspection, materials overlay, crafting values and all recipe shortcuts together.";
  }

  @Override
  protected void buildForm() {
    toggle("Enable recipe helper", enabled, 0, () -> enabled = !enabled);
  }

  @Override
  protected void commit() {
    app.recipes.setEnabled(enabled);
    closeAfterSave();
  }
}
