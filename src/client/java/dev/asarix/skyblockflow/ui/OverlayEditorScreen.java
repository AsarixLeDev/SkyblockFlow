package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/** A transparent canvas; layout changes save when closed. */
public final class OverlayEditorScreen extends FlowScreen {
  private final JobSettings jobs;
  private final PetSettings pets;
  private final HudSettings.Placement[] media;
  private final JobOverlay.Bounds[] bounds =
      new JobOverlay.Bounds[OverlayEditorScreen.NAMES.length];
  private static final String[] NAMES = {
    "Job tracker",
    "Pet tracker",
    "Spotify",
    "Discord",
    "Clock",
    "Minerals",
    "Artisan",
    "Rocket League"
  };
  private static final int[] COLORS = {
    0xff79dfbe, 0xffc3a4ff, 0xff78d19d, 0xff98a1ff, 0xffffd37b, 0xff79dfbe, 0xffb59bff, 0xff59c9fa
  };
  private int selected, dragging = -1, corner = -1;
  private double startX, startY;
  private OverlayLayout.Rect start;
  private float startScale, effectiveScale;

  public OverlayEditorScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Edit overlays");
    jobs = ConfigStore.copy(app.store.config.jobs, JobSettings.class);
    media =
        new HudSettings.Placement[] {
          ConfigStore.copy(app.store.config.hud.music, HudSettings.Music.class),
          ConfigStore.copy(app.store.config.hud.discordLayout, HudSettings.Placement.class),
          app.tools.resolvedLayout(
              net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight(),
              "clock",
              ConfigStore.copy(
                  app.store.config.tools.layout("clock"), HudSettings.Placement.class)),
          app.tools.resolvedLayout(
              net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight(),
              "minerals",
              ConfigStore.copy(
                  app.store.config.tools.layout("minerals"), HudSettings.Placement.class)),
          app.tools.resolvedLayout(
              net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight(),
              "artisan",
              ConfigStore.copy(
                  app.store.config.tools.layout("artisan"), HudSettings.Placement.class)),
          app.tools.resolvedLayout(
              net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight(),
              "esports",
              ConfigStore.copy(
                  app.store.config.tools.layout("esports"), HudSettings.Placement.class))
        };
    pets = ConfigStore.copy(app.store.config.pets, PetSettings.class);
  }

  @Override
  protected void init() {
    super.init();
    dragging = -1;
    int w = Math.min(520, width - 24), x = (width - w) / 2, y = height - 100;
    int bw = (w - 24) / 3;
    for (int i = 0; i < NAMES.length; i++) {
      final int index = i;
      int tabWidth = (w - 12) / 5 - 4;
      button(
          x + 8 + (i % 5) * (tabWidth + 4),
          y + 6 + (i / 5) * 27,
          tabWidth,
          NAMES[i],
          () -> {
            selected = index;
            rebuildWidgets();
          },
          i == selected);
    }
    button(
        x + 8,
        y + 62,
        bw,
        "Reset layout",
        () -> {
          var defaults = dev.asarix.skyblockflow.core.FactoryDefaults.settings();
          var j = defaults.jobs;
          var p = defaults.pets;
          apply(0, new OverlayLayout.Placement(j.x, j.y, j.scale));
          apply(1, new OverlayLayout.Placement(p.x, p.y, p.scale));
          copyLayout(new HudSettings.Music(), media[0]);
          media[1] = HudSettings.badgePlacement();
          for (int index = 0; index < 4; index++)
            copyLayout(
                new ToolSettings()
                    .layout(new String[] {"clock", "minerals", "artisan", "esports"}[index]),
                media[index + 2]);
        });
    button(x + 12 + bw, y + 62, bw, "Back", this::onClose);
    button(x + 16 + bw * 2, y + 62, bw, "Save layout", this::saveLayout, true);
  }

  private float scale(int i) {
    return i == 0 ? jobs.scale : i == 1 ? pets.scale : media[i - 2].scale;
  }

  private final java.util.BitSet changedLayouts = new java.util.BitSet();

  private void apply(int i, OverlayLayout.Placement p) {
    changedLayouts.set(i);
    if (i == 0) {
      jobs.x = p.x();
      jobs.y = p.y();
      jobs.scale = p.scale();
    } else if (i >= 2) {
      var m = media[i - 2];
      m.x = p.x();
      m.y = p.y();
      m.scale = p.scale();
    } else {
      pets.x = p.x();
      pets.y = p.y();
      pets.scale = p.scale();
    }
  }

  private static void copyLayout(JobSettings from, JobSettings to) {
    to.x = from.x;
    to.y = from.y;
    to.scale = from.scale;
  }

  private static void copyLayout(PetSettings from, PetSettings to) {
    to.x = from.x;
    to.y = from.y;
    to.scale = from.scale;
  }

  private static void copyLayout(HudSettings.Placement from, HudSettings.Placement to) {
    to.x = from.x;
    to.y = from.y;
    to.scale = from.scale;
  }

  private boolean layoutSaved;

  private void saveLayout() {
    saveLayout(true);
  }

  @Override
  public void removed() {
    super.removed();
    if (!layoutSaved)
      minecraft.schedule(
          () -> {
            if (minecraft.gui.screen() != this) saveLayout(false);
          });
  }

  private void saveLayout(boolean close) {
    var c = app.store.config;
    var oldJ = ConfigStore.copy(c.jobs, JobSettings.class);
    var oldP = ConfigStore.copy(c.pets, PetSettings.class);
    var music = ConfigStore.copy(c.hud.music, HudSettings.Music.class);
    var badge = ConfigStore.copy(c.hud.discordLayout, HudSettings.Placement.class);
    var oldTools = ConfigStore.copy(c.tools, ToolSettings.class);
    for (int index = 0; index < 4; index++)
      if (changedLayouts.get(index + 4)) copyLayout(
          media[index + 2],
          c.tools.layout(new String[] {"clock", "minerals", "artisan", "esports"}[index]));
    copyLayout(media[0], c.hud.music);
    copyLayout(media[1], c.hud.discordLayout);
    copyLayout(jobs, c.jobs);
    copyLayout(pets, c.pets);
    if (app.save()) {
      changedLayouts.clear();
      layoutSaved = true;
      if (close) minecraft.gui.setScreen(parent);
    } else {
      c.tools.layouts = oldTools.layouts;
      copyLayout(music, c.hud.music);
      copyLayout(badge, c.hud.discordLayout);
      copyLayout(oldJ, c.jobs);
      copyLayout(oldP, c.pets);
      notice = app.lastError;
    }
  }

  @Override
  public void onClose() {
    saveLayout();
  }

  private int handle(int i, double mx, double my) {
    var b = bounds[i];
    if (b == null) return -1;
    for (int k = 0; k < 4; k++) {
      double x = b.x() + (k == 1 || k == 2 ? b.width() : 0);
      double y = b.y() + (k >= 2 ? b.height() : 0);
      if (Math.abs(mx - x) <= 7 && Math.abs(my - y) <= 7) return k;
    }
    return -1;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    boolean resizingSelected = e.button() == 0 && handle(selected, e.x(), e.y()) >= 0;
    if (!resizingSelected && super.mouseClicked(e, twice)) return true;
    if (e.button() != 0) return false;
    for (int n = 0; n < NAMES.length; n++) {
      int i = n == 0 ? selected : NAMES.length - n;
      if (n > 0 && i <= selected) i--;
      if (bounds[i] == null || i != selected && hidden(i)) continue;
      int h = handle(i, e.x(), e.y());
      if (h < 0 && !bounds[i].contains(e.x(), e.y())) continue;
      selected = i;
      corner = h;
      startX = e.x();
      startY = e.y();
      var b = bounds[i];
      start = new OverlayLayout.Rect(b.x(), b.y(), b.width(), b.height());
      startScale = scale(i);
      // A small viewport may display less than the requested scale.
      effectiveScale =
          i >= 4
              ? b.width()
                  / app.tools.previewWidth(
                      new String[] {"clock", "minerals", "artisan", "esports"}[i - 4])
              : i == 2
                  ? b.width() / 226f
                  : i == 3
                      ? b.width()
                          / (app.voice.inCall() && app.store.config.hud.voice
                              ? 154
                              : DiscordBadge.WIDTH)
                      : i == 1 ? b.width() / 274f : jobEffectiveScale(b);
      rebuildWidgets();
      dragging = i;
      return true;
    }
    return false;
  }

  private float jobEffectiveScale(JobOverlay.Bounds b) {
    var job = app.jobs.tracker.selected(jobs.pinnedJob);
    if (job == null) job = JobOverlay.preview();
    int h =
        (jobs.showProgress ? 65 : 36)
            + (JobOverlay.rows(job, jobs, System.currentTimeMillis()).size()
                    + app.closest.rows(job, jobs).size())
                * 16
            + 22;
    return b.height() / h;
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    if (dragging < 0 || e.button() != 0) return false;
    double x = e.x() - startX, y = e.y() - startY;
    apply(
        dragging,
        corner < 0
            ? OverlayLayout.move(start, x, y, startScale, width, height)
            : OverlayLayout.resize(start, effectiveScale, corner, x, y, width, height));
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent e) {
    if (e.button() == 0 && dragging >= 0) {
      dragging = -1;
      return true;
    }
    return super.mouseReleased(e);
  }

  private boolean hidden(int i) {
    var c = app.store.config;
    if (i >= 4)
      return !switch (i - 4) {
        case 0 -> c.tools.clock;
        case 1 -> c.tools.minerals;
        case 2 -> c.tools.artisan;
        default -> c.tools.esports;
      };
    return switch (i) {
      case 0 -> !jobs.showOverlay || !c.enabled(Feature.JOBS) || !c.visible(Feature.JOBS);
      case 2 -> !c.hud.music.enabled;
      case 3 -> !c.hud.discord && !c.hud.voice;
      default -> !pets.showOverlay || !c.enabled(Feature.PETS) || !c.visible(Feature.PETS);
    };
  }

  private void panel(GuiGraphicsExtractor g, int i, int mx, int my) {
    boolean compact = width < 450 || height < 300;
    boolean draw = i == selected || !compact && !hidden(i);
    var b =
        switch (i) {
          case 0 -> JobOverlay.render(app, g, jobs, true, draw);
          case 2 -> SpotifyOverlay.render(app, g, (HudSettings.Music) media[0], true, draw);
          case 3 -> DiscordBadge.render(app, g, media[1], true, draw);
          case 4, 5, 6, 7 ->
              app.tools.renderTool(
                  g,
                  new String[] {"clock", "minerals", "artisan", "esports"}[i - 4],
                  media[i - 2],
                  true,
                  draw);
          default -> PetOverlay.render(app, g, pets, true, draw);
        };
    bounds[i] = b;
    if (b == null || hidden(i) && i != selected) return;
    int x = Math.round(b.x()),
        y = Math.round(b.y()),
        w = Math.round(b.width()),
        h = Math.round(b.height());
    int color = COLORS[i];
    boolean active = i == selected, hover = b.contains(mx, my) || handle(i, mx, my) >= 0;
    if (!draw) color = 0x88000000 | color & 0xffffff;
    if (active || hover) g.fill(x, y, x + w, y + h, active ? 0x129bb6ff : 0x089bb6ff);
    int edge = active ? 2 : 1;
    g.fill(x, y, x + w, y + edge, color);
    g.fill(x, y + h - edge, x + w, y + h, color);
    g.fill(x, y, x + edge, y + h, color);
    g.fill(x + w - edge, y, x + w, y + h, color);
    String label =
        NAMES[i] + (hidden(i) ? " · hidden" : "") + " · " + Math.round(scale(i) * 100) + "%";
    int lw = Math.min(width - 8, Ui.width(label) + 14),
        lx = Math.clamp(x, 4, Math.max(4, width - lw - 4));
    int ly = y >= 25 ? y - 23 : y + 4;
    if (!compact) {
      Ui.rounded(g, lx, ly, lw, 19, 4, 0xee121c29);
      Ui.text(g, Ui.trim(label, lw - 12), lx + 7, ly + 5, color);
    }
    for (int k = 0; k < 4; k++) {
      int hx = x + (k == 1 || k == 2 ? w : 0), hy = y + (k >= 2 ? h : 0);
      int radius = active || handle(i, mx, my) == k ? 4 : 3;
      Ui.rounded(g, hx - radius - 1, hy - radius - 1, radius * 2 + 2, radius * 2 + 2, 2, color);
      g.fill(hx - radius + 1, hy - radius + 1, hx + radius - 1, hy + radius - 1, 0xffeef4ff);
    }
    if (active && dragging >= 0) {
      if (Math.abs(x + w / 2d - width / 2d) <= 3)
        g.fill(width / 2, 0, width / 2 + 1, height, 0x8879dfbe);
      if (Math.abs(y + h / 2d - height / 2d) <= 3)
        g.fill(0, height / 2, width, height / 2 + 1, 0x8879dfbe);
    }
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    if (minecraft.level == null) g.fill(0, 0, width, height, BG);
    for (int i = 0; i < NAMES.length; i++) if (i != selected) panel(g, i, mx, my);
    panel(g, selected, mx, my);
    Ui.rounded(g, 12, height - 118, Math.min(width - 24, 340), 18, 4, 0xdd111b28);
    Ui.text(
        g,
        Ui.trim("Drag panels · resize corners · auto-saves", width - 40),
        20,
        height - 113,
        MUTED);
    int dw = Math.min(520, width - 24);
    Ui.rounded(g, (width - dw) / 2, height - 100, dw, 90, 7, 0xee111b28);
    renderChrome(g, mx, my, dt);
    if (!notice.isEmpty()) Ui.text(g, Ui.trim(notice, width - 24), 12, 58, RED);
  }
}
