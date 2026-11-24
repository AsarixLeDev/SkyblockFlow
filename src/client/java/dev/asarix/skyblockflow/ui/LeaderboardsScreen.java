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
    int half = (bodyW-6)/2;
    action("Refresh rankings",0,0,half,service::measureAll);
    action("Public data settings",half+6,0,half,()->minecraft.gui.setScreen(new PublicDataScreen(app,this)));
    label(service.status,0,34);
    int y=62;
    for (var id : JobXp.NAMES.keySet()) {
      String rank=app.excaliaApi.rank(id,minecraft.getUser().getName());
      action(JobXp.name(id)+"  ·  "+rank,0,y,bodyW,()->service.measure(id));
      String detail = service.lead(id);
      label(detail.isEmpty() ? service.outcome(id) : "Lead over #2 · " + detail, 0, y + 25);
      y += 50;
    }
    page("Island & balance");
    var island=service.islands;
    label("Island power",0,0);
    label(island.estimate,0,22);
    action("Refresh public rankings",0,52,bodyW,service::measureAll);
    var money=app.excaliaApi.snapshot(ExcaliaApiClient.Feed.MONEY);
    String rank=money==null ? app.excaliaApi.state(ExcaliaApiClient.Feed.MONEY) :
        money.profiles().forPlayer(minecraft.getUser().getName()).stream()
        .filter(field->field.label().equals("Rank")).map(ProfileIndex.Field::value).findFirst().orElse("Unranked");
    label("Skydollars   ·   "+rank,0,90);
    action("View player profile",0,120,bodyW,()->minecraft.gui.setScreen(
        new PlayerViewScreen(app,this,minecraft.getUser().getName())));

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
