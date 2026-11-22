package dev.asarix.skyblockflow.ui;

import static dev.asarix.skyblockflow.ui.FlowScreen.ACCENT;
import static dev.asarix.skyblockflow.ui.FlowScreen.BORDER;
import static dev.asarix.skyblockflow.ui.FlowScreen.CARD;
import static dev.asarix.skyblockflow.ui.FlowScreen.MUTED;
import static dev.asarix.skyblockflow.ui.FlowScreen.TEXT;

import dev.asarix.skyblockflow.RecipeHelper;
import dev.asarix.skyblockflow.RecipeHelper.Action;
import dev.asarix.skyblockflow.RecipeHelper.ItemTile;
import dev.asarix.skyblockflow.core.RecipeCatalog;
import dev.asarix.skyblockflow.mixin.ContainerScreenAccess;
import dev.asarix.skyblockflow.mixin.RecipeBookAccess;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Item browser and bookmarks around the inventory, with independent paging and a shared hide control. */
public final class InventoryRecipeDock {
  private static final int ITEM = 18, CONTROL = 16, MARGIN = 5, GAP = 5;
  private static final int MAX_SIDE_WIDTH = 12 * ITEM + 4;
  private static final int BOOK_TABS = 32, BOOK_GAP = 4;
  private static final long SLIDE_NS = 145_000_000L;

  public static final class State {
    private String query = "";
    private boolean bookmarksVisible = true, bookmarkFilter;
    private int itemPage, bookmarkPage;
    private int checklistScroll, checklistBatches;
    private String checklistRecipe = "";
  }

  private enum Edge {
    RIGHT(1, 0, "›", "‹"), LEFT(-1, 0, "‹", "›"),
    TOP(0, -1, "⌃", "⌄"), BOTTOM(0, 1, "⌄", "⌃"), LAUNCHER(0, 0, "", "‹");

    final int dx, dy;
    final String close, open;

    Edge(int dx, int dy, String close, String open) {
      this.dx = dx;
      this.dy = dy;
      this.close = close;
      this.open = open;
    }
  }

  private record Box(int x, int y, int w, int h) {
    int right() { return x + w; }
    int bottom() { return y + h; }

    boolean contains(double mx, double my) {
      return mx >= x && mx < right() && my >= y && my < bottom();
    }
  }

  private record Band(int top, int bottom) {
    boolean intersects(int y, int end) { return y < bottom && end > top; }
  }

  private record Grid(Box area, List<Box> cells, Box previous, Box next) {
    boolean contains(double x, double y) {
      return previous.contains(x, y) || next.contains(x, y) || cellAt(x, y) >= 0;
    }

    int cellAt(double x, double y) {
      if (!area.contains(x, y)) return -1;
      for (int i = 0; i < cells.size(); i++)
        if (cells.get(i).contains(x, y)) return i;
      return -1;
    }
  }

  private record Browser(Edge edge, Grid grid, Box search, Box filter, Box toggle) {
    boolean contains(double x, double y) {
      return grid != null && (grid.contains(x, y) || search.contains(x, y)
          || filter != null && filter.contains(x, y));
    }
  }

  private record Bookmarks(Grid grid, Box toggle) {}

  private record Space(int width, int height, int left, int right, int top, int bottom,
                       Band effects, boolean calculator, boolean checklist) {}

  private record Layout(Browser browser, Bookmarks bookmarks, Box checklist) {}

  private final RecipeHelper helper;
  private final AbstractContainerScreen<?> screen;
  private final ContainerScreenAccess container;
  private final State state;
  private final FlowTextField search;
  private final RecipeBookComponent<?> vanillaBook;
  private List<ItemTile> items = List.of(), pins = List.of(), filteredPins = List.of();
  private RecipeCatalog catalog;
  private Language language;
  private ItemTile hover;
  private long transitionAt;
  private float from, target;
  private int ownedButtons;
  private Space lastSpace;
  private Layout layout;

