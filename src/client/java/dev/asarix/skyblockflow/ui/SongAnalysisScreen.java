package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Editable annotations stay in original audio time; preview uses the draft without saving it. */
public final class SongAnalysisScreen extends FlowScreen {
  private final SongAnalysis wave;
  private final float energyMaximum;
  private Set<Long> originalTimes = Set.of();
  private final CelebrationSettings settings;
  private final Runnable onSaved;
  private HitTimeline draft, saved;
  private SongAnalysis automatic;
  private final Deque<HitTimeline> undo = new ArrayDeque<>();
  private BassAnalyzer playback;
  private Runnable stop = () -> {};
  private boolean loading, busy, dirty, dragging, dragSnapshot;
  private long selected = -1, viewStart;
  private double viewSpan;
  private EditBox timestamp;

  public SongAnalysisScreen(
      SkyblockFlowClient app, Screen parent, CelebrationSettings settings, SongAnalysis song) {
    this(app, parent, settings, song, () -> {});
  }

  public SongAnalysisScreen(
      SkyblockFlowClient app,
      Screen parent,
      CelebrationSettings settings,
      SongAnalysis song,
      Runnable onSaved) {
    super(app, parent, "Song · edit bass hits");
    this.settings = ConfigStore.copy(settings, CelebrationSettings.class);
    this.wave = song;
    float max = .00001f;
    for (float value : song.energy()) max = Math.max(max, value);
    energyMaximum = max;
    this.onSaved = onSaved;
    viewSpan = song.durationMs();
    this.settings.edit.enabled = true;
  }

  public boolean hasUnsavedChanges() {
    return dirty;
  }

  public HitTimeline draftTimeline() {
    return draft == null ? null : draft.copy();
  }

  private List<SongAnalysis.Hit> hits() {
    return draft == null ? wave.hits() : draft.hits;
  }

  private int gx() {
    return 18;
  }

  private int gy() {
    return height < 340 ? 100 : 116;
  }

  private int gw() {
    return width - 36;
  }

  private int gh() {
    return Math.max(12, height - gy() - 142);
  }

  private long at(double x) {
    return Math.clamp(Math.round(viewStart + (x - gx()) / gw() * viewSpan), 0, wave.durationMs());
  }

  private int px(long t) {
    return gx() + (int) Math.round((t - viewStart) * gw() / viewSpan);
  }

  private boolean ready() {
    return draft != null && !busy;
  }

  @Override
  protected void init() {
    super.init();
    String[] labels = {"Undo", "+ Zoom", "− Zoom", "Fit", "Earlier", "Later", "Files"};
    Runnable[] actions = {
      this::undo,
      () -> zoom(.5, .5),
      () -> zoom(2, .5),
      () -> {
        viewStart = 0;
        viewSpan = wave.durationMs();
      },
      () -> pan(-.7),
      () -> pan(.7),
      () -> {
        if (ready())
          minecraft.gui.setScreen(new SongTimelineTransferScreen(app, this, settings, draft));
      }
    };
    int w = (width - 36 - 6 * 4) / 7;
    for (int i = 0; i < labels.length; i++) button(18 + i * (w + 4), 72, w, labels[i], actions[i]);
    int y = height - 104, small = (width - 36 - 5 * 4) / 6;
    timestamp = edit(18, y, small, selected < 0 ? "0.000" : seconds(selected));
    timestamp.setMessage(Component.literal("Original song time (seconds)"));
    button(22 + small, y, small, "Add", () -> changeAtField(false));
    button(26 + small * 2, y, small, "Move", () -> changeAtField(true));
    button(30 + small * 3, y, small, "Remove", () -> remove(selected));
    button(34 + small * 4, y, small, "Reset hits", this::reset);
    button(
        38 + small * 5,
        y,
        small,
        "Reviewed: " + (draft != null && draft.fullyReviewed ? "ON" : "OFF"),
        () -> {
          if (!ready()) return;
          remember();
          draft.fullyReviewed = !draft.fullyReviewed;
          changed();
          rebuildWidgets();
        });
    String[] bottom = {"Play / restart", "Stop", "Add at playhead", "Save hits", "Back"};
    Runnable[] run = {
      this::play,
      () -> {
        stop.run();
        playback = null;
      },
      () -> {
        if (playback != null) add(Math.round(playback.positionMs() * settings.pitch));
      },
      this::save,
      this::onClose
    };
    w = (width - 36 - 16) / 5;
    for (int i = 0; i < bottom.length; i++)
      button(18 + i * (w + 4), height - 65, w, bottom[i], run[i], i == 3);
    if (draft == null && !loading) {
      loading = true;
      notice = "Loading saved corrections...";
      app.sounds
          .songTimeline(settings)
          .thenCombine(
              app.sounds.automaticSong(settings),
              (doc, auto) -> {
                minecraft.execute(
                    () -> {
                      draft = doc;
                      saved = doc.copy();
                      automatic = auto;
                      indexOriginal();
                      loading = false;
                      notice =
                          "Left-click: add/select · drag: move · right-click: remove · wheel: zoom";
                      if (minecraft.gui.screen() == this) rebuildWidgets();
                    });
                return doc;
              })
          .exceptionally(
              error -> {
                minecraft.execute(
                    () -> {
                      loading = false;
                      notice = error(error);
                    });
                return null;
              });
    }
  }

