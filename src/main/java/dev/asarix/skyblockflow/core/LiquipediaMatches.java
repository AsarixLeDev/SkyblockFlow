package dev.asarix.skyblockflow.core;

import java.net.URI;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

public final class LiquipediaMatches {
  public record Match(
      String left,
      String right,
      String series,
      String game,
      String logoLeft,
      String logoRight,
      String event, long startsAt, boolean live) {
    public Match(String left, String right, String series, String game, String logoLeft, String logoRight, String event) {
      this(left, right, series, game, logoLeft, logoRight, event, 0, true);
    }
    public boolean visible(long now) { return live ? startsAt <= now && startsAt > now - 21600 : startsAt >= now - 900 && startsAt < now + 86400; }
  }

  /** Grid-aligned local hours/half-hours/quarter-hours, or minutes during a match. */
  public static long nextPoll(List<Match> matches, long nowMs) {
    var t = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault())
        .withSecond(0).withNano(0).plusMinutes(1);
    long next = t.plusHours(1).toInstant().toEpochMilli();
    // Evaluate the cadence at each future boundary so crossing the 24h/2h threshold
    // does not postpone the first faster refresh until the previous cadence expires.
    for (int i = 0; i < 60; i++, t = t.plusMinutes(1)) {
      long at = t.toEpochSecond();
      int minutes = 60;
      for (var m : matches) {
        long away = m.startsAt - at;
        if (m.live && m.visible(at) || !m.live && away <= 0 && away >= -900) { minutes = 1; break; }
        if (away > 0 && away <= 7200) minutes = Math.min(minutes, 15);
        else if (away > 0 && away <= 86400) minutes = Math.min(minutes, 30);
      }
      if (t.getMinute() % minutes == 0) { next = t.toInstant().toEpochMilli(); break; }
    }
    for (var m : matches) if (m.startsAt * 1000 > nowMs) next = Math.min(next, m.startsAt * 1000);
    return next;
  }

  public static List<Match> parse(String html, long now) {
    var doc = Jsoup.parse(html, "https://liquipedia.net");
    var out = new ArrayList<Match>();
    for (var e : doc.select(".match-info, .brkts-match-info-popup, .match-ticker-card")) {
      var timer = e.selectFirst(".timer-object");
      if (timer == null || Set.of("finished", "true", "1").contains(timer.attr("data-finished"))) continue;
      long start;
      try {
        start = Long.parseLong(timer.attr("data-timestamp"));
      } catch (NumberFormatException ex) {
        continue;
      }
      if (start > now + 7 * 86400 || start < now - 6 * 3600) continue;
      boolean ticker = e.hasClass("match-ticker-card");
      var teams = e.select(ticker ? ".match-ticker-card__opponent" : ".match-info-header-opponent");
      if (teams.size() != 2
          || !e.select(".match-info-header-winner, .match-ticker-card__opponent.winner").isEmpty()) continue;
      var scores = ticker ? teams.stream().map(LiquipediaMatches::tickerScore).toList()
          : e.select(".match-info-header-scoreholder-score").eachText();
      boolean scored = scores.size() == 2 && scores.get(0).matches("[0-9]+") && scores.get(1).matches("[0-9]+");
      boolean live = start <= now && scored;
      if (start < now - 900 && !live) continue;
      String game = "Game score not published";
      for (var g : e.select(".brkts-popup-body-game"))
        if (g.select("[data-label-type=result-win], [data-label-type=result-loss]").isEmpty()) {
          var cells = g.select(".brkts-popup-spaced");
          if (cells.size() >= 3
              && cells.first().text().matches("[0-9]+")
              && cells.get(2).text().matches("[0-9]+"))
            game = "Game: " + cells.first().text() + " – " + cells.get(2).text();
        }
      String bo = e.attr("data-bestof");
      out.add(
          new Match(
              name(teams.get(0)),
              name(teams.get(1)),
              (live ? (bo.isBlank() ? "Series" : "BO" + bo) + ": "
                  + scores.get(0)
                  + " – "
                  + scores.get(1) : "Upcoming"),
              game,
              logo(teams.get(0)),
              logo(teams.get(1)),
              e.select(".match-info-tournament-name, .match-ticker-card-tournament-name").text(), start, live));
    }
    out.sort(Comparator.comparing(Match::live).reversed().thenComparingLong(Match::startsAt));
    return out.stream().distinct().limit(64).toList();
  }

  private static String tickerScore(Element opponent) {
    // Current ticker cards put the score after the team block, without a score class.
    var score = opponent.children().last();
    return score == null || score.hasClass("block-team") ? "" : score.text();
  }

  private static String name(Element e) {
    var name = e.selectFirst(".team-name");
    if (name == null) name = e.selectFirst(".name");
    return name == null ? "Unknown" : name.text();
  }

  private static String logo(Element team) {
    String dark = firstLogo(team.select(".team-template-darkmode img, .darkmode img"));
    if (!dark.isEmpty()) return dark;
    var images = team.select(".team-template-image-icon img, .team-template-image img");
    return firstLogo(images.isEmpty() ? team.select("img") : images);
  }

  private static String firstLogo(Iterable<Element> images) {
    for (var image : images) {
      for (String attribute : List.of("data-src", "src")) {
        URI logo = logoUri(image.absUrl(attribute));
        if (logo != null) return logo.toASCIIString();
      }
    }
    return "";
  }

  public static URI logoUri(String url) {
    if (url == null || url.isBlank()) return null;
    try {
      var uri = URI.create(url).normalize();
      String path = uri.getPath();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || !"liquipedia.net".equalsIgnoreCase(uri.getHost())
          || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443
          || path == null || !path.startsWith("/commons/images/")) return null;
      String lower = path.toLowerCase(Locale.ROOT);
      return lower.contains("rocket_league_default") || lower.contains("team_placeholder") ? null : uri;
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }
}