  private InventoryRecipeDock(RecipeHelper helper, AbstractContainerScreen<?> screen, State state) {
    this.helper = helper;
    this.screen = screen;
    this.container = (ContainerScreenAccess) screen;
    this.state = state;
    from = target = helper.settings().inventoryDockHidden ? 0 : 1;
    vanillaBook = screen.children().stream()
        .filter(RecipeBookComponent.class::isInstance)
        .map(child -> (RecipeBookComponent<?>) child).findFirst().orElse(null);
    search = new FlowTextField(screen.getFont(), 0, 0, 60, CONTROL, Component.literal("Find items"));
    search.setMaxLength(80);
    layout();
    search.setValue(state.query);
    search.setResponder(query -> {
      state.query = query;
      state.itemPage = 0;
      refresh();
    });
  }

  public static void attach(RecipeHelper helper, AbstractContainerScreen<?> screen, State state) {
    var dock = new InventoryRecipeDock(helper, screen, state);
    ScreenEvents.afterForeground(screen).register((s, g, mx, my, dt) -> dock.render(g, mx, my, dt));
    ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> dock.allowClick(e));
    ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> dock.allowRelease(e));
    ScreenMouseEvents.allowMouseDrag(screen)
        .register((s, e, dx, dy) -> (dock.ownedButtons & 1 << e.button()) == 0);
    ScreenMouseEvents.allowMouseScroll(screen)
        .register((s, mx, my, dx, dy) -> dock.allowScroll(mx, my, dy));
    ScreenKeyboardEvents.allowKeyPress(screen).register((s, e) -> dock.allowKey(e));
    ScreenKeyboardEvents.allowCharType(screen).register((s, e) -> {
      if (!dock.search.isFocused()) return true;
      dock.search.charTyped(e);
      return false;
    });
  }

  private Space space() {
    int left = container.skyblockflow$leftPos(), top = container.skyblockflow$topPos();
    int outerLeft = left, outerRight = left + container.skyblockflow$imageWidth();
    if (vanillaBook != null && vanillaBook.isVisible()) {
      int bookX = ((RecipeBookAccess) vanillaBook).skyblockflow$bookX();
      outerRight = Math.max(outerRight, bookX + RecipeBookComponent.IMAGE_WIDTH);
      // Lunar can center the inventory and draw the recipe book beside that position.
      if (screen.width >= 379)
        bookX = Math.min(bookX, left - RecipeBookComponent.IMAGE_WIDTH - BOOK_GAP);
      outerLeft = Math.min(left, bookX - BOOK_TABS);
    }
    var player = Minecraft.getInstance().player;
    int effects = (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen)
        && player != null && screen.width - left - container.skyblockflow$imageWidth() >= 34
        ? player.getActiveEffects().size() : 0;
    Band blocked = null;
    if (effects > 0) {
      int step = effects > 5 ? 132 / (effects - 1) : 33;
      blocked = new Band(top - GAP, top + 32 + (effects - 1) * step + GAP);
    }
    int tabs = screen instanceof CreativeModeInventoryScreen ? 30 : 0;
    return new Space(screen.width, screen.height, outerLeft, outerRight, top - tabs,
        top + container.skyblockflow$imageHeight() + tabs, blocked,
        helper.inventoryCalculatorEnabled(), helper.settings().showChecklist && helper.materialRecipe() != null);
  }

  private Layout layout() {
    Space space = space();
    if (space.equals(lastSpace)) return layout;
    lastSpace = space;
    var right = sideArea(space, Edge.RIGHT);
    var left = sideArea(space, Edge.LEFT);
    int checklistHeight = Math.min(130, left.h / 2);
    Box checklist = space.checklist && left.w >= 87 && checklistHeight >= 68
        ? new Box(left.x, left.bottom() - checklistHeight, left.w, checklistHeight) : null;
    Bookmarks bookmarks = bookmarks(left, checklist);
    Browser browser = sideBrowser(right, Edge.RIGHT, space.effects, bookmarks == null);
    if (browser == null) browser = shelf(space, bookmarks == null);
    if (browser == null) {
      browser = sideBrowser(left, Edge.LEFT, null, true);
      if (browser != null) {
        bookmarks = null;
        checklist = null;
      }
    }
    if (browser == null) {
      var edge = right.w >= left.w ? right : left;
      int x = Math.clamp(edge.right() - CONTROL, 0, Math.max(0, space.width - CONTROL));
      browser = new Browser(Edge.LAUNCHER, null, null, null, control(x, MARGIN));
    }
    layout = new Layout(browser, bookmarks, checklist);
    if (browser.search == null) {
      search.setFocused(false);
    } else {
      var field = browser.search;
      if (search.getWidth() != field.w) {
        int cursor = search.getCursorPosition();
        search.setWidth(field.w);
        search.setCursorPosition(0);
        search.setCursorPosition(cursor);
      }
      search.setPosition(field.x, field.y);
      search.setHint(Ui.label(Ui.trim(field.w >= 60 ? "Find items…" : "Find", search.getInnerWidth()))
          .copy().withStyle(style -> style.withColor(MUTED)));
    }
    return layout;
  }

  private static Box sideArea(Space s, Edge side) {
    int free = side == Edge.RIGHT ? s.width - MARGIN - s.right - GAP : s.left - MARGIN - GAP;
    int width = Math.clamp(free, 0, MAX_SIDE_WIDTH);
    int x = side == Edge.RIGHT ? s.width - MARGIN - width : MARGIN;
    return new Box(x, MARGIN, width, bottomLimit(s, x, width) - MARGIN);
  }

  private static int bottomLimit(Space s, int x, int width) {
    int calculatorX = Math.max(4, (s.width - 340) / 2);
    int calculatorRight = calculatorX + Math.min(s.width - 8, 340);
    return s.calculator && x < calculatorRight + GAP && x + width > calculatorX - GAP
        ? s.height - 28 : s.height - MARGIN;
  }

  private static Browser sideBrowser(Box area, Edge edge, Band blocked, boolean needsFilter) {
    int controls = needsFilter ? 2 : 1;
    if (area.w < CONTROL * controls + 30) return null;
    int header = area.y, footer = area.bottom() - CONTROL;
    if (blocked != null) {
      if (blocked.intersects(header, header + CONTROL)) header = blocked.bottom;
      if (blocked.intersects(footer, footer + CONTROL)) footer = blocked.top - CONTROL;
    }
    Grid grid = grid(area, header, footer, blocked);
    if (grid.cells.isEmpty()) return null;
    Box toggle = control(area.right() - CONTROL, footer);
    Box filter = needsFilter ? control(toggle.x - CONTROL - 2, footer) : null;
    int searchEnd = filter == null ? toggle.x : filter.x;
    return new Browser(edge, grid, new Box(area.x, footer, searchEnd - area.x - 3, CONTROL), filter, toggle);
  }

  private static Bookmarks bookmarks(Box area, Box checklist) {
    int footer = (checklist == null ? area.bottom() : checklist.y - GAP) - CONTROL;
    if (area.w < 2 * CONTROL + 5) return null;
    Grid grid = grid(area, area.y, footer, null);
    if (grid.cells.isEmpty()) return null;
    return new Bookmarks(grid, control(area.x, footer));
  }

  private static Grid grid(Box area, int header, int footer, Band blocked) {
    int columns = area.w / ITEM;
    int gridX = area.x + (area.w - columns * ITEM) / 2;
    var cells = new ArrayList<Box>();
    for (int y = header + CONTROL + 4; y + ITEM <= footer - 4; y += ITEM) {
      if (blocked != null && blocked.intersects(y, y + ITEM)) continue;
      for (int col = 0; col < columns; col++)
        cells.add(new Box(gridX + col * ITEM, y, ITEM, ITEM));
    }
    return new Grid(area, cells, control(area.x, header), control(area.right() - CONTROL, header));
  }

  private static Browser shelf(Space s, boolean needsFilter) {
    int w = Math.min(390, s.width - 2 * MARGIN);
    int x = (s.width - w) / 2, h = 24;
    boolean above = s.top - MARGIN - GAP >= h;
    int y = above ? s.top - GAP - h : s.bottom + GAP;
    if (w < 178 || !above && y + h > bottomLimit(s, x, w)) return null;
    Box area = new Box(x, y, w, h);
    int controlY = y + 4;
    Box search = new Box(x, controlY, Math.min(88, w / 3), CONTROL);
    Box filter = needsFilter ? control(search.right() + 3, controlY) : null;
    Box toggle = control(area.right() - CONTROL, controlY);
    Box next = control(toggle.x - CONTROL - 3, controlY);
    Box previous = control(next.x - CONTROL - 3, controlY);
    var cells = new ArrayList<Box>();
    for (int sx = (filter == null ? search.right() : filter.right()) + 5; sx + ITEM <= previous.x - 4; sx += ITEM)
      cells.add(new Box(sx, y + 3, ITEM, ITEM));
    return new Browser(above ? Edge.TOP : Edge.BOTTOM,
        new Grid(area, cells, previous, next), search, filter, toggle);
  }

  private static Box control(int x, int y) { return new Box(x, y, CONTROL, CONTROL); }

  private void refresh() {
    catalog = helper.catalog();
    language = Language.getInstance();
    items = helper.items(state.query, false);
    pins = helper.items("", true);
    filteredPins = state.bookmarkFilter && !state.query.isBlank() ? helper.items(state.query, true) : pins;
  }

  private List<ItemTile> results(Layout l) {
    return l.bookmarks == null && state.bookmarkFilter ? filteredPins : items;
  }

  private static int pages(Grid grid, List<ItemTile> items) {
    return Math.max(1, Math.ceilDiv(items.size(), grid.cells.size()));
  }

  private void clampPages(Layout l) {
    if (l.browser.grid != null)
      state.itemPage = Math.clamp(state.itemPage, 0, pages(l.browser.grid, results(l)) - 1);
    if (l.bookmarks != null)
      state.bookmarkPage = Math.clamp(state.bookmarkPage, 0, pages(l.bookmarks.grid, pins) - 1);
  }

  private static ItemTile tileAt(Grid grid, List<ItemTile> items, int page, double x, double y) {
    int cell = grid.cellAt(x, y);
    if (cell < 0) return null;
    int index = page * grid.cells.size() + cell;
    return index < items.size() ? items.get(index) : null;
  }

  private float reveal(long now) {
    if (transitionAt == 0) return target;
    float t = Math.clamp((now - transitionAt) / (float) SLIDE_NS, 0, 1);
    if (t >= 1) {
      transitionAt = 0;
      return target;
    }
    float remaining = 1 - t;
    return target + (from - target) * remaining * remaining * remaining;
  }

  private void toggle() {
    long now = System.nanoTime();
    from = reveal(now);
    target = target == 0 ? 1 : 0;
    transitionAt = now;
    search.setFocused(false);
    hover = null;
    helper.settings().inventoryDockHidden = target == 0;
    helper.save();
  }

  private boolean over(Layout l, double x, double y) {
    if (l.checklist != null && l.checklist.contains(x, y)) return true;
    if (l.browser.toggle.contains(x, y)) return true;
    if (reveal(System.nanoTime()) <= 0) return false;
    if (l.browser.contains(x, y)) return true;
    return l.bookmarks != null && (l.bookmarks.toggle.contains(x, y) || overBookmarks(l, x, y));
  }

  private boolean overBookmarks(Layout l, double x, double y) {
    if (l.bookmarks == null || !state.bookmarksVisible || pins.isEmpty()) return false;
    Grid grid = l.bookmarks.grid;
    return pins.size() > grid.cells.size() && (grid.previous.contains(x, y) || grid.next.contains(x, y))
        || tileAt(grid, pins, state.bookmarkPage, x, y) != null;
  }

  private boolean allowClick(MouseButtonEvent event) {
    Layout l = layout();
    Browser browser = l.browser;
    double x = event.x(), y = event.y();
    boolean left = event.button() == 0;
    if (!over(l, x, y)) {
      search.setFocused(false);
      return true;
    }
    ownedButtons |= 1 << event.button();
    if (l.checklist != null && l.checklist.contains(x, y)) return false;
    if (left && browser.toggle.contains(x, y)) {
      if (browser.edge == Edge.LAUNCHER) {
        if (screen.getMenu().getCarried().isEmpty()) helper.open(screen, null, false, false);
      } else toggle();
      return false;
    }
    if (target == 0 || transitionAt != 0 || !screen.getMenu().getCarried().isEmpty()) return false;
    if (catalog != helper.catalog() || language != Language.getInstance()) refresh();
    clampPages(l);
    screen.setFocused(null);
    for (var child : screen.children()) {
      if (child instanceof EditBox field && field.isFocused()) field.setFocused(false);
    }
    boolean typing = left && browser.search != null && browser.search.contains(x, y);
    search.setFocused(typing);
    if (typing) search.onClick(event, false);
    else if (left && l.bookmarks != null && l.bookmarks.toggle.contains(x, y))
      state.bookmarksVisible = !state.bookmarksVisible;
    else if (left && browser.filter != null && browser.filter.contains(x, y)) {
      state.bookmarkFilter = !state.bookmarkFilter;
      state.itemPage = 0;
      refresh();
    } else if (left || event.button() == 1) {
      boolean bookmark = overBookmarks(l, x, y);
      Grid grid = bookmark ? l.bookmarks.grid : browser.grid;
      var entries = bookmark ? pins : results(l);
      if (grid == null) return false;
      if (left && (grid.previous.contains(x, y) || grid.next.contains(x, y)))
        turnPage(l, bookmark, grid.previous.contains(x, y) ? -1 : 1);
      else {
        var tile = tileAt(grid, entries, bookmark ? state.bookmarkPage : state.itemPage, x, y);
        if (tile != null) {
          if (left) helper.openSelected(screen, tile.id(), tile.recipe());
          else open(Action.USES, tile, false);
        }
      }
    }
    return false;
  }

  private boolean allowRelease(MouseButtonEvent event) {
    int mask = 1 << event.button();
    if ((ownedButtons & mask) != 0) {
      ownedButtons &= ~mask;
      return false;
    }
    if (!screen.getMenu().getCarried().isEmpty() && container.skyblockflow$quickCraftSlots().isEmpty()
        && over(layout(), event.x(), event.y())) {
      container.skyblockflow$quickCrafting(false);
      return false;
    }
    return true;
  }

  private void turnPage(Layout l, boolean bookmarks, int delta) {
    if (bookmarks) state.bookmarkPage += delta;
    else state.itemPage += delta;
    clampPages(l);
  }

  private boolean allowScroll(double x, double y, double amount) {
    Layout l = layout();
    if (l.checklist != null && l.checklist.contains(x, y)) {
      syncChecklist(l.checklist);
      state.checklistScroll = helper.checklist().offset(l.checklist.h,
          state.checklistScroll - (int) Math.signum(amount));
      return false;
    }
    if (!over(l, x, y)) return true;
    if (target > 0 && amount != 0) {
      boolean bookmarks = overBookmarks(l, x, y);
      if (bookmarks || l.browser.grid != null && l.browser.grid.contains(x, y))
        turnPage(l, bookmarks, -(int) Math.signum(amount));
    }
    return false;
  }

  private boolean allowKey(KeyEvent event) {
    if (search.isFocused()) {
      if (event.key() == GLFW.GLFW_KEY_ESCAPE) search.setFocused(false);
      else if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
        Layout l = layout();
        var entries = results(l);
        if (!entries.isEmpty() && l.browser.grid != null && screen.getMenu().getCarried().isEmpty()) {
          clampPages(l);
          var tile = entries.get(state.itemPage * l.browser.grid.cells.size());
          helper.openSelected(screen, tile.id(), tile.recipe());
        }
      } else search.keyPressed(event);
      return false;
    }
    if (!screen.getMenu().getCarried().isEmpty() || screen.getFocused() instanceof EditBox) return true;
    Action action = helper.action(event);
    if (action == null) return true;
    if (hover != null) {
      open(action, hover, event.hasShiftDown());
      return false;
    }
    var stack = RecipeHelper.hovered(screen);
    if (stack.isEmpty()) return true;
    helper.handle(action, helper.remember(stack), null, screen, event.hasShiftDown());
    if (action == Action.PIN) refresh();
    return false;
  }

  private void open(Action action, ItemTile tile, boolean max) {
    helper.handle(action, tile.id(), tile.recipe(), screen, max);
    if (action == Action.PIN) refresh();
  }

  private void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
    Layout l = layout();
    Browser browser = l.browser;
    hover = null;
    if (l.checklist != null) {
      var box = l.checklist;
      syncChecklist(box);
      helper.drawChecklist(g, box.x, box.y, box.w, box.h, state.checklistScroll);
    }
    if (browser.edge == Edge.LAUNCHER) {
      button(g, browser.toggle, "‹", false, true, mx, my);
      if (browser.toggle.contains(mx, my)) tooltip(g, "Open recipe helper", mx, my);
      return;
    }

    float progress = reveal(System.nanoTime());
    boolean canHover = transitionAt == 0 && target > 0 && screen.getMenu().getCarried().isEmpty();
    int hx = canHover ? mx : -1, hy = canHover ? my : -1;
    if (progress > 0) {
      if (catalog != helper.catalog() || language != Language.getInstance()) refresh();
      clampPages(l);
      beginSlide(g, browser.grid.area, browser.edge, progress);
      renderGrid(g, browser.grid, results(l), state.itemPage, true, hx, hy);
      search.extractRenderState(g, hx, hy, dt);
      if (browser.filter != null) drawBookmark(g, browser.filter, state.bookmarkFilter, hx, hy);
      endSlide(g);
      if (l.bookmarks != null) {
        var saved = l.bookmarks;
        beginSlide(g, saved.grid.area, Edge.LEFT, progress);
        if (state.bookmarksVisible)
          renderGrid(g, saved.grid, pins, state.bookmarkPage, pins.size() > saved.grid.cells.size(), hx, hy);
        drawBookmark(g, saved.toggle, state.bookmarksVisible, hx, hy);
        endSlide(g);
      }
    }
    button(g, browser.toggle, target > 0 ? browser.edge.close : browser.edge.open, target == 0, true, mx, my);
    if (browser.toggle.contains(mx, my)) tooltip(g, target > 0 ? "Hide recipe helper" : "Show recipe helper", mx, my);
    if (!canHover) return;
    if (browser.filter != null && browser.filter.contains(mx, my)) {
      tooltip(g, state.bookmarkFilter ? "Show all items" : "Show pinned recipes", mx, my);
    } else if (l.bookmarks != null && l.bookmarks.toggle.contains(mx, my)) {
      tooltip(g, state.bookmarksVisible ? "Hide pinned recipes" : "Show pinned recipes", mx, my);
    } else {
      hover = tileAt(browser.grid, results(l), state.itemPage, mx, my);
      if (hover == null && l.bookmarks != null && state.bookmarksVisible)
        hover = tileAt(l.bookmarks.grid, pins, state.bookmarkPage, mx, my);
      if (hover != null) {
        g.setComponentTooltipForNextFrame(screen.getFont(),
            List.of(Ui.label(helper.name(hover.id())), Ui.label("Click for recipe · Right-click for uses")),
            mx, my, RecipeScreen.TOOLTIP_STYLE);
        if (helper.inspectInside()) helper.inspectBrowser(g, hover.id(), mx, my);
      }
    }
  }

  private void syncChecklist(Box box) {
    var settings = helper.settings();
    if (!state.checklistRecipe.equals(settings.checklistRecipe)
        || state.checklistBatches != settings.batches) {
      state.checklistRecipe = settings.checklistRecipe;
      state.checklistBatches = settings.batches;
      state.checklistScroll = 0;
    }
    state.checklistScroll = helper.checklist().offset(box.h, state.checklistScroll);
  }

  private static void beginSlide(GuiGraphicsExtractor g, Box area, Edge edge, float progress) {
    g.enableScissor(area.x, area.y, area.right(), area.bottom());
    g.pose().pushMatrix();
    g.pose().translate(Math.round((1 - progress) * area.w) * edge.dx,
        Math.round((1 - progress) * area.h) * edge.dy);
  }

  private static void endSlide(GuiGraphicsExtractor g) {
    g.pose().popMatrix();
    g.disableScissor();
  }

  private void renderGrid(GuiGraphicsExtractor g, Grid grid, List<ItemTile> items, int page,
                          boolean showPager, int mx, int my) {
    if (showPager) {
      int pages = pages(grid, items);
      button(g, grid.previous, "‹", false, page > 0, mx, my);
      button(g, grid.next, "›", false, page + 1 < pages, mx, my);
      String count = (page + 1) + "/" + pages;
      int space = grid.next.x - grid.previous.right();
      if (Ui.width(count) + 4 <= space)
        Ui.text(g, count, grid.previous.right() + (space - Ui.width(count)) / 2, grid.previous.y + 4, TEXT);
      else if (grid.previous.contains(mx, my) || grid.next.contains(mx, my))
        tooltip(g, "Page " + count, mx, my);
    }
    int first = page * grid.cells.size();
    if (items.isEmpty() && showPager) {
      var cell = grid.cells.getFirst();
      Ui.text(g, Ui.trim(state.query.isBlank() ? "No pins" : "No matches", grid.area.w - 4),
          cell.x, cell.y + 4, MUTED);
    }
    for (int i = 0; i < grid.cells.size() && first + i < items.size(); i++) {
      var cell = grid.cells.get(i);
      if (cell.contains(mx, my)) Ui.rounded(g, cell.x, cell.y, ITEM, ITEM, 3, 0xc034495b);
      g.item(helper.icon(items.get(first + i).id()), cell.x + 1, cell.y + 1);
    }
  }

  private static void drawBookmark(GuiGraphicsExtractor g, Box box, boolean selected, int mx, int my) {
    button(g, box, "", selected, true, mx, my);
    int x = box.x + 5, y = box.y + 4;
    int ink = selected ? ACCENT : MUTED;
    g.fill(x, y, x + 6, y + 1, ink);
    g.fill(x, y + 1, x + 1, y + 8, ink);
    g.fill(x + 5, y + 1, x + 6, y + 8, ink);
    g.fill(x + 1, y + 6, x + 2, y + 7, ink);
    g.fill(x + 4, y + 6, x + 5, y + 7, ink);
    g.fill(x + 2, y + 5, x + 4, y + 6, ink);
  }

  private static void button(GuiGraphicsExtractor g, Box b, String label, boolean selected,
                             boolean active, int mx, int my) {
    Ui.rounded(g, b.x, b.y, b.w, b.h, 4, selected ? ACCENT : BORDER);
    Ui.rounded(g, b.x + 1, b.y + 1, b.w - 2, b.h - 2, 3,
        selected ? 0xff2c4946 : active && b.contains(mx, my) ? 0xff324357 : CARD);
    Ui.text(g, label, b.x + (b.w - Ui.width(label)) / 2, b.y + (b.h - 9) / 2,
        selected ? ACCENT : active ? TEXT : MUTED);
  }

  private void tooltip(GuiGraphicsExtractor g, String message, int x, int y) {
    g.setTooltipForNextFrame(screen.getFont(), Ui.label(message), x, y, RecipeScreen.TOOLTIP_STYLE);
  }
}
