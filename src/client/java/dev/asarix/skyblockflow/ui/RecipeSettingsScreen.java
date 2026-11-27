package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import net.minecraft.client.gui.screens.Screen;

public final class RecipeSettingsScreen extends FormScreen {
  private boolean enabled, sackIcons;

  public RecipeSettingsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Recipe helper");
    enabled = app.recipes.enabled();
    sackIcons = app.recipes.settings().sackIcons;
  }

  @Override
  protected String description() {
    return "Disable the browser, item inspection, materials overlay, crafting values and all recipe shortcuts together.";
  }

  @Override
  protected void buildForm() {
    toggle("Enable recipe helper", enabled, 0, () -> enabled = !enabled);
    toggle("Show sack item icons", sackIcons, 36, () -> sackIcons = !sackIcons);
    label("Tiny item overlays on sacks in inventory and hotbar.", 0, 74);
  }

  @Override
  protected void commit() {
    app.recipes.settings().sackIcons = sackIcons;
    app.recipes.setEnabled(enabled);
    closeAfterSave();
  }
}
