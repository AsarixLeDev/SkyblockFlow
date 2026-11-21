package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;
import net.minecraft.client.gui.screens.Screen;

public final class VisibilityScreen extends FormScreen {
  private final Map<Feature, Boolean> hidden = new EnumMap<>(Feature.class);

  public VisibilityScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "F11 visibility");
    for (var f : Feature.values()) hidden.put(f, app.store.config.feature(f).hideWithF11);
  }

  @Override
  protected String description() {
    return "Choose which features disappear when you press F11. Their behavior stays active.";
  }

  @Override
  protected void buildForm() {
    int y = 0;
    for (var f : Feature.values()) {
      toggle(FeatureBrowserScreen.name(f), hidden.get(f), y, () -> hidden.put(f, !hidden.get(f)));
      y += 32;
    }
  }

  @Override
  protected void commit() {
    hidden.forEach((f, h) -> app.store.config.feature(f).hideWithF11 = h);
    if (app.save()) closeAfterSave();
  }
}
