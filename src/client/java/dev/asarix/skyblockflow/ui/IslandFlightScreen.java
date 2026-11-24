package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.IslandFlight;
import dev.asarix.skyblockflow.core.IslandFlightLogic.Rank;
import dev.asarix.skyblockflow.core.Numbers;
import net.minecraft.client.gui.screens.Screen;

public final class IslandFlightScreen extends FormScreen {
  private boolean enabled;
  public IslandFlightScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Island flight");
    enabled = app.islandFlight.settings().enabled && app.islandFlight.rank() != Rank.CITIZEN;
  }
  protected String description() { return "Turn flight on when you return to your island."; }
  protected void buildForm() {
    toggle("Enable island flight", enabled, 0, () -> {
      if (!enabled && app.islandFlight.rank() == Rank.CITIZEN) notice = "VIP or higher is required. Visit the Excalia store to get a rank.";
      else enabled = !enabled;
    });
    label("Use /is go or /is tp once to learn your island.", 0, 42);
    label("Any teleport landing inside your island can enable flight.", 0, 64);
    label("Rank: " + app.islandFlight.rank().label, 0, 102);
    var home = app.islandFlight.home();
    if (home == null) label("Island: not learned yet", 0, 130);
    else {
      label("Island: learned", 0, 130);
      label("World: " + home.dimension + (home.server.isEmpty() ? "" : " · " + home.server), 0, 152);
      label("Center: " + Numbers.amount(home.centerX) + " · " + Numbers.amount(home.centerZ)
          + " · Size: " + Numbers.amount(home.size), 0, 174);
    }
    action("Learn a different island", 0, 208, bodyW, () -> { app.islandFlight.forget(); notice = app.islandFlight.status; rebuildWidgets(); });
    label("Citoyen players need to purchase VIP or higher to use /fly enable.", 0, 252);
    action("Open Excalia's rank store", 0, 280, bodyW, () -> net.minecraft.util.Util.getPlatform().openUri(IslandFlight.STORE));
  }
  protected void commit() {
    app.islandFlight.setEnabled(enabled && app.islandFlight.rank() != Rank.CITIZEN);
    closeAfterSave();
  }
}