  private static String seconds(long ms) {
    return String.format(Locale.ROOT, "%.3f", ms / 1000d);
  }

  public static String time(long ms) {
    return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000);
  }

  static String error(Throwable error) {
    while (error.getCause() != null) error = error.getCause();
    return error.getMessage() == null ? error.toString() : error.getMessage();
  }

  private void remember() {
    if (undo.size() >= 50) undo.removeFirst();
    undo.addLast(draft.copy());
  }

  private void indexOriginal() {
    var times = new HashSet<Long>();
    for (var hit : draft.detectedHits) times.add(hit.timeMs());
    originalTimes = times;
  }

  private void changed() {
    indexOriginal();
    draft.updateEdits();
    dirty = !draft.hits.equals(saved.hits) || draft.fullyReviewed != saved.fullyReviewed;
    if (playback != null) playback.usePreviewAnalysis(wave.withHits(draft.hits));
    notice =
        dirty
            ? "Unsaved corrections · Save hits applies them to future celebrations."
            : "Corrections saved";
  }

  private void undo() {
    if (!ready() || undo.isEmpty()) return;
    draft = undo.removeLast();
    selected = -1;
    changed();
    rebuildWidgets();
  }

  private long nearest(long t) {
    var h =
        hits().stream()
            .min(Comparator.comparingLong(hit -> Math.abs(hit.timeMs() - t)))
            .orElse(null);
    return h != null && Math.abs(h.timeMs() - t) <= viewSpan * 7 / gw() ? h.timeMs() : -1;
  }

  private void select(long t) {
    selected = t;
    timestamp.setValue(seconds(t));
  }

  private void add(long t) {
    if (!ready() || draft.hits.size() >= 50000) return;
    t = Math.clamp(t, 0, wave.durationMs());
    for (var h : draft.hits)
      if (h.timeMs() == t) {
        select(t);
        return;
      }
    remember();
    draft.hits.add(new SongAnalysis.Hit(t, 1));
    draft.hits.sort(Comparator.comparingLong(SongAnalysis.Hit::timeMs));
    select(t);
    changed();
  }

  private void remove(long t) {
    if (!ready() || t < 0) return;
    remember();
    draft.hits.removeIf(h -> h.timeMs() == t);
    selected = -1;
    changed();
  }

  private void move(long t) {
    if (!ready() || selected < 0 || t == selected) return;
    long old = selected;
    float strength = 1;
    for (var h : draft.hits) {
      if (h.timeMs() == t) return;
      if (h.timeMs() == old) strength = h.strength();
    }
    draft.hits.removeIf(h -> h.timeMs() == old);
    draft.hits.add(new SongAnalysis.Hit(t, strength));
    draft.hits.sort(Comparator.comparingLong(SongAnalysis.Hit::timeMs));
    select(t);
    changed();
  }

  private void changeAtField(boolean moving) {
    try {
      double value = Double.parseDouble(timestamp.getValue());
      if (!Double.isFinite(value) || value < 0 || value * 1000 > wave.durationMs())
        throw new IllegalArgumentException("Time must be inside the song, in original seconds");
      long t = Math.round(value * 1000);
      if (moving) {
        if (ready() && selected >= 0) {
          remember();
          move(t);
        }
      } else add(t);
    } catch (Exception error) {
      notice = error.getMessage();
    }
  }

  private void zoom(double factor, double anchor) {
    double middle = viewStart + viewSpan * anchor;
    viewSpan = Math.clamp(viewSpan * factor, Math.min(500, wave.durationMs()), wave.durationMs());
    viewStart =
        Math.clamp(
            Math.round(middle - viewSpan * anchor),
            0,
            Math.max(0, (long) (wave.durationMs() - viewSpan)));
  }

  private void pan(double amount) {
    viewStart =
        Math.clamp(
            Math.round(viewStart + viewSpan * amount),
            0,
            Math.max(0, (long) (wave.durationMs() - viewSpan)));
  }

  private void play() {
    if (!ready()) return;
    stop.run();
    playback = new BassAnalyzer(settings.edit);
    playback.usePreviewAnalysis(wave.withHits(draft.hits));
    stop = app.sounds.playCelebration(settings, playback, true);
  }

  private boolean closeWhenSaved;

  private void save() {
    if (!ready()) return;
    busy = true;
    var snapshot = draft.copy();
    notice = "Saving corrections...";
    app.sounds
        .saveTimeline(settings.sound, snapshot)
        .whenComplete(
            (v, error) ->
                minecraft.execute(
                    () -> {
                      busy = false;
                      if (error != null) {
                        notice = error(error);
                        return;
                      }
                      saved = snapshot;
                      changed();
                      onSaved.run();
                      if (closeWhenSaved && !dirty) {
                        stop.run();
                        minecraft.gui.setScreen(parent);
                      }
                    }));
  }

  private void reset() {
    if (!ready() || automatic == null) return;
    minecraft.gui.setScreen(
        new ConfirmScreen(
            yes -> {
              minecraft.gui.setScreen(this);
              if (yes) {
                remember();
                draft.hits = new ArrayList<>(automatic.hits());
                draft.detectedHits = new ArrayList<>(automatic.hits());
                draft.detectorVersion = SongAnalysisCache.VERSION;
                draft.detectorSettings =
                    ConfigStore.copy(settings.edit, CelebrationEditSettings.class);
                draft.analysisPitch = settings.pitch;
                draft.fullyReviewed = false;
                changed();
              }
            },
            Component.literal("Reset manual hits?"),
            Component.literal(
                "Restore this detector's automatic hits. Undo is available; Save hits applies the"
                    + " reset.")));
  }

  void imported(HitTimeline doc) {
    remember();
    draft = doc.copy();
    selected = -1;
    changed();
  }

  @Override
  public void onFilesDrop(List<Path> paths) {
    if (!ready() || paths.size() != 1) return;
    busy = true;
    app.sounds
        .importTimeline(paths.getFirst(), settings.sound, wave.durationMs())
        .whenComplete(
            (doc, error) ->
                minecraft.execute(
                    () -> {
                      busy = false;
                      if (error != null) notice = error(error);
                      else {
                        imported(doc);
                        notice = "Timeline imported into draft. Review, then Save hits.";
                        rebuildWidgets();
                      }
                    }));
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    if (super.mouseClicked(e, twice)) return true;
    if (!ready() || !inside(e.x(), e.y(), gx(), gy(), gw(), gh())) return false;
    long t = at(e.x()), near = nearest(t);
    if (e.button() == 1) {
      remove(near);
      return true;
    }
    if (e.button() == 0) {
      dragSnapshot = near < 0;
      if (near >= 0) select(near);
      else add(t);
      dragging = true;
      return true;
    }
    return false;
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    if (dragging) {
      long t = at(e.x());
      if (t != selected && !dragSnapshot) {
        remember();
        dragSnapshot = true;
      }
      move(t);
      return true;
    }
    return super.mouseDragged(e, dx, dy);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent e) {
    dragging = false;
    return super.mouseReleased(e);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double sx, double sy) {
    if (inside(x, y, gx(), gy(), gw(), gh())) {
      zoom(sy > 0 ? .7 : 1 / .7, (x - gx()) / gw());
      return true;
    }
    return super.mouseScrolled(x, y, sx, sy);
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (timestamp != null && timestamp.isFocused()) return super.keyPressed(e);
    if (e.key() == GLFW.GLFW_KEY_DELETE) {
      remove(selected);
      return true;
    }
    return super.keyPressed(e);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    background(
        g,
        "Left-click add/select · drag move · right-click remove · wheel zoom · times use original"
            + " audio");
    int x = gx(), y = gy(), w = gw(), h = gh();
    Ui.surface(g, x, y, w, h, PANEL, BORDER);

    for (int i = 1; i < w - 1; i++) {
      long t = at(x + i);
      int bh = (int) ((h - 6) * wave.energyAt(t) / energyMaximum);
      g.fill(x + i, y + h - 3 - bh, x + i + 1, y + h - 3, 0xff385a70);
    }

    for (var hit : hits()) {
      int hx = px(hit.timeMs());
      if (hx < x || hx >= x + w) continue;
      int color =
          hit.timeMs() == selected
              ? GOLD
              : originalTimes.contains(hit.timeMs()) ? ACCENT : 0xffc49aff;
      g.fill(hx, y + 3, hx + (hit.timeMs() == selected ? 2 : 1), y + h - 2, color);
    }
    long position = playback == null ? 0 : Math.round(playback.positionMs() * settings.pitch);
    if (playback != null && px(position) >= x && px(position) < x + w)
      g.fill(px(position), y, px(position) + 2, y + h, GOLD);
    text(
        g,
        cut(settings.sound + " · " + hits().size() + " hits" + (dirty ? " · unsaved" : ""), w),
        x,
        y - 16,
        TEXT);
    text(g, time(viewStart) + " – " + time(Math.round(viewStart + viewSpan)), x, y + h + 8, MUTED);
    if (width > 440)
      text(
          g,
          cut("Green: detected · Purple: added · Gold: selected/playhead", w / 2),
          x + w / 2,
          y + h + 8,
          MUTED);
    if (inside(mx, my, x, y, w, h)) {
      long t = at(mx), near = nearest(t);
      g.setTooltipForNextFrame(
          Ui.label(
              "Song "
                  + time(t)
                  + (near < 0
                      ? " · click to add"
                      : " · hit " + time(near) + " · drag or right-click")),
          mx,
          my);
    }
    if (timestamp != null && timestamp.isHoveredOrFocused())
      g.setTooltipForNextFrame(
          Ui.label("Original audio time in seconds. Add a hit or move the selected hit here."),
          mx,
          my);
    renderChrome(g, mx, my, dt);
  }

  @Override
  public void onClose() {
    if (busy) {
      notice = "Wait for the current file operation to finish.";
      return;
    }
    if (dirty) {
      closeWhenSaved = true;
      save();
      return;
    }
    stop.run();
    super.onClose();
  }

  @Override
  public void removed() {
    stop.run();
    playback = null;
    super.removed();
  }
}
