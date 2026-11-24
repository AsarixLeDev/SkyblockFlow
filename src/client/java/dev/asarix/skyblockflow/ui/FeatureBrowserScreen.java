package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

public final class FeatureBrowserScreen extends FlowScreen {
  private String query = "", category = "All";
  private boolean keyboardNavigation;
  private List<Feature> matches;
  private static final List<Feature> SORTED =
      Arrays.stream(Feature.values())
          .sorted(Comparator.comparing(f -> f == Feature.OVERLAY ? "" : name(f)))
          .toList();
  private int scroll, selected, top, columns, cardWidth, bottom;
  private int cardHeight = 76;
  private boolean compact;

  @Override
  protected int footerReservedWidth() {
    return compact ? width : 0;
  }

  public FeatureBrowserScreen(SkyblockFlowClient app) {
    this(app, null);
  }

  public FeatureBrowserScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Features");
  }

  public static String name(Feature f) {
    return switch (f) {
      case CHAT -> "Chat alerts";

      case COORDINATES -> "Coordinate sounds";
      case COMMANDS -> "Command shortcuts";

      case OVERLAY -> "Overlays";
      case ALARMS -> "Alarms";

      case LEADERBOARDS -> "Leaderboards";
      case JOBS -> "Job tracker";
      case PETS -> "Pet tracker";
      case TIMERS -> "Timer alarms";
      case SHARED_EDITS -> "Shared edits";
      case WAYPOINTS -> "Waypoints";
      case SPAWNERS -> "Spawner helper";
      case CALCULATOR -> "Calculator";
      case SHOP_VALUES -> "Shop values";
      case CLOCK -> "Clock";
      case MINERALS -> "Minerals";
      case ARTISAN -> "Artisan";
      case ENERGY -> "Energy planner";
      case ESPORTS -> "Rocket League";
      case COMMUNITY_NAMES -> "Community names";
      case ISLAND_FLIGHT -> "Island flight";
    };
  }

  public static String description(Feature f) {
    return switch (f) {
      case CHAT -> "React to messages with custom sounds and titles.";

      case COORDINATES -> "Play a sound when you enter an area.";
      case COMMANDS -> "Run a chat command with a keyboard shortcut.";

      case ALARMS ->
          "Choose titles, action bars and sounds for every alarm, including rocket oxygen.";
      case OVERLAY -> "Style and arrange Spotify, Discord, job, pet and other overlays.";

      case LEADERBOARDS -> "Check job ranks on demand and observe island power.";
      case JOBS -> "Track XP, session pace and time to level 200.";
      case PETS -> "Equipped pet, food timer, inventory food and XP.";
      case TIMERS -> "One-shot or repeating countdowns with custom sounds.";
      case SHARED_EDITS -> "Send music and camera edits to another player.";
      case WAYPOINTS -> "Colored sets, maps and routes to your checkpoints.";
      case SPAWNERS -> "Activation range, clear spawning space and hostile light warnings.";
      case CALCULATOR -> "Decimal arithmetic in inventories and containers.";
      case SHOP_VALUES -> "Level-adjusted unit and stack sale values.";
      case CLOCK -> "Sun, moon and in-game HH:mm clock.";
      case MINERALS -> "Count ore blocks mined this session.";
      case ARTISAN -> "Tools needed for the next level and 20-level milestone.";
      case ENERGY -> "Solar, batteries, nuclear fuel and machine selection.";
      case ESPORTS -> "Live Rocket League series from Liquipedia.";
      case COMMUNITY_NAMES -> "Optional SF badges, name colors, gradients and fonts in tab and chat.";
      case ISLAND_FLIGHT -> "Use /fly enable on your island · VIP and higher · learns /is go and /is tp.";
    };
  }

  public static String category(Feature f) {
    return switch (f) {
      case JOBS, PETS, WAYPOINTS, SPAWNERS, LEADERBOARDS, SHARED_EDITS, ISLAND_FLIGHT -> "Gameplay";
      case ALARMS, CHAT, COORDINATES, TIMERS -> "Alerts";
      default -> "Tools";
    };
  }

  public static int color(Feature f) {
    return switch (category(f)) {
      case "Gameplay" -> 0xff9bb6ff;
      case "Alerts" -> GOLD;
      default -> ACCENT;
    };
  }

  public static boolean enabled(Config c, Feature f) {
    if (f == Feature.COMMUNITY_NAMES)
      return SkyblockFlowClient.INSTANCE != null
          && SkyblockFlowClient.INSTANCE.playerNames != null
          && SkyblockFlowClient.INSTANCE.playerNames.enabled();
    return c.enabled(f)
        && switch (f) {
          case SHARED_EDITS ->
              SkyblockFlowClient.INSTANCE != null
                  && SkyblockFlowClient.INSTANCE.shared != null
                  && SkyblockFlowClient.INSTANCE.shared.settings.enabled;

          case WAYPOINTS -> SkyblockFlowClient.INSTANCE.waypoints.data().enabled;

          case CALCULATOR -> c.tools.calculator;
          case SHOP_VALUES -> c.tools.shopValues;
          case CLOCK -> c.tools.clock;
          case MINERALS -> c.tools.minerals;
          case ARTISAN -> c.tools.artisan;
          case ESPORTS -> c.tools.esports;
          case ISLAND_FLIGHT -> c.tools.islandFlight.enabled && SkyblockFlowClient.INSTANCE.islandFlight.rank() != dev.asarix.skyblockflow.core.IslandFlightLogic.Rank.CITIZEN;
          default -> true;
        };
  }

  private void toggle(Feature f) {
    if (f == Feature.COMMUNITY_NAMES) {
      if (app.playerNames.enabled()) {
        app.playerNames.setEnabled(false);
        notice = "Community names disabled";
      } else open(f);
      return;
    }
    var c = app.store.config;
    boolean on = !enabled(c, f);
    if (f == Feature.ISLAND_FLIGHT && !app.islandFlight.setEnabled(on)) { notice = app.islandFlight.status; return; }
    if (f == Feature.SHARED_EDITS && on) {
      var draft = dev.asarix.skyblockflow.core.ConfigStore.copy(app.shared.settings, dev.asarix.skyblockflow.SharedEdits.Settings.class);
      minecraft.gui.setScreen(new SharedEditTrustScreen(app, this, draft.relayUrl, () -> {
        draft.acceptedRelay = draft.relayUrl;
        draft.enabled = true;
        app.shared.configure(draft);
        app.save();
      }));
      return;
    }
    c.feature(f).enabled = on;
    switch (f) {
      case SHARED_EDITS -> {
        app.shared.settings.enabled = on;
        app.shared.save();
        app.shared.disconnect();
      }
      case WAYPOINTS -> {
        app.waypoints.data().enabled = on;
        app.waypoints.changed();
      }

      case CALCULATOR -> c.tools.calculator = on;
      case SHOP_VALUES -> c.tools.shopValues = on;
      case CLOCK -> c.tools.clock = on;
      case MINERALS -> c.tools.minerals = on;
      case ARTISAN -> c.tools.artisan = on;
      case ESPORTS -> c.tools.esports = on;
      default -> {}
    }
    app.save();
    notice = name(f) + (on ? " enabled" : " disabled");
  }

  private List<Feature> visible() {
    if (matches != null) return matches;
    String q = query.toLowerCase(Locale.ROOT).strip();
    return matches =
        SORTED.stream()
            .filter(f -> app.store.config.visible(f))
            .filter(f -> category.equals("All") || category(f).equals(category))
            .filter(f -> (name(f) + " " + description(f)).toLowerCase(Locale.ROOT).contains(q))
            .toList();
  }

  public void open(Feature f) {
    minecraft.gui.setScreen(
        switch (f) {
          case LEADERBOARDS -> new LeaderboardsScreen(app, this);
          case ALARMS -> new AlarmHub(app, this);
          case OVERLAY -> new OverlayHub(app, this);
          case COMMUNITY_NAMES -> new PlayerNamesScreen(app, this);
          case ISLAND_FLIGHT -> new IslandFlightScreen(app, this);
          case WAYPOINTS -> new WaypointScreen(app, this);
          case SPAWNERS -> new SpawnerSettingsScreen(app, this);
          case ENERGY -> new EnergyScreen(app, this);
          case CALCULATOR, SHOP_VALUES, CLOCK, MINERALS, ARTISAN, ESPORTS ->
              new ToolsScreen(app, this, name(f));

          case JOBS -> new JobSettingsScreen(app, this);
          case PETS -> new PetSettingsScreen(app, this);
          case TIMERS -> new TimerSettingsScreen(app, this);
          case SHARED_EDITS -> new SharedEditsScreen(app, this);

          case COMMANDS -> new FeatureListScreen(app, this, false);
          case COORDINATES -> new FeatureListScreen(app, this, true);
          default -> new FeatureSettingsScreen(app, this, f);
        });
  }

  @Override
  protected void init() {
    super.init();
    matches = null;
    button(width - 40, 10, 26, "Close", this::onClose);
    button(
        width - 166,
        10,
        120,
        "Edit overlays",
        () -> minecraft.gui.setScreen(new OverlayEditorScreen(app, this)));
    compact = height < 300;
    cardHeight = compact ? 44 : 62;
    boolean wide = width >= 560;
    columns = width >= 900 ? 3 : wide ? 2 : 1;
    top = compact ? 100 : 136;
    bottom = height - (compact ? 36 : 68);
    cardWidth = (width - 40 - (columns - 1) * 10) / columns;
    EditBox search = edit(20, compact ? 40 : 76, width - 40, query);
    search.setHint(Ui.label("Search all settings, including nested options…"));
    search.setTextShadow(false);
    search.setResponder(
        s -> {
          query = s;
          if (!s.isBlank())
            minecraft.schedule(
                () -> {
                  if (minecraft.gui.screen() == this)
                    minecraft.gui.setScreen(new SettingSearchScreen(app, this, s));
                });
          matches = null;
          scroll = 0;
          selected = 0;
        });
    String[] categories = {"All", "Gameplay", "Alerts", "Tools"};
    int catX = 20, catW = (width - 52) / 4;
    for (String name : categories) {
      button(
          catX,
          compact ? 70 : 104,
          catW,
          name,
          () -> {
            category = name;
            scroll = 0;
            selected = 0;
            rebuildWidgets();
          },
          category.equals(name));
      catX += catW + 4;
    }
    int w = (width - 52) / 4;
    button(
        20,
        height - (compact ? 28 : 58),
        w,
        "Settings",
        () -> minecraft.gui.setScreen(new GeneralSettingsScreen(app, this)));
    button(
        24 + w,
        height - (compact ? 28 : 58),
        w,
        "Activity",
        () -> minecraft.gui.setScreen(new DiagnosticsScreen(app, this)));
    button(
        28 + w * 2,
        height - (compact ? 28 : 58),
        w,
        "F11 visibility",
        () -> minecraft.gui.setScreen(new VisibilityScreen(app, this)));
    button(32 + w * 3, height - (compact ? 28 : 58), w, "Recipes",
        () -> app.recipes.open(this, null, false, false), true);
    clamp();
  }

  private void clamp() {
    scroll =
        Math.clamp(
            scroll,
            0,
            Math.max(
                0, ((visible().size() + columns - 1) / columns) * cardHeight - (bottom - top)));
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll -= (int) (sy * 30);
    clamp();
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (super.mouseClicked(e, twice)) return true;
    if (e.button() != 0 || e.y() < top || e.y() >= bottom) return false;
    var list = visible();
    for (int i = 0; i < list.size(); i++) {
      int x = 20 + (i % columns) * (cardWidth + 10), y = top + (i / columns) * cardHeight - scroll;
      if (!inside(e.x(), e.y(), x, y, cardWidth, cardHeight - 8)) continue;
      selected = i;
      keyboardNavigation = false;
      if (list.get(i) != Feature.ALARMS
          && list.get(i) != Feature.OVERLAY
          && inside(e.x(), e.y(), x + cardWidth - 44, y + 10, 30, 18)) toggle(list.get(i));
      else open(list.get(i));
      return true;
    }
    return false;
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (getFocused() instanceof EditBox box
        && box.isFocused()
        && e.key() != GLFW.GLFW_KEY_ENTER
        && e.key() != GLFW.GLFW_KEY_DOWN
        && e.key() != GLFW.GLFW_KEY_UP) return super.keyPressed(e);
    var list = visible();
    if (e.key() == GLFW.GLFW_KEY_ENTER && !list.isEmpty()) {
      open(list.get(Math.min(selected, list.size() - 1)));
      return true;
    }
    if (e.key() == GLFW.GLFW_KEY_SPACE && !list.isEmpty()) {
      toggle(list.get(Math.min(selected, list.size() - 1)));
      return true;
    }
    int delta =
        switch (e.key()) {
          case GLFW.GLFW_KEY_DOWN -> columns;
          case GLFW.GLFW_KEY_UP -> -columns;
          case GLFW.GLFW_KEY_RIGHT -> 1;
          case GLFW.GLFW_KEY_LEFT -> -1;
          default -> 0;
        };
    if (delta != 0) {
      keyboardNavigation = true;
      selected = Math.clamp(selected + delta, 0, Math.max(0, list.size() - 1));
      scroll = Math.max(0, (selected / columns) * cardHeight - (bottom - top - cardHeight));
      clamp();
      return true;
    }
    return super.keyPressed(e);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(
        g, compact ? "" : "Choose a feature to configure it. Use its switch to turn it on or off.");
    clamp();
    g.enableScissor(18, top, width - 14, bottom);
    var list = visible();
    for (int i = 0; i < list.size(); i++) {
      var f = list.get(i);
      int x = 20 + (i % columns) * (cardWidth + 10), y = top + (i / columns) * cardHeight - scroll;
      boolean hover = inside(mx, my, x, y, cardWidth, cardHeight - 8) && my >= top && my < bottom;
      Ui.surface(
          g,
          x,
          y,
          cardWidth,
          cardHeight - 8,
          hover ? 0xff263244 : CARD,
          hover || keyboardNavigation && i == selected ? color(f) : BORDER);
      Ui.rounded(g, x + 12, y + 12, 3, 14, 1, color(f));
      text(g, cut(name(f), cardWidth - 78), x + 23, y + 14, TEXT);
      if (f != Feature.ALARMS && f != Feature.OVERLAY)
        Ui.toggle(g, x + cardWidth - 42, y + 12, enabled(app.store.config, f));
      if (!compact) text(g, cut(description(f), cardWidth - 26), x + 13, y + 34, MUTED);
      if (hover && (compact || Ui.width(description(f)) > cardWidth - 26))
        g.setTooltipForNextFrame(Ui.label(description(f)), mx, my);
    }
    if (list.isEmpty()) {
      text(g, "No matching features", 28, top + 20, TEXT);
      text(g, "Try another search or press F11 to restore hidden features.", 28, top + 40, MUTED);
    }
    g.disableScissor();
    Ui.scrollBar(
        g,
        width - 9,
        top,
        bottom - top,
        ((list.size() + columns - 1) / columns) * cardHeight,
        scroll);
    renderChrome(g, mx, my, dt);
  }
}
