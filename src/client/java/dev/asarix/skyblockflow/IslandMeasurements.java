package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import java.util.*;
import java.util.regex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;

/** Passive reader of server-owned slots and unsigned server messages. */
public final class IslandMeasurements {
  private static final long FRESH = ExcaliaApiClient.MAX_QUOTE_AGE;
  private static final Pattern PAGE =
      Pattern.compile("(?m)\\bpage *:? *(\\d{1,4}) */ *(\\d{1,4})\\b");
  private static final Pattern TOTAL =
      Pattern.compile(
          "(?m)^(?:total *(?:des? iles)?|(?:nombre total d['’]|nombre d['’])iles) *: *([0-9]{1,7})"
              + " *$");
  private final ClosestPlayerService service;
  private Object menu;
  private String menuTitle = "";
  private long revision = -1, stableAt, nextReminder, valueUntil, reserveUntil, reminderAt;
  private int lastPage = -1, lastPages, pageHint = 1;
  private boolean needOwner, askedOwner, browseMessage, foundDuringBrowse;
  private long ownerUntil;
  public void leaderChanged() {
    service.forgetPublicIsland();
    service.history.apiIslandId = "";
    service.history.owner = ""; evidenceOwner = ""; ownPower = -1;
    askedOwner = foundDuringBrowse = false; needOwner = true; service.saveHistory();
    status = "Run /is info to identify your island leader";
  }
  public void pageClicked(String raw) {
    if (!isVisitsTitle(menuTitle) && requestedVisitsMenu == null) return;
    String text = JobXp.normalize(raw);
    if (text.contains("suivant") || text.contains("next")) pageHint = Math.max(1, lastPage) + 1;
    else if (text.contains("precedent") || text.contains("previous")) pageHint = Math.max(1, lastPage - 1);
    else return;
    visitsUntil = System.currentTimeMillis() + 8000;
    requestedVisitsMenu = null;
  }
  private static boolean isVisitsTitle(String title) {
    return title.contains("visit") && title.contains("ile") || title.contains("classement des iles") || title.contains("top des iles");
  }
  private void readOwner(String raw, long now) {
    if (ownerUntil < now) return;
    String owner = IslandBoard.owner(raw);
    if (owner.isEmpty()) return;
    service.history.owner = evidenceOwner = owner; ownerUntil = 0; needOwner = false;
    service.saveHistory();
    // Re-evaluate the page currently on screen when /is info arrives after its contents.

  }
  private boolean reserveScreen;
  private long visitsUntil;
  private Object requestedVisitsMenu;
  private List<String> lastItems = List.of();
  private String patternText = "", evidenceOwner = "";
  private Pattern pattern;
  private IslandBoard.Proposal proposal;
  private long ownPower = -1, powerAt;
  private boolean awardConsumed;
  public String status = "Waiting for public island rankings", estimate = "Unknown";
  public List<String> samples = List.of();

  IslandMeasurements(ClosestPlayerService service) {
    this.service = service;
  }

  void reset() {
    requestedVisitsMenu = null;
    visitsUntil = 0; pageHint = 1; needOwner = askedOwner = browseMessage = foundDuringBrowse = false; ownerUntil = 0;
    menu = null;
    menuTitle = "";
    revision = -1;
    stableAt = nextReminder = valueUntil = reserveUntil = reminderAt = 0;
    lastPage = -1;
    lastPages = 0;
    lastItems = List.of();
    reserveScreen = false;
    awardConsumed = false;
    powerAt = 0;
    proposal = null;
    ownPower = -1;
    evidenceOwner = "";
    samples = List.of();
    estimate = "Unknown";
    status = "Open /is visits to observe a page";
  }

  void cancelReminder() {
    reminderAt = 0;
  }

  void command(String command, long now) {
    requestedVisitsMenu = null;
    visitsUntil = command.matches("is (visits|top)(?: [0-9]+)?") ? now + 8000 : 0;
    if (visitsUntil > 0) {
      pageHint = 1; browseMessage = foundDuringBrowse = false;
      needOwner = service.history.owner.isEmpty() && !askedOwner;
    }
    if (command.equals("is info")) { ownerUntil = now + 10000; askedOwner = true; }
    valueUntil = command.equals("is value") ? now + 10000 : 0;
    reserveUntil = 0;
    proposal = null;
  }

