package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.IslandFlightLogic.*;
import dev.asarix.skyblockflow.core.Models.*;
import dev.asarix.skyblockflow.mixin.IslandTabAccess;
import java.util.*;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class IslandFlight {
  public static final String STORE = "https://store.excalia.fr/shop";
  private static final Pattern SERVER = Pattern.compile("(?:serveur|server)\\s*[:»|•➜→]\\s*([a-z0-9][a-z0-9_.:+-]{1,80})",Pattern.CASE_INSENSITIVE);
  private final SkyblockFlowClient app;
  private final IslandFlightLogic logic = new IslandFlightLogic();
  private final Map<Integer, Rank> rankGlyphs = new HashMap<>();
  private Object resources, level;
  private long world, nextTick;
  private Rank rank = Rank.UNKNOWN;
  public String status = "Join Excalia to learn your island";

  public IslandFlight(SkyblockFlowClient app) {
    this.app = app;
    ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
      if (!overlay && connected()) logic.message(message.getString(), System.currentTimeMillis());
    });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { logic.reset(); level = null; rank = Rank.UNKNOWN; });
  }
  public IslandFlightSettings settings() { return app.store.config.tools.islandFlight; }
  private boolean connected() { return app.recipes != null && app.recipes.onExcalia() && Minecraft.getInstance().player != null; }
  public Rank rank() { return rank; }
  public boolean enabled() { return settings().enabled && app.store.config.enabled(Feature.ISLAND_FLIGHT); }
  public String account() { return Minecraft.getInstance().getUser().getProfileId() + "@" + app.serverKey(); }
  public IslandFlightSettings.Home home() { return settings().homes.get(account()); }
  public void forget() { settings().homes.remove(account()); logic.reset(); app.save(); status = "Use /is go or /is tp to learn your island"; }
  public boolean setEnabled(boolean enabled) {
    if (enabled && rank == Rank.CITIZEN) { status = "VIP or higher is required. Get a rank at store.excalia.fr."; return false; }
    settings().enabled = enabled; app.store.config.feature(Feature.ISLAND_FLIGHT).enabled = enabled; app.save(); return true;
  }
  public void commandSent(String command) { if (connected() && enabled()) logic.command(command, System.currentTimeMillis()); }

  private long worldId(Minecraft mc){
    if(level!=mc.level){level=mc.level;world++;}
    return world;
  }
  public void positionPacket(){
    var mc=Minecraft.getInstance();
    if(!connected() || !enabled() || mc.level==null || !mc.player.isAlive())return;
    logic.teleported(worldId(mc),mc.level.dimension().identifier().toString(),mc.player.getX(),mc.player.getZ(),System.currentTimeMillis());
  }

  private void tick(Minecraft mc) {
    long now = System.currentTimeMillis();
    if (now < nextTick) return;
    nextTick = now + 100;
    if (!connected() || mc.level == null || mc.getConnection() == null) { rank = Rank.UNKNOWN; return; }
    rank = readRank(mc);
    var tab = (IslandTabAccess)mc.gui.hud.getTabList();
    String headings = plain(tab.skyblockflow$header()) + "\n" + plain(tab.skyblockflow$footer());
    var sidebar = ClosestPlayerService.sidebar();
    if (sidebar != null) headings += "\n" + String.join("\n", ClosestPlayerService.lines(sidebar));
    String server = server(headings);
    var border = mc.level.getWorldBorder();
    boolean available = mc.getConnection().getCommands().getRoot().getChild("fly") != null;
    var sample = new Sample(worldId(mc), server, mc.level.dimension().identifier().toString(), mc.player.getX(), mc.player.getZ(),
        border.getCenterX(), border.getCenterZ(), border.getSize(), rank, available, mc.player.getAbilities().mayfly);
    var action = logic.tick(sample, settings(), account(), enabled() && mc.player.isAlive(), now);
    if (action == Action.LEARNED) { app.save(); status = "Island learned"; }
    else if (action == Action.FLY) { mc.player.connection.sendCommand(IslandFlightLogic.ENABLE_FLIGHT_COMMAND); status = "Flight requested on your island"; }
    else if (action == Action.LEARN_NEEDED) {
      String help = "Enter /is go or /is tp to learn your island.";
      mc.gui.hud.getChat().addServerSystemMessage(Component.literal("[SkyblockFlow] " + help).withColor(0x79dfbe));
      app.notifyGeneral("Island flight", "Set your island", help, new SoundSettings());
      status = help;
    }
    if (rank == Rank.CITIZEN) status = "VIP or higher is required. Get a rank at store.excalia.fr.";
    else if (rank == Rank.UNKNOWN) status = "Waiting for your rank in tab";
    else if (!enabled()) status = "Disabled";
    else if (home() == null) status = "Use /is go or /is tp to learn your island";
  }

  static String server(String text) {
    var match = SERVER.matcher(JobXp.normalize(text));
    return match.find() ? match.group(1) : "";
  }
  private static String plain(Component text) { return text == null ? "" : text.getString(); }
  private Rank readRank(Minecraft mc) {
    var info = mc.getConnection().getPlayerInfo(mc.getUser().getProfileId());
    String username = mc.player.getGameProfile().name();
    String text = info == null ? "" : plain(info.getTabListDisplayName()).replace(username, "");
    var team = mc.player.getTeam();
    if (team != null) text += " " + plain(team.getPlayerPrefix()) + " " + team.getName().replace(username, "");
    var rank = IslandFlightLogic.rank(text);
    if (rank != Rank.UNKNOWN) return rank;
    loadGlyphs(mc);
    for (int cp : text.codePoints().toArray()) if (rankGlyphs.containsKey(cp)) return rankGlyphs.get(cp);
    return Rank.UNKNOWN;
  }
  private void loadGlyphs(Minecraft mc) {
    var packs = mc.getResourceManager().listPacks().toList();
    if (packs.equals(resources)) return;
    resources = packs; rankGlyphs.clear();
    int remaining = 512;
    for (var entry : mc.getResourceManager().listResources("font", id -> id.getPath().endsWith(".json")).values()) {
      if (--remaining < 0) break;
      try (var reader = entry.openAsReader()) {
        var root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        if (!root.has("providers")) continue;
        for (var element : root.getAsJsonArray("providers")) {
          var provider = element.getAsJsonObject();
          if (!provider.has("file") || !provider.has("chars")) continue;
          String file = provider.get("file").getAsString().replaceAll("[^a-zA-Z0-9]", " ");
          var decoded = IslandFlightLogic.rank(file);
          if (decoded == Rank.UNKNOWN) continue;
          for (var row : provider.getAsJsonArray("chars")) for (int cp : row.getAsString().codePoints().toArray())
            if (Character.getType(cp) == Character.PRIVATE_USE) rankGlyphs.put(cp, decoded);
        }
      } catch (java.io.IOException | RuntimeException ignored) { }
    }
  }
}
