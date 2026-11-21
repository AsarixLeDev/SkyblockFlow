package dev.asarix.skyblockflow;

import com.google.gson.JsonParser;
import dev.asarix.skyblockflow.core.LiquipediaMatches;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public final class RocketLeagueFeed {
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private volatile List<LiquipediaMatches.Match> matches = List.of();
  private volatile String status = "Waiting for Liquipedia";
  private volatile long next;
  private long lastRequestNanos = Long.MIN_VALUE / 2;
  private volatile boolean running;
  private volatile long updated;

  private static long requestAfter;

  static synchronized void rateLimit() throws InterruptedException {
    long delay = requestAfter - System.nanoTime();
    if (delay > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
    requestAfter = System.nanoTime() + 2_100_000_000L;
  }

  public void tick(boolean enabled) {
    if (!enabled || running || System.currentTimeMillis() < next || cooldownSeconds() > 0) return;
    request();
  }

  public long cooldownSeconds() { return Math.max(0, (60_000_000_000L - (System.nanoTime() - lastRequestNanos) + 999_999_999L) / 1_000_000_000L); }
  public String updateNow() {
    long seconds = cooldownSeconds();
    if (seconds > 0) return "Please wait " + seconds + " seconds";
    if (running) return "Update in progress";
    request();
    return "Updating matches…";
  }
  private void request() {
    running = true;
    lastRequestNanos = System.nanoTime();
    next = System.currentTimeMillis() + 60_000;
    Thread.ofPlatform()
        .daemon()
        .name("SkyblockFlow Liquipedia")
        .start(
            () -> {
              try {
                var request =
                    HttpRequest.newBuilder(
                            URI.create(
                                "https://liquipedia.net/rocketleague/api.php?action=parse&page=Main_Page&prop=text&format=json"))
                        .timeout(Duration.ofSeconds(20))
                        .header(
                            "User-Agent",
                            "SkyblockFlow/2.3 (Minecraft overlay; contact: https://github.com/AsarixLeDev/SkyblockFlow)")
                        .header("Accept-Encoding", "gzip")
                        .GET()
                        .build();
                rateLimit();
                var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                  if (response.statusCode() != 200)
                    throw new java.io.IOException("HTTP " + response.statusCode());
                  var input =
                      response
                              .headers()
                              .firstValue("Content-Encoding")
                              .orElse("")
                              .equalsIgnoreCase("gzip")
                          ? new java.util.zip.GZIPInputStream(body)
                          : body;
                  byte[] bytes = input.readNBytes(4_000_001);
                  if (bytes.length > 4_000_000) throw new java.io.IOException("Response too large");
                  var root =
                      JsonParser.parseString(
                              new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                          .getAsJsonObject();
                  if (!root.has("parse"))
                    throw new java.io.IOException("API response missing matches");
                  String html =
                      root.getAsJsonObject("parse").getAsJsonObject("text").get("*").getAsString();
                  matches = LiquipediaMatches.parse(html, System.currentTimeMillis() / 1000);
                  updated = System.currentTimeMillis();
                  status = matches.isEmpty() ? "No match scheduled" : "Matches updated";
                  next = LiquipediaMatches.nextPoll(matches, updated);
                }
              } catch (Exception e) {
                status = "Liquipedia unavailable";
                next = Math.max(System.currentTimeMillis() + 300_000, LiquipediaMatches.nextPoll(matches, System.currentTimeMillis()));
              } finally {
                running = false;
              }
            });
  }

  public LiquipediaMatches.Match current() {
    long now = System.currentTimeMillis() / 1000;
    var relevant = matches.stream().filter(m -> m.visible(now)).toList();
    var live = relevant.stream().filter(LiquipediaMatches.Match::live).toList();
    var list = live.isEmpty() ? relevant : live;
    return list.isEmpty() ? null : list.get((int) (now / 15 % list.size()));
  }

  public String when(LiquipediaMatches.Match m) {
    if (m.live()) return "LIVE";
    if (m.startsAt() <= System.currentTimeMillis() / 1000) return "Starting soon";
    var date = java.time.Instant.ofEpochSecond(m.startsAt()).atZone(java.time.ZoneId.systemDefault());
    long minutes = Math.max(1, (m.startsAt() - System.currentTimeMillis() / 1000 + 59) / 60);
    return java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(date) + " · in "
        + (minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m");
  }
  public String age() { return updated == 0 ? "" : "Updated " + Math.max(0, (System.currentTimeMillis() - updated) / 1000) + "s ago"; }
  public List<String> rows() { return rows(false); }
  public List<String> rows(boolean showUpdated) {
    var rows = new ArrayList<String>();
    rows.add("ROCKET LEAGUE");
    var m = current();
    if (m == null) rows.add(status.equals("Liquipedia unavailable") ? status : "No match in the next 24 hours");
    else {
      rows.add(m.event().isBlank() ? "Rocket League" : m.event());
      rows.add(when(m));
      rows.add(m.left() + "  vs  " + m.right());
      if (m.live()) { rows.add(m.series()); rows.add(m.game()); }
    }
    if (showUpdated && updated > 0) rows.add(age());
    return rows;
  }
}
