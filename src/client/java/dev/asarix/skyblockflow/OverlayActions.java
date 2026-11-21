package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.ui.*;
import java.util.*;
import net.fabricmc.fabric.api.client.screen.v1.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/** Chat's free mouse exposes small actions directly under each visible overlay. */
public final class OverlayActions {
  private record Action(int x, int y, int w, int h, String label, Runnable run) {
    boolean contains(double mx, double my) {
      return mx >= x && mx < x + w && my >= y && my < y + h;
    }
  }

  private static final List<Action> actions = new ArrayList<>();

  private OverlayActions() {}

  public static void register(SkyblockFlowClient app) {
    ScreenEvents.AFTER_INIT.register(
        (mc, screen, width, height) -> {
          if (!(screen instanceof ChatScreen)) return;
          ScreenEvents.afterExtract(screen).register((s, g, mx, my, dt) -> render(app, g, mx, my));
          ScreenMouseEvents.allowMouseClick(screen)
              .register(
                  (s, event) -> {
                    if (app.celebrations.hideOverlays()) {
                      actions.clear();
                      return true;
                    }
                    if (event.button() != 0) return true;
                    for (Action action : List.copyOf(actions))
                      if (action.contains(event.x(), event.y())) {
                        actions.clear();
                        try {
                          action.run.run();
                        } catch (Exception e) {
                          app.error(e.getMessage());
                        }
                        return false;
                      }
                    return true;
                  });
        });
  }

  private static void row(
      SkyblockFlowClient app,
      dev.asarix.skyblockflow.core.HudSettings.Panel panel,
      GuiGraphicsExtractor g,
      JobOverlay.Bounds b,
      String[] labels,
      Runnable[] handlers,
      int mx,
      int my) {
    if (b == null) return;
    var hud = app.store.config.hud;
    var paint = new HudPaint(hud.theme(panel), hud.legacy);
    int x = (int) b.x(), y = (int) Math.min(g.guiHeight() - 44, b.y() + b.height() + 3);
    int total =
        Arrays.stream(labels).mapToInt(t -> paint.width(t) + 12).sum() + 6 * (labels.length - 1);
    int rowHeight = total > b.width() ? 2 : 1;
    if (rowHeight == 2) y = Math.min(y, g.guiHeight() - 64);
    int right = (int) Math.min(g.guiWidth() - 4, b.x() + Math.max(b.width(), 140));
    int startX = x;
    for (int i = 0; i < labels.length; i++) {
      int w = paint.width(labels[i]) + 12;
      if (x + w > right && x != startX) {
        x = startX;
        y += 20;
      }
      Action action = new Action(x, y, w, 18, labels[i], handlers[i]);
      actions.add(action);
      if (hud.legacy)
        Ui.rounded(g, x, y, w, 18, 4, action.contains(mx, my) ? 0xff34485d : 0xee172737);
      else if (paint.theme.buttons == dev.asarix.skyblockflow.core.HudSettings.Buttons.PILL)
        Ui.rounded(
            g,
            x,
            y,
            w,
            18,
            9,
            ((int) (paint.theme.opacity * 2.55f) << 24)
                | (paint.theme.colors.background & 0xffffff));
      else
        g.fill(
            x + 5,
            y + 16,
            x + w - 5,
            y + 17,
            action.contains(mx, my) ? paint.accent() : paint.secondary());
      paint.text(
          g,
          labels[i],
          x + 6,
          y + 5,
          hud.legacy ? 0xffe7edf5 : action.contains(mx, my) ? paint.accent() : paint.ink());
      x += w + 6;
    }
  }

  private static void render(SkyblockFlowClient app, GuiGraphicsExtractor g, int mx, int my) {
    actions.clear();
    var mc = Minecraft.getInstance();
    var parent = mc.gui.screen();
    var discord = DiscordBadge.render(app, g, app.store.config.hud.discordLayout, false, true);
    var call = app.voice.call;
    if (call != null && app.store.config.hud.voice && app.voice.canControl())
      row(
          app,
          dev.asarix.skyblockflow.core.HudSettings.Panel.DISCORD,
          g,
          discord,
          new String[] {
            call.muted() ? "Unmute mic" : "Mute mic", call.deafened() ? "Undeafen" : "Deafen"
          },
          new Runnable[] {() -> app.voice.toggleMute(false), () -> app.voice.toggleMute(true)},
          mx,
          my);
    row(
        app,
        dev.asarix.skyblockflow.core.HudSettings.Panel.JOBS,
        g,
        JobOverlay.render(app, g, app.store.config.jobs, false),
        new String[] {"Settings", "Reset session", "Hide until next session"},
        new Runnable[] {
          () -> mc.gui.setScreen(new JobSettingsScreen(app, parent)),
          () -> {
            var job = app.jobs.tracker.selected(app.store.config.jobs.pinnedJob);
            app.jobs.resetSession(job == null ? "" : job.id);
          },
          () -> app.jobs.hideUntilNextSession()
        },
        mx,
        my);
    row(
        app,
        dev.asarix.skyblockflow.core.HudSettings.Panel.PETS,
        g,
        PetOverlay.render(app, g, app.store.config.pets, false),
        new String[] {"Settings", "Open /pets", "Hide until pet equipped"},
        new Runnable[] {
          () -> mc.gui.setScreen(new PetSettingsScreen(app, parent)),
          () -> {
            mc.gui.setScreen(null);
            if (mc.player != null) mc.player.connection.sendCommand("pets");
          },
          () -> app.pets.hideUntilEquipped()
        },
        mx,
        my);
  }
}
