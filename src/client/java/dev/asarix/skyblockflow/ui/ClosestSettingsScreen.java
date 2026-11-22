package dev.asarix.skyblockflow.ui;
import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import net.minecraft.client.gui.screens.Screen;

public final class ClosestSettingsScreen extends FormScreen {
  private final ClosestSettings draft, target;
  public ClosestSettingsScreen(SkyblockFlowClient app, Screen parent, ClosestSettings target) {
    super(app, parent, "Leaderboard settings");
    this.target = target; this.draft = ConfigStore.copy(target, ClosestSettings.class);
  }
  protected String description() { return "Review and click each command. SkyblockFlow reads the response."; }
  protected void buildForm() {
    toggle("Hide recognized responses", draft.hideOutput, 0, () -> draft.hideOutput = !draft.hideOutput);
    field("gap", "Minimum time between clicks (seconds)", "" + draft.commandGapMs / 1000.0, 40);
    field("timeout", "Response timeout (seconds)", "" + draft.responseTimeoutMs / 1000, 86);
    field("pages", "Maximum pages", "" + draft.maxPages, 132);
    label("Commands wait for your click. Public API data refreshes separately.", 0, 190);
  }
  protected void commit() {
    draft.commandGapMs = Math.round(decimal("gap", .5f, 60) * 1000);
    draft.responseTimeoutMs = number("timeout", 2, 60) * 1000;
    draft.maxPages = (int) number("pages", 1, 100);
    ConfigStore.validateClosest(draft);
    target.hideOutput = draft.hideOutput; target.commandGapMs = draft.commandGapMs;
    target.responseTimeoutMs = draft.responseTimeoutMs; target.maxPages = draft.maxPages;
    if (target != app.closest.settings() || app.save()) onClose();
  }
}
