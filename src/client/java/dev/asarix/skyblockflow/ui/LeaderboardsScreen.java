package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.*;
import dev.asarix.skyblockflow.core.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.gui.screens.Screen;

public final class LeaderboardsScreen extends FormScreen {
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());
  private int ticks;

  public LeaderboardsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Leaderboards");
  }

  protected boolean readOnly() {
    return true;
  }

  protected void commit() {
    onClose();
  }

  protected String description() {
    return "This server and account · assign measurement keys in Minecraft Controls.";
  }

  public void tick() {
    if (++ticks % 20 == 0) rebuildWidgets();
  }

  protected void buildForm() {
    var service = app.closest;
    page("Jobs");
    int half = (bodyW - 6) / 2;
    action("Measure all", 0, 0, half, service::measureAll);
    action("Measure current", half + 6, 0, half, service::measureCurrent);
    String next = service.nextCommand();
    if (!next.isEmpty()) action("Run /" + next, 0, 30, bodyW, () -> service.executeNext(next));
    action("Cancel measurement", half + 6, 64, half, service::cancel);
    label(service.status, 0, 98);
    label(service.progress(), 0, 118);
    action(
        "Measurement settings",
        0,
        148,
        bodyW,
        () -> minecraft.gui.setScreen(new ClosestSettingsScreen(app, this, service.settings())));
    int y = 184;
    for (var id : JobXp.NAMES.keySet()) {
      var rank = service.history.boards.get(id);
      action(
          JobXp.name(id)
              + " · "
              + (rank == null
                  ? "Unmeasured"
                  : "#" + rank.position() + " · " + TIME.format(Instant.ofEpochMilli(rank.at()))),
          0,
          y,
          bodyW,
          () -> service.measure(id));
      String detail = service.lead(id);
      label(detail.isEmpty() ? service.outcome(id) : "Lead over #2 · " + detail, 0, y + 25);
      y += 50;
    }
    page("Island power");
    var island = service.islands;
    label(
        "Open /is visits and use its page arrows to browse islands.", 0, 0);
    label(island.status, 0, 26);
    var h = service.history;
    var last = h.boards.get("island/" + h.owner);
    label(
        last == null
            ? "Last confirmed rank unknown"
            : "Last confirmed · "
                + h.owner
                + " #"
                + last.position()
                + " · "
                + TIME.format(Instant.ofEpochMilli(last.at())),
        0,
        52);
    long age = System.currentTimeMillis() - h.snapshotAt;
    label(
        h.snapshotAt == 0
            ? "No snapshot"
            : "Snapshot · "
                + TIME.format(Instant.ofEpochMilli(h.snapshotAt))
                + " · "
                + h.islands.size()
                + " entries"
                + (age > 60000 ? " · stale" : ""),
        0,
        76);
    label("Position · " + island.estimate, 0, 100);
    action("I changed of leader", 0, 168, bodyW, island::leaderChanged);
    action("Browse-page hint: " + (service.settings().islandBrowseHint ? "ON" : "OFF"), 0, 202, bodyW, () -> {
      service.settings().islandBrowseHint = !service.settings().islandBrowseHint; app.save(); rebuildWidgets();
    });
    if (island.needsOwner()) action("Run /is info", 0, 270, bodyW, () -> service.executeNext("is info"));
    label("Island leader · " + (h.owner.isEmpty() ? "Not found yet" : h.owner), 0, 238);
    action(
        "Preview observed item and edit pattern",
        0,
        132,
        bodyW,
        () -> minecraft.gui.setScreen(new IslandPatternScreen(app, this)));
  }
}

final class IslandPatternScreen extends FormScreen {
  private int index;

  IslandPatternScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Island item pattern");
  }

  protected String description() {
    return "Patterns match normalized item names and lore with named groups rank, leader and"
               + " power.";
  }

  private String sample() {
    var samples = app.closest.islands.samples;
    return samples.isEmpty() ? "" : samples.get(Math.floorMod(index, samples.size()));
  }

  protected void buildForm() {
    field("pattern", "Island entry regex", app.closest.settings().islandPattern, 0);
    action(
        "Validate and preview",
        0,
        48,
        bodyW,
        () -> {
          var pattern = IslandBoard.compile(value("pattern"));
          var entry = IslandBoard.parse(pattern, sample(), minecraft.getUser().getName());
          notice =
              sample().isEmpty()
                  ? "Pattern valid · open a visits page for a sample"
                  : entry == null
                      ? "No match for this item"
                      : "#"
                          + entry.rank()
                          + " · "
                          + entry.leader()
                          + " · "
                          + entry.power()
                          + " power";
        });
    action(
        "Next observed item",
        0,
        80,
        bodyW,
        () -> {
          index++;
          rebuildWidgets();
        });
    action(
        "Restore default pattern",
        0,
        112,
        bodyW,
        () -> {
          values.put("pattern", IslandBoard.DEFAULT_PATTERN);
          rebuildWidgets();
        });
    int y = 150;
    for (var line : JobXp.normalize(sample()).split("\n")) {
      label(line, 0, y);
      y += 18;
    }
  }

  protected void commit() {
    IslandBoard.compile(value("pattern"));
    app.closest.settings().islandPattern = value("pattern");
    if (app.save()) onClose();
  }
}