  void capture(String raw, long now) {
    if (raw.length() > 4096) return;
    readOwner(raw, now);
    if (valueUntil >= now) {
      long value = IslandBoard.ownValue(raw);
      if (value >= 0) {
        valueUntil = 0;
        ownPower = value;
        powerAt = now;
        updateEstimate(now);
        status = "Own power " + value + " · /is value";
      }
    }
    if (!reserveScreen && reserveUntil < now) return;
    if (IslandBoard.rejected(raw)) {
      proposal = null;
      reserveUntil = 0;
      status = "Reserve conversion rejected";
      return;
    }
    long gain = IslandBoard.gain(raw);
    if (gain <= 0 || awardConsumed) return;
    awardConsumed = true;
    // Preview totals are usable only when the accepted delta agrees. Other members can convert too.
    boolean matchedPreview = proposal != null && proposal.after() - proposal.before() == gain;
    boolean recentPower =
        powerAt > 0
            && now >= powerAt
            && now - powerAt <= FRESH
            && ownPower >= 0
            && ownPower <= Long.MAX_VALUE - gain;
    ownPower =
        matchedPreview ? proposal.after() : proposal == null && recentPower ? ownPower + gain : -1;
    // An inferred total cannot serve as the starting value of another conversion.
    powerAt = 0;
    proposal = null;
    reserveUntil = 0;
    status =
        "Award +"
            + gain
            + (ownPower < 0 ? " · new total unknown" : " · estimated power " + ownPower);
    updateEstimate(now);
  }

  private void updateEstimate(long now) {
    var h = service.history;
    estimate =
        now - h.snapshotAt > FRESH
            ? "Unknown · stale snapshot"
            : IslandBoard.estimate(h.islands.values(), evidenceOwner, ownPower);
  }

  public void publicRank(ExcaliaApiData.Island island, List<ExcaliaApiData.Island> board, long at) {
    String owner = island.owner().toLowerCase(Locale.ROOT);
    var old = service.history.boards.get("island/" + owner);
    if (old != null && old.at() >= at) return;
    service.history.owner = evidenceOwner = owner;
    needOwner = false; askedOwner = foundDuringBrowse = true;
    boolean isolated = board.stream().filter(i -> Math.abs(i.rank() - island.rank()) == 1)
        .noneMatch(i -> i.power() == island.power());
    service.confirm("island/" + owner, "Island power", island.rank(), at, isolated);
    estimate = "Confirmed #" + island.rank(); status = "Public island ranking updated";
    if (at >= powerAt) { ownPower = island.power(); powerAt = at; }
    service.saveHistory();
  }

  public void publicPower(long power, long at) {
    if (at < powerAt) return;
    ownPower = power; powerAt = at;
    updateEstimate(System.currentTimeMillis());
    status = "Island power · " + Numbers.integer(power);
  }

  public boolean needsOwner() { return needOwner; }
  public void ownerRequested() {
    needOwner = false; askedOwner = true; ownerUntil = System.currentTimeMillis() + 10000;
  }

  void tick(Minecraft mc, long now, long inventoryRevision) {
    var screen = mc.gui.screen();
    if (needOwner) status = "Open Leaderboards and click /is info to identify your island leader";
    var settings = service.settings();
    if (now - service.history.snapshotAt > FRESH && estimate.startsWith("Estimated"))
      estimate = "Unknown · stale snapshot";
    if (screen != menu) {
      // Permit a short server acknowledgement after closing the conversion dialog.
      if (reserveScreen) reserveUntil = now + 5000;
      else if (reserveUntil < now) proposal = null;
      if (requestedVisitsMenu != screen) requestedVisitsMenu = null;
      reserveScreen = false;
      menu = screen;
      menuTitle = screen == null ? "" : JobXp.normalize(screen.getTitle().getString());
      revision = -1;
      stableAt = now;
      if (screen instanceof AbstractContainerScreen<?>) awardConsumed = false;
      lastPage = -1;
      lastItems = List.of();
    }
    if (!(screen instanceof AbstractContainerScreen<?> chest) || mc.player == null) return;
    String title = menuTitle;
    if (visitsUntil >= now && requestedVisitsMenu == null && !title.contains("reputation")) {
      requestedVisitsMenu = screen;
      visitsUntil = 0;
    }
    if (requestedVisitsMenu != screen) requestedVisitsMenu = null;
    boolean reserve = title.contains("reserve") || reserveUntil >= now && title.contains("conver");
    reserveScreen = reserve;
    if (!reserve) {
      reserveUntil = 0; proposal = null; return;
    }
    if (revision != inventoryRevision) {
      revision = inventoryRevision;
      stableAt = now;
    }
    if (stableAt == 0 || now - stableAt < 400) return;
    stableAt = 0;
    IslandBoard.Proposal preview = null;
    for (var slot : chest.getMenu().slots) {
      if (slot.container == mc.player.getInventory() || slot.getItem().isEmpty()) continue;
      var stack = slot.getItem();
      var lore = stack.get(DataComponents.LORE);
      if (lore == null) continue;
      StringBuilder raw = new StringBuilder(stack.getHoverName().getString());
      for (var line : lore.lines()) {
        if (raw.length() > 2048) break;
        raw.append('\n').append(line.getString());
      }
      if (raw.length() > 2048) continue;
      reserveUntil = now + 30000;
      var candidate = IslandBoard.proposal(raw.toString());
      if (candidate != null) preview=candidate;
    }
    if (preview != null && !preview.equals(proposal)) awardConsumed=false;
    proposal=preview;
  }

