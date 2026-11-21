package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

/**
 * Index actual nested form controls, including their page and scroll position. No actions execute.
 */
public final class SettingSearchScreen extends FlowScreen {
  private record Route(String name, Function<Screen, FormScreen> factory) {}

  private record Hit(Route route, FormScreen.SearchControl control) {}

  private final List<Hit> index = new ArrayList<>();
  private List<Hit> matches = List.of();
  private String query;
  private int scroll;
  private EditBox search;

  public SettingSearchScreen(SkyblockFlowClient app, Screen parent, String query) {
    super(app, parent, "Search every setting");
    this.query = query;
  }

  private List<Route> routes() {
    var routes = new ArrayList<Route>();
    routes.add(new Route("Community names / tab / chat / colors / gradients / fonts", p -> new PlayerNamesScreen(app, p)));
    routes.add(new Route("General", p -> new GeneralSettingsScreen(app, p)));
    routes.add(new Route("Jobs", p -> new JobSettingsScreen(app, p)));
    routes.add(
        new Route(
            "Jobs / closest player",
            p -> new ClosestSettingsScreen(app, p, app.closest.settings())));
    routes.add(new Route("Pets", p -> new PetSettingsScreen(app, p)));
    routes.add(new Route("Shared edits", p -> new SharedEditsScreen(app, p)));
    routes.add(new Route("Waypoints / settings", p -> new WaypointSettingsScreen(app, p)));
    routes.add(new Route("Waypoints / map", p -> new WaypointSettingsScreen(app, p, true)));
    routes.add(new Route("Spawners", p -> new SpawnerSettingsScreen(app, p)));
    routes.add(
        new Route(
            "Celebration",
            p -> new CelebrationSettingsScreen(app, p, app.store.config.jobs.celebration)));
    routes.add(
        new Route(
            "Celebration / edit effects",
            p -> new CelebrationEditScreen(app, p, app.store.config.jobs.celebration)));
    routes.add(new Route("Overlays / default appearance", p -> new HudOptions(app, p, null)));
    routes.add(
        new Route(
            "Overlays / colors",
            p -> new OverlayColorsScreen(app, p, app.store.config.hud.base.colors)));
    routes.add(new Route("Discord / authentication", p -> new DiscordConnectionScreen(app, p)));
    routes.add(new Route("Discord / guide / privacy / tutorial", p -> new DiscordGuideScreen(app, p)));
    routes.add(new Route("Energy planner", p -> new EnergyScreen(app, p)));
    for (var panel : HudSettings.Panel.values())
      routes.add(new Route("Overlays / " + panel.title, p -> new HudOptions(app, p, panel)));
    for (var kind : AlarmKind.values())
      routes.add(new Route("Alarms / " + kind.label, p -> new AlarmHub.Editor(app, p, kind)));
    for (String tool :
        List.of(
            "Calculator",
            "Shop values",
            "Clock",
            "Minerals",
            "Artisan",
            "Ancient debris",
            "Rocket League")) routes.add(new Route(tool, p -> new ToolsScreen(app, p, tool)));
    for (var f : List.of(Feature.CHAT, Feature.COORDINATES, Feature.COMMANDS))
      routes.add(
          new Route(FeatureBrowserScreen.name(f), p -> new FeatureSettingsScreen(app, p, f)));
    return routes;
  }

  @Override
  protected void init() {
    super.init();
    search = edit(20, 74, width - 40, query);
    search.setHint(Ui.label("Find any setting, including nested options…"));
    search.setResponder(
        v -> {
          query = v;
          filter();
        });
    setInitialFocus(search);
    search.setFocused(true);
    search.moveCursorToEnd(false);
    button(width - 90, height - 28, 76, "Back", this::onClose);
    index.clear();
    for (var route : routes()) {
      try {
        var form = route.factory.apply(this);
        form.init(width, height);
        for (var c : form.searchControls()) index.add(new Hit(route, c));
      } catch (RuntimeException ignored) {
      }
    }
    filter();
  }

  private static String normalized(String s) {
    return JobXp.normalize(s).toLowerCase(Locale.ROOT);
  }

  private void filter() {
    String[] words = normalized(query).strip().split("\\s+");
    matches =
        index.stream()
            .filter(
                h -> {
                  String hay =
                      normalized(h.route.name + " " + h.control.page() + " " + h.control.caption());
                  for (String word : words) if (!hay.contains(word)) return false;
                  return true;
                })
            .toList();
    scroll = 0;
  }

  private void open(Hit hit) {
    var form = hit.route.factory.apply(this);
    minecraft.gui.setScreen(form);
    form.reveal(hit.control);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (super.mouseClicked(e, twice)) return true;
    if (e.button() != 0 || e.y() < 110 || e.y() > height - 40) return false;
    int i = ((int) e.y() - 110 + scroll) / 40;
    if (i >= 0 && i < matches.size()) {
      open(matches.get(i));
      return true;
    }
    return false;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll =
        Math.clamp(scroll - (int) (sy * 36), 0, Math.max(0, matches.size() * 40 - (height - 150)));
    return true;
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (e.key() == GLFW.GLFW_KEY_ENTER && !matches.isEmpty()) {
      open(matches.getFirst());
      return true;
    }
    return super.keyPressed(e);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(g, matches.size() + " matching settings · click to open the exact page");
    g.enableScissor(18, 108, width - 18, height - 38);
    for (int i = 0; i < matches.size(); i++) {
      int y = 110 + i * 40 - scroll;
      if (y + 36 < 110 || y > height - 38) continue;
      var h = matches.get(i);
      Ui.surface(
          g,
          20,
          y,
          width - 40,
          35,
          inside(mx, my, 20, y, width - 40, 35) ? 0xff2a394b : CARD,
          BORDER);
      text(g, cut(h.control.caption(), width - 65), 30, y + 5, TEXT);
      text(g, cut(h.route.name + " / " + h.control.page(), width - 65), 30, y + 20, ACCENT);
    }
    g.disableScissor();
    renderChrome(g, mx, my, dt);
  }
}
