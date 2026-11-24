package dev.asarix.skyblockflow.core;

import java.util.regex.Pattern;
import dev.asarix.skyblockflow.core.IslandFlightSettings.Home;

public final class IslandFlightLogic {
  public enum Rank {
    UNKNOWN("Waiting for tab"), CITIZEN("Citoyen · upgrade required"), VIP("VIP"),
    MVP("MVP"), MVP_PLUS("MVP+"), HERO("Héros");
    public final String label;
    Rank(String label) { this.label = label; }
    public boolean canFly() { return this != UNKNOWN && this != CITIZEN; }
  }
  public static final String ENABLE_FLIGHT_COMMAND = "fly enable";
  private static final Pattern TEXT_RANK = Pattern.compile(
      "(?<![a-z0-9])(?:mvp(?:\\+| +plus)?|vip\\+?|heros|citoyen)(?![a-z0-9])");
  public enum Action { NONE, LEARNED, FLY, LEARN_NEEDED }
  public record Sample(long world, String server, String dimension, double x, double z,
                       double centerX, double centerZ, double size, Rank rank, boolean commandAvailable, boolean mayFly) {
    Home home() {
      var h = new Home(); h.server = server; h.dimension = dimension; h.centerX = centerX; h.centerZ = centerZ; h.size = size;
      return h;
    }
  }
  private Sample last;
  private long learnUntil, stableSince, arrivalUntil, lastNotice;
  private boolean confirmed, prompted;
  private record Destination(long world,String dimension,double x,double z){}
  private Destination destination;

  public void teleported(long world,String dimension,double x,double z,long now) {
    destination = new Destination(world,dimension,x,z);
    arrivalUntil = now + 10_000;
  }

  public void reset() { last = null; learnUntil = stableSince = arrivalUntil = lastNotice = 0; confirmed = prompted = false; destination = null; }
  public void command(String command, long now) {
    if(command.strip().matches("(?i)/?fly(?:\\s+(?:off|disable))?"))destination=null;
    if (command.strip().matches("(?i)/?(?:is|island)\\s+(?:go|tp|home)")) {
      learnUntil = now + 20_000; stableSince = 0; confirmed = false; destination = null;
    }
  }
  public void message(String text, long now) {
    if (now <= learnUntil && JobXp.normalize(text).strip().matches("\\[skyllia]:\\s*you have been teleported to your island\\.")) {
      confirmed = true; stableSince = now;
    }
  }

  public static Rank rank(String tabText) {
    // Season 3 Oraxen tab badges, identified by matching the individual grade PNGs
    // to their cells in the packed font atlas. Most are ordinary Unicode code points.
    for (int i = 0; i < tabText.length(); i++) {
      switch (tabText.charAt(i)) {
        case 0xA489: return Rank.CITIZEN;
        case 0xA473: return Rank.VIP;
        case 0xA477: return Rank.MVP;
        case 0xA479: return Rank.MVP_PLUS;
        case 0xF700: return Rank.HERO;
        default: break;
      }
    }
    var match = TEXT_RANK.matcher(JobXp.normalize(tabText).replace('_', ' '));
    if (!match.find()) return Rank.UNKNOWN;
    return switch (match.group()) {
      case "citoyen" -> Rank.CITIZEN;
      case "heros" -> Rank.HERO;
      case "vip", "vip+" -> Rank.VIP;
      case "mvp" -> Rank.MVP;
      default -> Rank.MVP_PLUS;
    };
  }

  public Action tick(Sample s, IslandFlightSettings settings, String account, boolean enabled, long now) {
    Sample before = last; last = s;
    if (!enabled || s.rank == Rank.CITIZEN) { destination = null; return Action.NONE; }
    var home = settings.homes.get(account);
    boolean changed = before != null && (before.world != s.world || !before.server.isEmpty() && !s.server.isEmpty() && !before.server.equals(s.server));
    if (changed) prompted = false;
    if (confirmed && now <= learnUntil) {
      if (before == null || changed || Math.hypot(s.x-before.x, s.z-before.z) > .25) stableSince = now;
      var candidate = s.home();
      if (now-stableSince >= 750 && candidate.valid() && candidate.contains(s.x,s.z)) {
        settings.homes.put(account, candidate); learnUntil = 0; confirmed = false;
        teleported(s.world,s.dimension,s.x,s.z,now);
        return Action.LEARNED;
      }
    }
    if (now > learnUntil) confirmed = false;
    if (home == null) {
      if (changed && s.rank.canFly() && (lastNotice == 0 || now-lastNotice >= 30_000) && !prompted && !confirmed) {
        lastNotice = now; prompted = true; return Action.LEARN_NEEDED;
      }
      return Action.NONE;
    }
    if(destination==null)return Action.NONE;
    if(now>arrivalUntil || destination.world!=s.world || !destination.dimension.equals(home.dimension)
        || !s.dimension.equals(home.dimension)){
      destination=null;return Action.NONE;
    }
    if(!home.server.isEmpty() && !s.server.isEmpty() && !home.server.equals(s.server))return Action.NONE;
    // Border packets can follow the position packet. A finite border at a different
    // center is a different island; wait for synchronization before making a decision.
    var bounds=home;
    var live=s.home();
    if(live.valid()){
      if(Math.abs(home.centerX-live.centerX)>1 || Math.abs(home.centerZ-live.centerZ)>1)return Action.NONE;
      bounds=live;
    }
    if(!bounds.contains(destination.x,destination.z))return Action.NONE;
    if(!bounds.contains(s.x,s.z)){destination=null;return Action.NONE;}
    if(s.mayFly){
      // The new island's abilities can arrive shortly after its position.
      arrivalUntil=Math.min(arrivalUntil,now+1500);
      return Action.NONE;
    }
    if(!s.rank.canFly() || !s.commandAvailable)return Action.NONE;
    destination=null;
    return Action.FLY;
  }
}
