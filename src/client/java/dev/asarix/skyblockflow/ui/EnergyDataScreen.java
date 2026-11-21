package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.EnergyPlan.Located;
import net.minecraft.client.gui.screens.Screen;

/** Local machine observations for diagnosing unrecognized server models and menus. */
public final class EnergyDataScreen extends FormScreen {
  private final Located location;
  public EnergyDataScreen(SkyblockFlowClient app, Screen parent, Located location) {
    super(app, parent, "Detected machine data"); this.location = location;
  }
  protected boolean readOnly() { return true; }
  protected String description() { return "Saved on this computer · your inventory is excluded"; }
  protected void buildForm() {
    page("Block"); details(location.blockObservation, "No block observation yet. Rescan this loaded block.");
    page("Machine menu"); details(location.menuObservation, "Right-click the saved machine in the world to read its menu.");
  }
  private void details(String text, String empty) {
    int y = 0;
    for (String line : (text.isBlank() ? empty : text).split("\n"))
      y = DiscordGuideScreen.paragraph(this, line, y, bodyW) + 8;
  }
  protected void commit() { onClose(); }
}
