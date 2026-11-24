package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import net.minecraft.client.gui.screens.Screen;

public final class PublicDataScreen extends FormScreen {
  private final ExcaliaApiSettings draft;
  public PublicDataScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Excalia public data");
    draft = ConfigStore.copy(app.store.config.tools.publicData, ExcaliaApiSettings.class);
  }
  protected String description() { return "Excalia Season 3 · rankings and prices from excalia.fr"; }
  protected void buildForm() {
    toggle("Public jobs, island & balance ranks", draft.leaderboards, 0, () -> draft.leaderboards = !draft.leaderboards);
    toggle("Player-shop buy & sell quotes", draft.market, 36, () -> draft.market = !draft.market);
    toggle("Current server-shop prices", draft.shopPrices, 72, () -> draft.shopPrices = !draft.shopPrices);
    label("Published tops define ranks. A stats lookup fills missing job XP.", 0, 112);
    label("Market buy: cheapest seller. Market sell: highest buyer.", 0, 134);
    label("Quotes show the merchant; availability may change before you arrive.", 0, 156);
    label("Unit and stack totals respect listed stock. Sold-out offers are hidden.", 0, 178);
    label("Recent prices are cached. Older quotes fall back to reference prices.", 0, 200);
    label("Public HTTPS requests send no Discord or Minecraft credentials.", 0, 234);
    label("Excalia receives your IP address, as with a visit to its website.", 0, 256);
    action("Refresh public data", 0, 290, bodyW, () -> {
      app.store.config.tools.publicData = ConfigStore.copy(draft, ExcaliaApiSettings.class);
      app.save(); app.excaliaApi.refreshRankings(); notice = app.excaliaApi.refreshPrices();
    });
    label("Refreshes respect Excalia's cache and rate limits.", 0, 326);
  }
  protected void commit() {
    app.store.config.tools.publicData = ConfigStore.copy(draft, ExcaliaApiSettings.class);
    if (app.save()) onClose();
  }
}
