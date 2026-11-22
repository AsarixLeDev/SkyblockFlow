package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import dev.asarix.skyblockflow.ui.*;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class GameplayTools {
  public final SkyblockFlowClient app;
  public final Map<String, Long> mined = new LinkedHashMap<>();
  public final EnergySelector energy;
  private final DailyClaimsReminder dailyClaims;
  public final RocketLeagueFeed esports = new RocketLeagueFeed();
  private static final List<String> MINERALS = List.of(
      "coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald", "quartz");
  private static final LiquipediaMatches.Match ESPORTS_PREVIEW = new LiquipediaMatches.Match(
      "Blue team", "Orange team", "BO5: 2 – 1", "Game: 1 – 0", "", "", "Rocket League · Preview");
  private final TeamLogos logos = new TeamLogos();
  private long lastDebris;

  private record Pending(BlockState state, net.minecraft.client.multiplayer.ClientLevel level, long expires) {}

  private final Map<net.minecraft.core.BlockPos, Pending> pending = new HashMap<>();

  public void miningStarted(net.minecraft.core.BlockPos pos, BlockState state) {
    // A server-side skill can break the block before the client ever predicts its removal.
    rememberMining(pos, state, 1_500_000_000L);
  }

  public void attempted(net.minecraft.core.BlockPos pos, BlockState state) {
    rememberMining(pos, state, 5_000_000_000L);
  }

  private void rememberMining(net.minecraft.core.BlockPos pos, BlockState state, long duration) {
    if (mineral(state) != null)
      pending.put(pos.immutable(),
          new Pending(state, Minecraft.getInstance().level, System.nanoTime() + duration));
  }

  public void confirmed(net.minecraft.core.BlockPos pos, BlockState state) {
    var old = pending.get(pos);
    if (old == null) return;
    if (old.level() != Minecraft.getInstance().level || old.expires() <= System.nanoTime()) {
      pending.remove(pos);
    } else if (old.state().getBlock() != state.getBlock()) {
      pending.remove(pos);
      mined(old.state());
    }
  }

  public GameplayTools(SkyblockFlowClient app) {
    this.app = app;
    energy = new EnergySelector(app);
    dailyClaims = new DailyClaimsReminder(app);
    InventoryCalculator.register(app);
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
        .register(mc -> logos.close());
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, mc) -> {
          mined.clear();
          pending.clear();
        });
    ClientTickEvents.END_CLIENT_TICK.register(
        mc -> {
          dailyClaims.tick();
          if (!pending.isEmpty()) {
            long now = System.nanoTime();
            pending.values().removeIf(p -> p.level() != mc.level || p.expires() <= now);
          }
          energy.tick(mc);
          esports.tick(mc.level != null && app.store.config.tools.esports);
        });
    RecipeHelper.addBackgroundHud(
        Identifier.fromNamespaceAndPath("skyblockflow", "gameplay_tools"), (g, dt) -> render(g));
    net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register(
        (stack, context, flag, lines) -> tooltip(stack, lines));
    net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registry) -> {
          dispatcher.register(
              com.mojang.brigadier.builder.LiteralArgumentBuilder
                  .<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal(
                      "skyblockflowartisan")
                  .executes(
                      ctx -> {
                        for (String line : artisan())
                          ctx.getSource().sendFeedback(dev.asarix.skyblockflow.ui.DisplayText.literal(line));
                        return 1;
                      }));
          dispatcher.register(
              com.mojang.brigadier.builder.LiteralArgumentBuilder
                  .<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal(
                      "skyblockflowenergy")
                  .executes(
                      ctx -> {
                        Minecraft.getInstance()
                            .schedule(
                                () ->
                                    Minecraft.getInstance()
                                        .gui
                                        .setScreen(new EnergyScreen(app, null)));
                        return 1;
                      }));
        });
  }

  void reloadPreferences() {
    dailyClaims.reload();
  }

  public List<String> artisan() {
    return ArtisanMath.rows(
        app.jobs.tracker.data.jobs.get("artisan"), app.store.config.tools.artisanTiers);
  }

  private static String mineral(BlockState state) {
    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    if (id.equals("ancient_debris")) return id;
    if (id.endsWith("_ore")) {
      for (String key : MINERALS) if (id.endsWith(key + "_ore")) return key;
    }
    return null;
  }

  public void mined(BlockState state) {
    String key = mineral(state);
    if (key != null) mined.merge(key, 1L, Long::sum);
  }

  public void miningXp() {
    var mc = Minecraft.getInstance();
    var t = app.store.config.tools;
    if (!t.ancientDebris
        || mc.level == null
        || !(mc.hitResult instanceof BlockHitResult hit)
        || !mc.level
            .getBlockState(hit.getBlockPos())
            .is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS)
        || System.nanoTime() - lastDebris < 5_000_000_000L) return;
    lastDebris = System.nanoTime();
    app.notifyGeneral(
        "Ancient debris",
        "Ancient debris",
        "Mining XP detected while aiming at ancient debris",
        new SoundSettings());
  }

  private void tooltip(ItemStack stack, List<Component> lines) {
    if (!app.store.config.tools.shopValues || app.recipes == null || !app.recipes.onExcalia()) return;
    for (String price : app.recipes.shopLines(stack, true))
      lines.add(dev.asarix.skyblockflow.ui.DisplayText.literal(price).withColor(0xffc578));
  }

  public static Item icon(String key) {
    return switch (key) {
      case "coal" -> Items.COAL;
      case "iron" -> Items.RAW_IRON;
      case "copper" -> Items.RAW_COPPER;
      case "gold" -> Items.RAW_GOLD;
      case "redstone" -> Items.REDSTONE;
      case "lapis" -> Items.LAPIS_LAZULI;
      case "diamond" -> Items.DIAMOND;
      case "emerald" -> Items.EMERALD;
      case "quartz" -> Items.QUARTZ;
      default -> Items.ANCIENT_DEBRIS;
    };
  }

  private boolean visible(Feature feature) {
    return app.store.config.enabled(feature) && app.store.config.visible(feature);
  }

  public void render(GuiGraphicsExtractor g) {
    var mc = Minecraft.getInstance();
    if (mc.player == null
        || mc.gui.hud.isHidden()
        || app.celebrations.hideOverlays()
        || mc.gui.screen() instanceof OverlayEditorScreen) return;
    var t = app.store.config.tools;
    if (t.clock && visible(Feature.CLOCK)) renderTool(g, "clock", t.layout("clock"), false, true);
    if (t.minerals && visible(Feature.MINERALS))
      renderTool(g, "minerals", t.layout("minerals"), false, true);
    if (t.artisan && visible(Feature.ARTISAN))
      renderTool(g, "artisan", t.layout("artisan"), false, true);
    if (t.esports && visible(Feature.ESPORTS) && (!t.esportsHideInactive || esports.current() != null))
      renderTool(g, "esports", t.layout("esports"), false, true);
    energy.renderLabels(g);
  }

  public HudSettings.Placement resolvedLayout(
      int guiHeight, String key, HudSettings.Placement stored) {
    var defaults = new ToolSettings().layout(key);
    if (Math.abs(stored.x - defaults.x) > 1e-8
        || Math.abs(stored.y - defaults.y) > 1e-8
        || stored.scale != 1
        || key.equals("esports")) return stored;
    var adjusted = ConfigStore.copy(stored, HudSettings.Placement.class);
    float scale = guiHeight < 320 ? .75f : 1;
    adjusted.scale = scale;
    double top = 10,
        clockHeight = 31 * scale,
        mineralHeight = (28 + ((app.store.config.tools.tracked.size() + 1) / 2) * 22) * scale;
    double height =
        key.equals("clock")
            ? clockHeight
            : key.equals("minerals") ? mineralHeight : (artisan().size() * 15 + 16) * scale;
    double y =
        key.equals("clock")
            ? top
            : key.equals("minerals")
                ? top + clockHeight + 10
                : top + clockHeight + 10 + mineralHeight + 12;
    adjusted.y = Math.clamp(y / Math.max(1, guiHeight - height), 0, 1);
    return adjusted;
  }

  public JobOverlay.Bounds renderTool(
      GuiGraphicsExtractor g,
      String key,
      HudSettings.Placement layout,
      boolean preview,
      boolean draw) {
    var mc = Minecraft.getInstance();
    var t = app.store.config.tools;
    if (!preview) layout = resolvedLayout(g.guiHeight(), key, layout);
    if (key.equals("minerals")) return mineralPanel(g, layout, draw);
    if (key.equals("esports")) {
      var match = preview ? ESPORTS_PREVIEW : esports.current();
      if (match != null) return esportsPanel(g, layout, draw, match, preview);
    }
    var theme =
        ConfigStore.copy(
            app.store.config.hud.theme(
                HudSettings.Panel.valueOf(key.toUpperCase(java.util.Locale.ROOT))),
            HudSettings.Theme.class);
    List<String> rows;
    if (key.equals("clock")) {
      long tick = mc.level == null ? 6000 : mc.level.getOverworldClockTime();
      boolean day = Math.floorMod(tick, 24000) < 12000;
      if (t.fancyClock) {
        theme.colors.accent = day ? 0xffd37b : 0x9cb7ff;
        theme.colors.background = day ? 0x40301a : 0x15253f;
      }
      rows = List.of((day ? "☀  " : "☾  ") + EnergyPlan.time(tick));
    } else if (key.equals("artisan"))
      rows =
          preview && app.jobs.tracker.data.jobs.get("artisan") == null
              ? List.of(
                  "Artisan · preview", "Lv. 100 tools: 12 / 128", "Next level / next milestone")
              : artisan();
    else rows = esports.rows(t.esportsShowUpdated);
    return panel(g, layout, rows, theme, draw);
  }

  public JobOverlay.Bounds panel(
      GuiGraphicsExtractor g,
      HudSettings.Placement l,
      List<String> rows,
      HudSettings.Theme theme,
      boolean draw) {
    var p = new HudPaint(theme);
    int w = Math.max(130, rows.stream().mapToInt(p::width).max().orElse(0) + 24),
        h = rows.size() * 15 + 16;
    w = Math.min(w, 300);
    var r = OverlayLayout.bounds(g.guiWidth(), g.guiHeight(), w, h, l.x, l.y, l.scale);
    if (draw) {
      g.pose().pushMatrix();
      g.pose().translate((float) r.x(), (float) r.y());
      g.pose().scale((float) r.width() / w, (float) r.width() / w);
      p.panel(g, 0, 0, w, h);
      for (int i = 0; i < rows.size(); i++)
        p.text(g, p.trim(rows.get(i), w - 24), 12, (h - ((rows.size() - 1) * 15 + 9)) / 2 + i * 15, i == 0 ? p.accent() : p.ink());
      g.pose().popMatrix();
    }
    return new JobOverlay.Bounds(
        (float) r.x(), (float) r.y(), (float) r.width(), (float) r.height());
  }

  public int previewWidth(String key) {
    if (key.equals("clock")) return 130;
    if (key.equals("minerals")) return 174;
    if (key.equals("esports")) return 270;
    var p =
        new HudPaint(
            app.store.config.hud.theme(
                HudSettings.Panel.valueOf(key.toUpperCase(java.util.Locale.ROOT))));
    var rows = app.jobs.tracker.data.jobs.get("artisan") == null
        ? List.of("Artisan · preview", "Lv. 100 tools: 12 / 128", "Next level / next milestone")
        : artisan();
    return Math.min(300, Math.max(130, rows.stream().mapToInt(p::width).max().orElse(0) + 24));
  }

  private JobOverlay.Bounds esportsPanel(
      GuiGraphicsExtractor g, HudSettings.Placement l, boolean draw,
      LiquipediaMatches.Match m, boolean preview) {
    var p = new HudPaint(app.store.config.hud.theme(HudSettings.Panel.ESPORTS));
    int w = 270, h = (m.live() ? 140 : 113) + (app.store.config.tools.esportsShowUpdated ? 15 : 0);
    var r = OverlayLayout.bounds(g.guiWidth(), g.guiHeight(), w, h, l.x, l.y, l.scale);
    if (draw) {
      g.pose().pushMatrix();
      g.pose().translate((float) r.x(), (float) r.y());
      g.pose().scale((float) r.width() / w, (float) r.width() / w);
      p.panel(g, 0, 0, w, h);
      p.text(g, "ROCKET LEAGUE", 12, 10, p.accent());
      p.text(g, p.trim(m.event().isBlank() ? "Rocket League" : m.event(), w - 24), 12, 26, p.secondary());
      p.text(g, preview ? "LIVE · Preview" : esports.when(m), 12, 40, p.accent());
      for (int i = 0; i < 2; i++) {
        int y = 54 + i * 24;
        drawTeamLogo(g, p, logos.get(i == 0 ? m.logoLeft() : m.logoRight()), 12, y, i == 0);
        p.text(g, p.trim(i == 0 ? m.left() : m.right(), w - 56), 40, y + 5, p.ink());
      }
      if (m.live()) {
        p.text(g, m.series(), 12, 104, p.accent());
        p.text(g, p.trim(m.game(), w - 24), 12, 119, p.ink());
      }
      p.text(g, "liquipedia.net", w - p.width("liquipedia.net") - 12, h - 12, p.secondary());
      if (app.store.config.tools.esportsShowUpdated) p.text(g, esports.age(), 12, h - 12, p.secondary());
      g.pose().popMatrix();
    }
    return new JobOverlay.Bounds(
        (float) r.x(), (float) r.y(), (float) r.width(), (float) r.height());
  }

  static void drawTeamLogo(GuiGraphicsExtractor g, HudPaint paint, TeamLogos.Logo logo,
                           int x, int y, boolean left) {
    final int size = 18;
    if (logo != null) {
      float scale = (float) size / Math.max(logo.width(), logo.height());
      int width = Math.max(1, Math.round(logo.width() * scale));
      int height = Math.max(1, Math.round(logo.height() * scale));
      int x0 = x + (size - width) / 2, y0 = y + (size - height) / 2;
      g.blit(logo.texture(), x0, y0, x0 + width, y0 + height, 0f, 1f, 0f, 1f);
    } else {
      Ui.rounded(g, x, y, size, size, 5, left ? 0xff304a64 : 0xff5a4030);
      paint.text(g, "?", x + (size - paint.width("?")) / 2, y + 5, 0xfff0f4fa);
    }
  }

  private JobOverlay.Bounds mineralPanel(
      GuiGraphicsExtractor g, HudSettings.Placement l, boolean draw) {
    var keys =
        app.store.config.tools.tracked.stream()
            .filter(
                k ->
                    List.of(
                            "coal",
                            "iron",
                            "copper",
                            "gold",
                            "redstone",
                            "lapis",
                            "diamond",
                            "emerald",
                            "quartz",
                            "ancient_debris")
                        .contains(k))
            .toList();
    var p = new HudPaint(app.store.config.hud.theme(HudSettings.Panel.MINERALS));
    int column = Math.max(82, keys.stream().mapToInt(k -> p.width(Numbers.integer(mined.getOrDefault(k, 0L))) + 30).max().orElse(82));
    int w = column * 2 + 10, h = 28 + ((keys.size() + 1) / 2) * 22;
    var r = OverlayLayout.bounds(g.guiWidth(), g.guiHeight(), w, h, l.x, l.y, l.scale);
    if (draw) {
      g.pose().pushMatrix();
      g.pose().translate((float) r.x(), (float) r.y());
      g.pose().scale((float) r.width() / w, (float) r.width() / w);
      p.panel(g, 0, 0, w, h);
      p.text(g, "MINED THIS SESSION", 12, 10, p.accent());
      for (int i = 0; i < keys.size(); i++) {
        int x = 10 + i % 2 * column, y = 26 + i / 2 * 22;
        if (Minecraft.getInstance().level != null) g.item(new ItemStack(icon(keys.get(i))), x, y);
        else
          p.text(
              g,
              keys.get(i).substring(0, 1).toUpperCase(java.util.Locale.ROOT),
              x + 5,
              y + 5,
              p.accent());
        p.text(g, Numbers.integer(mined.getOrDefault(keys.get(i), 0L)), x + 22, y + 5, p.ink());
      }
      g.pose().popMatrix();
    }
    return new JobOverlay.Bounds(
        (float) r.x(), (float) r.y(), (float) r.width(), (float) r.height());
  }
}
