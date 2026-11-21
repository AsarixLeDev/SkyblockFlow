package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import net.minecraft.client.gui.screens.Screen;

public final class PetSettingsScreen extends FormScreen {
  private final PetSettings draft;
  private boolean enabled;
  private final boolean panelOnly;

  public PetSettingsScreen(SkyblockFlowClient app, Screen parent) {
    this(app, parent, false);
  }

  public PetSettingsScreen(SkyblockFlowClient app, Screen parent, boolean panelOnly) {
    super(app, parent, "Pet tracker");
    draft = ConfigStore.copy(app.store.config.pets, PetSettings.class);
    this.panelOnly = panelOnly;
    enabled = app.store.config.enabled(Feature.PETS);
  }

  @Override
  protected String description() {
    return "Open /pets to refresh your equipped pet.";
  }

  @Override
  protected void buildForm() {
    if (panelOnly) {
      buildPanel();
      return;
    }
    page("Tracking");
    toggle("Track equipped pet", enabled, 0, () -> enabled = !enabled);
    label("Status: " + app.pets.status, 0, 40);
    if (app.pets.tracker.data.state == PetTracker.State.EQUIPPED)
      label("Pet: " + app.pets.tracker.data.name, 0, 62);
    else if (app.pets.tracker.data.state == PetTracker.State.NONE) label("No pet equipped", 0, 62);
    action(
        "Open /pets",
        0,
        92,
        bodyW,
        () -> {
          if (minecraft.player == null) throw new IllegalStateException("Join a server first");
          minecraft.gui.setScreen(null);
          minecraft.player.connection.sendCommand("pets");
        });
    page("Display");
    action(
        "Overlay settings  ›",
        0,
        0,
        bodyW,
        () ->
            minecraft.gui.setScreen(
                new HudOptions(app, this, dev.asarix.skyblockflow.core.HudSettings.Panel.PETS)));
    page("Warnings");
    toggle(
        "Warn when a hungry pet loses job XP bonuses",
        draft.warnHungry,
        0,
        () -> draft.warnHungry = !draft.warnHungry);
    field(
        "cooldown",
        "Minimum time between warnings per job (seconds)",
        "" + draft.warningCooldownMs / 1000,
        40);
    label("Warn after this much job XP while hungry. Set 0 to disable a job.", 0, 88);
    int row = 116;
    for (var entry : JobXp.NAMES.entrySet()) {
      field(
          "xp_" + entry.getKey(),
          entry.getValue() + " XP threshold",
          "" + draft.threshold(entry.getKey()),
          row);
      row += 48;
    }
    label("Only unlocked, active XP bonuses count. Uses the normal title/sound alarm.", 0, row + 6);
  }

  private void buildPanel() {
    page("Overlay");
    toggle("Show pet overlay", draft.showOverlay, 0, () -> draft.showOverlay = !draft.showOverlay);
    toggle(
        "Hide when no pet is equipped",
        draft.hideNoPet,
        32,
        () -> draft.hideNoPet = !draft.hideNoPet);
    toggle(
        "Hide until pet details are available",
        draft.hideNoData,
        64,
        () -> draft.hideNoData = !draft.hideNoData);
  }

  @Override
  protected void commit() {
    var live = app.store.config.pets;
    if (panelOnly) {
      live.showOverlay = draft.showOverlay;
      live.hideNoPet = draft.hideNoPet;
      live.hideNoData = draft.hideNoData;
      if (app.save()) closeAfterSave();
      return;
    }
    draft.warningCooldownMs = number("cooldown", 1, 86400) * 1000;
    for (String job : JobXp.NAMES.keySet())
      draft.warningXp.put(job, (double) decimal("xp_" + job, 0, 1e12f));
    ConfigStore.validatePets(draft);
    live.warningCooldownMs = draft.warningCooldownMs;
    live.warningXp = draft.warningXp;
    live.warnHungry = draft.warnHungry;
    app.store.config.feature(Feature.PETS).enabled = enabled;
    if (app.save()) closeAfterSave();
    else notice = app.lastError;
  }
}
