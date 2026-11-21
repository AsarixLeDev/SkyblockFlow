package dev.asarix.skyblockflow.core;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Official name lookup fallback when Minecraft has cached a failed/rate-limited lookup. */
public final class PlayerProfileLookup {
  public record Identity(UUID id, String name) {}

  private record Cached(Identity profile, long expires) {}

  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final List<URI> services;
  private final Map<String, Cached> cache = new LinkedHashMap<>();

  public PlayerProfileLookup() {
    this(
        List.of(
            URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/"),
            URI.create("https://api.mojang.com/users/profiles/minecraft/")));
  }

  // Package-private transport endpoints let tests exercise real HTTP failure/fallback behavior.
  PlayerProfileLookup(List<URI> services) {
    this.services = List.copyOf(services);
  }

  public synchronized Identity find(String name) throws IOException, InterruptedException {
    if (name == null || !name.matches("[A-Za-z0-9_]{1,16}"))
      throw new IOException("Enter a Minecraft username (1–16 letters, numbers or underscores)");
    long now = System.currentTimeMillis();
    cache.values().removeIf(entry -> entry.expires <= now);
    String key = name.toLowerCase(Locale.ROOT);
    var saved = cache.get(key);
    if (saved != null) return saved.profile;
    int missing = 0;
    IOException failure = null;
    for (URI service : services) {
      try {
        var request =
            HttpRequest.newBuilder(service.resolve(name))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
          int status = response.statusCode();
          if (status == 404 || status == 204) {
            missing++;
            continue;
          }
          if (status != 200)
            throw new IOException(
                status == 429
                    ? "Minecraft profile lookup is rate-limited; try again shortly"
                    : "Minecraft profile service is unavailable (HTTP "
                        + status
                        + "); try again shortly");
          byte[] bytes = body.readNBytes(8193);
          if (bytes.length > 8192) throw new IOException("Minecraft profile response is too large");
          Identity identity;
          try {
            var data =
                JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String id = data.get("id").getAsString(), actualName = data.get("name").getAsString();
            if (!id.matches("[0-9a-fA-F]{32}") || !actualName.equalsIgnoreCase(name))
              throw new IllegalArgumentException();
            identity =
                new Identity(
                    UUID.fromString(
                        id.substring(0, 8)
                            + "-"
                            + id.substring(8, 12)
                            + "-"
                            + id.substring(12, 16)
                            + "-"
                            + id.substring(16, 20)
                            + "-"
                            + id.substring(20)),
                    actualName);
          } catch (RuntimeException invalid) {
            throw new IOException("Minecraft returned an invalid player profile", invalid);
          }
          if (cache.size() >= 128) cache.remove(cache.keySet().iterator().next());
          cache.put(key, new Cached(identity, System.currentTimeMillis() + 600000));
          return identity;
        }
      } catch (IOException unavailable) {
        failure = unavailable;
      }
    }
    if (missing == services.size()) throw new IOException("No Minecraft account named " + name);
    throw failure != null
        ? failure
        : new IOException("Minecraft profile lookup is unavailable; try again shortly");
  }
}