  void observe(List<String> items, int page, int pages, int total, String self, long now) {
    samples = List.copyOf(items);
    String expression = service.settings().islandPattern;
    if (!Objects.equals(patternText, expression) || pattern == null) {
      try {
        pattern = IslandBoard.compile(expression);
        patternText = expression;
      } catch (IllegalArgumentException e) {
        status = "Invalid island pattern";
        return;
      }
    }
    var entries = new TreeMap<Integer, IslandBoard.Entry>();
    var leaders = new HashSet<String>();
    int offset = (Math.max(1, page) - 1) * 28;
    for (int i = 0; i < items.size(); i++) {
      var e = IslandBoard.visit(pattern, items.get(i), self, offset + i + 1);
      if (e == null) continue;
      if (!leaders.add(e.leader())
          || entries.put(e.rank(), e) != null
          || page > 0 && (e.rank() - 1) / 28 + 1 != page) {
        status = "Conflicting island positions";
        return;
      }
    }
    status =
        "Page "
            + (page > 0 ? page + "/" + pages : "unknown")
            + " · "
            + entries.size()
            + "/"
            + items.size()
            + " entries read";
    if (entries.isEmpty()) return;
    IslandBoard.Entry previous = null;
    for (var e : entries.values()) {
      if (previous != null && distinct(e, previous)) {
        status = "Inconsistent island power order";
        return;
      }
      previous = e;
    }
    var h = service.history;
    // Bound a scan by the first page timestamp, including across reopen/reconnect.
    // Revisiting a page starts a new snapshot, as does conflicting cross-page evidence.
    boolean restart = now < h.snapshotAt || now - h.snapshotAt >= FRESH;
    if (h.islands.isEmpty() || restart) { h.islands.clear(); h.snapshotAt = now; }
    if (page > 0) h.islands.subMap(offset + 1, true, offset + 28, true).clear();
    // Owner keys retain tied scores and cached pages. Power is always the comparison value.
    for (var e : entries.values()) {
      h.islands.values().removeIf(old -> old.leader().equals(e.leader()));
      h.cacheIsland(e);
    }
    h.islands.putAll(entries);
    var own = entries.values().stream().filter(e -> e.own(self) || e.leader().equalsIgnoreCase(h.owner)).toList();
    if (own.size() > 1) {
      evidenceOwner = "";
      ownPower = -1;
      powerAt = 0;
      estimate = "Unknown · conflicting island membership";
    }
    if (own.size() == 1) {
      var e = own.getFirst();
      h.owner = evidenceOwner = e.leader();
      powerAt = now;
      ownPower = e.precision() == 1 ? e.power() : -1;
      estimate = "Confirmed #" + e.rank();
      browseMessage = false; foundDuringBrowse = true;
      boolean wholePage =
          page >= 1
              && pages >= page
              && entries.size() == items.size()
              && entries.firstKey() == (page - 1) * 28 + 1
              && entries.lastKey() - entries.firstKey() + 1 == entries.size()
              && (page == pages || entries.size() == 28);
      var above = h.islands.get(e.rank() - 1);
      var below = h.islands.get(e.rank() + 1);
      boolean isolated =
          (above == null ? e.rank() == 1 : distinct(above, e))
              && (below == null
                  ? e.rank() == total && page == pages && page > 0
                  : distinct(e, below));
      service.confirm("island/" + e.leader(), "Island power", e.rank(), now, wholePage && isolated);
      if (!wholePage || !isolated) status += " · partial page or tie; notifications paused";
    }
    if (own.isEmpty() && !foundDuringBrowse && service.settings().islandBrowseHint) {
      status += " · Island not found. Browse pages";
      if (!browseMessage) service.chat("Island not found. Browse pages");
      browseMessage = true;
    }
    service.saveHistory();
    nextReminder = now + 180000;
    reminderAt = 0;
  }

  private boolean distinct(IslandBoard.Entry higher, IslandBoard.Entry lower) {
    return higher.power() - lower.power() > higher.precision() - 1 + lower.precision() - 1;
  }
}
