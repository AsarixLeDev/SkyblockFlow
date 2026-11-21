package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.world.item.*;
import org.lwjgl.glfw.GLFW;

/** Hover to explore, click to pin. All numbers use the same measured presentation formatter. */
public final class JobStatsScreen extends FlowScreen {
  private static final List<String> JOBS = List.copyOf(JobXp.NAMES.keySet());
  private static final int CHAMPION = 0xffffed9e;
  private String selected = "mineur", locked = "";
  private int scroll;
  public JobStatsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Your jobs");
    var recent = app.jobs.tracker.selected("");
    if (recent != null) selected = recent.id;
  }
  public String selectedJob() { return selected; }
  public boolean pinned() { return !locked.isEmpty(); }
  protected int footerReservedWidth() { return 276; }
  @Override protected void init() {
    super.init();
    button(width - 100, 12, 84, "Sessions", () -> minecraft.gui.setScreen(new JobSessionScreen(app, this)));
    button(width - 276, height - 27, 96, "Refresh stats", () -> { app.closest.requestPersonalStats(); notice = "Refreshing your stats…"; });
    button(width - 174, height - 27, 108, "Leaderboards", () -> minecraft.gui.setScreen(new LeaderboardsScreen(app, this)));
    button(width - 60, height - 27, 48, "Done", this::onClose, true);
  }
  private int bodyWidth() { return Math.min(740, width - 32); }
  private int columns() { return bodyWidth() >= 560 ? 7 : 4; }
  private int gridHeight() { return ((7 + columns() - 1) / columns()) * 76; }
  private int totalHeight() { return gridHeight() + 36 + (columns() == 7 ? 184 : 378) + 16; }
  private int jobAt(double mx, double my) {
    if (my < 72 || my >= height - 36) return -1;
    int left = (width - bodyWidth()) / 2, cw = (bodyWidth() - (columns() - 1) * 8) / columns();
    for (int i = 0; i < JOBS.size(); i++) if (inside(mx, my, left + i % columns() * (cw + 8), 76 + i / columns() * 76 - scroll, cw, 68)) return i;
    return -1;
  }
  public static Item icon(String id) {
    return switch (id) {
      case "mineur" -> Items.DIAMOND_PICKAXE;
      case "bucheron" -> Items.IRON_AXE;
      case "fermier" -> Items.WHEAT;
      case "chasseur" -> Items.DIAMOND_SWORD;
      case "artisan" -> Items.ANVIL;
      case "aventurier" -> Items.ENDER_EYE;
      default -> Items.FISHING_ROD;
    };
  }
  @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    g.fill(0, 0, width, height, BG);
    background(g, locked.isEmpty() ? "Hover to explore · click a job to pin its stats" : JobXp.name(locked) + " pinned · click elsewhere to unlock");
    int hover = jobAt(mx, my);
    if (locked.isEmpty() && hover >= 0) selected = JOBS.get(hover);
    int left = (width - bodyWidth()) / 2, bw = bodyWidth(), cols = columns(), cw = (bw - (cols - 1) * 8) / cols;
    g.enableScissor(left - 2, 70, left + bw + 4, height - 34);
    for (int i = 0; i < JOBS.size(); i++) {
      String id = JOBS.get(i); int x = left + i % cols * (cw + 8), y = 76 + i / cols * 76 - scroll;
      var job = app.jobs.tracker.data.jobs.get(id); var rank = app.closest.history.boards.get(id);
      boolean master = job != null && job.level >= 200, first = rank != null && rank.position() == 1;
      Ui.surface(g, x, y, cw, 68, id.equals(selected) ? 0xff253b40 : CARD, id.equals(selected) ? ACCENT : BORDER);
      if (master || first) g.fill(x + 8, y + 1, x + cw - 8, y + 2, CHAMPION);
      g.pose().pushMatrix(); g.pose().translate(x + (cw - 24) / 2f, y + 8); g.pose().scale(1.5f, 1.5f);
      g.item(new ItemStack(icon(id)), 0, 0); g.pose().popMatrix();
      String name = Ui.trim(JobXp.name(id), cw - 8);
      text(g, name, x + (cw - Ui.width(name)) / 2, y + 39, master ? CHAMPION : TEXT);
      String level = job == null ? "—" : "Lv. " + job.level;
      text(g, level, x + (cw - Ui.width(level)) / 2, y + 54, MUTED);
    }
    int y = 76 + gridHeight() + 6 - scroll;
    text(g, JobXp.name(selected), left + 2, y, TEXT);
    String badge = locked.isEmpty() ? "" : "PINNED";
    text(g, badge, left + bw - Ui.width(badge) - 2, y, ACCENT);
    y += 24;
    var job = app.jobs.tracker.data.jobs.get(selected);
    var rank = app.closest.history.boards.get(selected);
    boolean master = job != null && job.level >= 200, first = rank != null && rank.position() == 1, rainbow = master && first;
    int pw = cols == 7 ? (bw - 12) / 2 : bw;
    personal(g, left, y, pw, job, master, rainbow);
    ranking(g, cols == 7 ? left + pw + 12 : left, cols == 7 ? y : y + 194, pw, job, rank, first, rainbow);
    g.disableScissor();
    Ui.scrollBar(g, left + bw + 3, 74, height - 110, totalHeight(), scroll);
    renderChrome(g, mx, my, dt);
  }
  private void card(GuiGraphicsExtractor g, int x, int y, int w, boolean gold) {
    Ui.surface(g, x, y, w, 184, gold ? 0xff292a24 : PANEL, gold ? 0xff9d9465 : BORDER);
  }
  private void line(GuiGraphicsExtractor g, String value, int x, int y, int max, int color, boolean rainbow) {
    value = Ui.trim(Numbers.text(value), max);
    if (!rainbow) { text(g, value, x, y, color); return; }
    float phase = (System.currentTimeMillis() % 16000) / 16000f;
    for (int cp : value.codePoints().toArray()) {
      String glyph = new String(Character.toChars(cp));
      int c = java.awt.Color.HSBtoRGB((phase + (x + y) / 650f) % 1, .35f, 1);
      text(g, glyph, x, y, c); x += Ui.width(glyph);
    }
  }
  private void bar(GuiGraphicsExtractor g, int x, int y, int w, double fraction, int color) {
    Ui.rounded(g, x, y, w, 4, 2, 0xff35434f);
    Ui.rounded(g, x, y, (int) Math.round(w * Math.clamp(fraction, 0, 1)), 4, 2, color);
  }
  private static double total(String job, int level) {
    double n = 0; for (int l = 0; l < Math.min(200, level); l++) n += Math.max(0, JobXp.requirement(job, l)); return n;
  }
  private void personal(GuiGraphicsExtractor g, int x, int y, int w, JobTracker.Job j, boolean gold, boolean rainbow) {
    card(g, x, y, w, gold); int color = gold ? CHAMPION : ACCENT, ink = gold ? CHAMPION : TEXT;
    line(g, "PERSONAL STATS", x + 14, y + 13, w - 28, color, rainbow);
    if (j == null) { line(g, "Earn XP or refresh your stats", x + 14, y + 53, w - 28, MUTED, false); return; }
    line(g, "Level " + j.level + (gold ? "  ·  MASTERED" : ""), x + 14, y + 36, w - 28, ink, rainbow);
    double f = gold ? 1 : j.xp / Math.max(1, j.required), all = total(j.id, 200), gained = gold ? all : total(j.id, j.level) + j.xp;
    line(g, "Level progress  ·  " + Numbers.fixed(f * 100, 1) + "%", x + 14, y + 60, w - 28, gold ? ink : MUTED, rainbow);
    line(g, gold ? "Level 200 complete" : JobXp.amount(j.xp) + " / " + JobXp.amount(j.required) + " XP", x + 14, y + 79, w - 28, ink, rainbow);
    bar(g, x + 14, y + 101, w - 28, f, color);
    line(g, "Total progress  ·  " + Numbers.fixed(gained / Math.max(1, all) * 100, 1) + "%", x + 14, y + 122, w - 28, gold ? ink : MUTED, rainbow);
    line(g, JobXp.amount(gained) + " / " + JobXp.amount(all) + " XP", x + 14, y + 141, w - 28, ink, rainbow);
    bar(g, x + 14, y + 164, w - 28, gained / Math.max(1, all), color);
  }
  private void ranking(GuiGraphicsExtractor g, int x, int y, int w, JobTracker.Job j, RankHistory.Observation rank, boolean gold, boolean rainbow) {
    card(g, x, y, w, gold); int ink = gold ? CHAMPION : TEXT;
    line(g, "RANKING", x + 14, y + 13, w - 28, gold ? CHAMPION : ACCENT, rainbow);
    line(g, rank == null ? "Rank not recorded yet" : "#" + rank.position() + (gold ? "  ·  LEADING THE WAY" : "  in " + JobXp.name(selected)), x + 14, y + 36, w - 28, ink, rainbow);
    var rival = app.closest.neighbor(selected);
    if (rival == null) {
      var result = app.closest.result(selected);
      if (result != null && result.reading() != null) rival = new RankHistory.Player(result.rank(), result.name(), result.reading().level(), result.reading(), result.at());
    }
    if (rival == null) { line(g, "Open the leaderboard to find your rival", x + 14, y + 79, w - 28, MUTED, rainbow); return; }
    line(g, (gold ? "Second place" : "Next player") + "  ·  " + rival.name(), x + 14, y + 61, w - 28, gold ? ink : MUTED, rainbow);
    var r = rival.reading();
    line(g, "Level " + rival.level() + (r == null ? "" : "  |  " + Numbers.fixed(r.level() >= 200 ? 100 : r.xp() / Math.max(1, r.required()) * 100, 1) + "%"), x + 14, y + 82, w - 28, ink, rainbow);
    line(g, r == null ? "Open their /jobs stats to read XP" : r.level() >= 200 ? "Level 200 complete" : JobXp.amount(r.xp()) + " / " + JobXp.amount(r.required()) + " XP", x + 14, y + 100, w - 28, ink, rainbow);
    line(g, gold ? "XP ahead of second place" : "XP until next player", x + 14, y + 130, w - 28, gold ? ink : MUTED, rainbow);
    String gap = j == null || r == null ? "—" : JobXp.amount(Math.max(0, (gold ? -1 : 1) * JobMessages.gap(j, r))) + " XP";
    line(g, gap, x + 14, y + 151, w - 28, ink, rainbow);
  }
  @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    int index = jobAt(e.x(), e.y());
    if (e.button() == 0 && index >= 0) { selected = locked = JOBS.get(index); return true; }
    locked = ""; return super.mouseClicked(e, twice);
  }
  @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
    scroll = Math.clamp(scroll - (int) (sy * 34), 0, Math.max(0, totalHeight() - (height - 110))); return true;
  }
  @Override public boolean keyPressed(KeyEvent e) {
    int at = JOBS.indexOf(selected);
    if (e.key() == GLFW.GLFW_KEY_RIGHT || e.key() == GLFW.GLFW_KEY_LEFT) {
      selected = JOBS.get(Math.floorMod(at + (e.key() == GLFW.GLFW_KEY_RIGHT ? 1 : -1), JOBS.size())); locked = selected; return true;
    }
    return super.keyPressed(e);
  }
}
