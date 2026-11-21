package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public-client OAuth: no application secret and no remote callback service. */
public final class DiscordOAuth {
  public static final Set<String> READ_SCOPES = Set.of("identify", "rpc", "rpc.voice.read");
  public static final String CONTROL_SCOPE = "rpc.voice.write";
  public static final URI AUTHORIZED_APPS = URI.create("https://discord.com/settings/authorized-apps");
  private static final URI AUTHORIZE = URI.create("https://discord.com/oauth2/authorize");
  private static final URI TOKEN = URI.create("https://discord.com/api/oauth2/token");
  private static final URI REVOKE = URI.create("https://discord.com/api/oauth2/token/revoke");
  private final URI tokenEndpoint, revokeEndpoint;
  private final HttpClient http;
  private final Clock clock;

  public record App(String clientId, URI redirect) {
    public App {
      if (!clientId.isEmpty() && !clientId.matches("[0-9]{10,25}"))
        throw new IllegalArgumentException("Invalid Discord application ID in this build");
      if (!"http".equals(redirect.getScheme()) || !"127.0.0.1".equals(redirect.getHost())
          || redirect.getPort() < 1024 || redirect.getPort() > 65535
          || !"/callback".equals(redirect.getPath()) || redirect.getRawQuery() != null
          || redirect.getRawFragment() != null || redirect.getRawUserInfo() != null)
        throw new IllegalArgumentException("Discord callback must use a local loopback address");
    }

    public boolean configured() {
      return !clientId.isEmpty();
    }
  }

  /** Deliberately redacts toString, including when used by failed assertions or logging. */
  public static final class Tokens {
    public final String access, refresh;
    public final long expiresAt;
    public final Set<String> scopes;

    public Tokens(String access, String refresh, long expiresAt, Set<String> scopes) {
      if (access == null || access.isBlank() || access.length() > 8192
          || refresh == null || refresh.isBlank() || refresh.length() > 8192
          || expiresAt <= 0 || !scopes.containsAll(READ_SCOPES))
        throw new IllegalArgumentException("Incomplete Discord authorization");
      this.access = access;
      this.refresh = refresh;
      this.expiresAt = expiresAt;
      this.scopes = Set.copyOf(scopes);
    }

    public boolean controls() { return scopes.contains(CONTROL_SCOPE); }
    @Override public String toString() { return "DiscordTokens[redacted]"; }
  }

  public static final class Failure extends IOException {
    public final boolean permanent;
    public Failure(String safeMessage, boolean permanent) {
      super(safeMessage);
      this.permanent = permanent;
    }
  }

  public DiscordOAuth() {
    this(TOKEN, REVOKE, Clock.systemUTC());
  }

  DiscordOAuth(URI tokenEndpoint, URI revokeEndpoint, Clock clock) {
    this.tokenEndpoint = tokenEndpoint;
    this.revokeEndpoint = revokeEndpoint;
    this.clock = clock;
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public Login login(App app, boolean controls) throws IOException {
    if (!app.configured()) throw new Failure("Discord connection is not available in this build yet.", true);
    return new Login(app, controls);
  }

  public Tokens exchange(App app, Login login, String code) throws IOException, InterruptedException {
    var body = new LinkedHashMap<String, String>();
    body.put("client_id", app.clientId());
    body.put("grant_type", "authorization_code");
    body.put("code", code);
    body.put("redirect_uri", app.redirect().toString());
    body.put("code_verifier", login.verifier);
    Tokens result = parseTokens(post(tokenEndpoint, body), null);
    return limitControls(result, login.controls);
  }

  public Tokens refresh(App app, Tokens previous) throws IOException, InterruptedException {
    return limitControls(parseTokens(post(tokenEndpoint, Map.of("client_id", app.clientId(),
        "grant_type", "refresh_token", "refresh_token", previous.refresh)), previous), previous.controls());
  }

  private static Tokens limitControls(Tokens tokens, boolean optedIn) {
    if (optedIn || !tokens.controls()) return tokens;
    var scopes = new HashSet<>(tokens.scopes);
    scopes.remove(CONTROL_SCOPE);
    return new Tokens(tokens.access, tokens.refresh, tokens.expiresAt, scopes);
  }

  public void revoke(App app, Tokens tokens) throws IOException, InterruptedException {
    post(revokeEndpoint, Map.of("client_id", app.clientId(), "token", tokens.refresh,
        "token_type_hint", "refresh_token"));
  }

  private Tokens parseTokens(JsonObject data, Tokens previous) throws Failure {
    try {
      if (!"Bearer".equalsIgnoreCase(data.get("token_type").getAsString())) throw new IllegalArgumentException();
      String access = data.get("access_token").getAsString();
      String refresh = data.has("refresh_token") ? data.get("refresh_token").getAsString()
          : previous == null ? "" : previous.refresh;
      long seconds = data.get("expires_in").getAsLong();
      if (seconds < 1 || seconds > 31_536_000) throw new IllegalArgumentException();
      Set<String> scopes = data.has("scope")
          ? new HashSet<>(Arrays.asList(data.get("scope").getAsString().split(" +")))
          : previous == null ? Set.of() : previous.scopes;
      return new Tokens(access, refresh, clock.millis() + seconds * 1000, scopes);
    } catch (RuntimeException e) {
      throw new Failure("Discord returned an incomplete authorization. Please connect again.", true);
    }
  }

  private JsonObject post(URI uri, Map<String, String> fields) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
    var response = http.send(request, info -> new LimitedBody());
    byte[] bytes = response.body();
    JsonObject data = new JsonObject();
    try {
      if (bytes.length > 0) data = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (RuntimeException ignored) { }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String code = data.has("error") && data.get("error").isJsonPrimitive() ? data.get("error").getAsString() : "";
      if (code.equals("invalid_grant"))
        throw new Failure("Your Discord connection expired or was removed. Connect again.", true);
      if (code.equals("invalid_client") || code.equals("unauthorized_client"))
        throw new Failure("Discord connection is not available for this release yet. Please contact the mod author.", true);
      if (code.equals("invalid_scope"))
        throw new Failure("Discord has not enabled the required permissions for this release yet.", true);
      throw new Failure("Discord could not complete the request. Try again later.", response.statusCode() >= 400 && response.statusCode() < 500 && response.statusCode() != 429);
    }
    return data;
  }

  /** Bounds both response size and time: HttpRequest timeout covers the complete subscriber. */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long size;
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
    public void onNext(List<java.nio.ByteBuffer> items) {
      for (var item : items) size += item.remaining();
      if (size > 65536) {
        subscription.cancel();
        delegate.onError(new IOException("Discord response too large"));
      } else delegate.onNext(items);
    }
    public void onError(Throwable error) { delegate.onError(error); }
    public void onComplete() { delegate.onComplete(); }
  }

  static String form(Map<String, String> fields) {
    return fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  static String random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String challenge(String verifier) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }

  public static final class Login implements AutoCloseable {
    private final String verifier = random(), state = random();
    private final boolean controls;
    private final App app;
    private final HttpServer server;
    private final ExecutorService executor;
    private final CompletableFuture<String> code = new CompletableFuture<>();
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final URI authorization;

    Login(App app, boolean controls) throws IOException {
      this.app = app;
      this.controls = controls;
      var scopes = new TreeSet<>(READ_SCOPES);
      if (controls) scopes.add(CONTROL_SCOPE);
      authorization = URI.create(AUTHORIZE + "?" + form(Map.of(
          "client_id", app.clientId(), "response_type", "code", "redirect_uri", app.redirect().toString(),
          "scope", String.join(" ", scopes), "state", state, "code_challenge", challenge(verifier),
          "code_challenge_method", "S256", "prompt", "consent")));
      try {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), app.redirect().getPort()), 4);
      } catch (BindException busy) {
        throw new Failure("Another sign-in is using the local callback. Close it and try again.", false);
      }
      executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("Discord callback-", 0).factory());
      server.setExecutor(executor);
      server.createContext("/", this::receive);
      server.start();
    }

    public URI authorization() { return authorization; }

    public String await(Duration timeout) throws IOException, InterruptedException {
      try { return code.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
      catch (TimeoutException e) { throw new Failure("Discord sign-in timed out. Click Connect Discord to try again.", false); }
      catch (CancellationException e) { throw new Failure("Discord sign-in cancelled.", false); }
      catch (ExecutionException e) {
        if (e.getCause() instanceof IOException io) throw io;
        throw new Failure("Discord sign-in could not finish. Please try again.", false);
      }
    }

    private void receive(HttpExchange exchange) throws IOException {
      String value = null;
      Failure error = null;
      int status = 400;
      String message = "This sign-in request is invalid. Return to Minecraft and try again.";
      try {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
            || !"GET".equals(exchange.getRequestMethod())
            || !app.redirect().getRawAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))
            || !app.redirect().getRawPath().equals(exchange.getRequestURI().getRawPath()))
          throw new IllegalArgumentException();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.length() > 16384) throw new IllegalArgumentException();
        var fields = new HashMap<String, String>();
        for (String pair : query.split("&")) {
          String[] parts = pair.split("=", 2);
          if (parts.length != 2 || fields.putIfAbsent(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
              URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException();
        }
        if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
            fields.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))
            || !"https://discord.com".equals(fields.get("iss"))) throw new IllegalArgumentException();
        if (fields.containsKey("error") && fields.containsKey("code")) throw new IllegalArgumentException();
        if (fields.containsKey("error")) {
          error = new Failure(fields.get("error").equals("access_denied")
              ? "Discord sign-in was declined. You can connect whenever you are ready."
              : "Discord could not authorize this release. Please contact the mod author.", true);
          message = "Sign-in was not completed. You can return to Minecraft.";
        } else {
          value = fields.get("code");
          if (value == null || value.isBlank() || value.length() > 4096) throw new IllegalArgumentException();
          message = "Approval received. Return to Minecraft to finish connecting to Discord.";
        }
        if (!claimed.compareAndSet(false, true)) throw new IllegalArgumentException();
        status = 200;
      } catch (IllegalArgumentException ignored) {
        message = "This sign-in request is invalid. Return to Minecraft and try again.";
        value = null;
        error = null;
      }
      byte[] page = ("<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content=\"width=device-width\"><title>Discord connection</title><h1>Discord connection</h1><p>" + message + "</p></html>").getBytes(StandardCharsets.UTF_8);
      var headers = exchange.getResponseHeaders();
      headers.set("Content-Type", "text/html; charset=utf-8");
      headers.set("Cache-Control", "no-store");
      headers.set("Referrer-Policy", "no-referrer");
      headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
      headers.set("X-Content-Type-Options", "nosniff");
      try {
        exchange.sendResponseHeaders(status, page.length);
        try (var output = exchange.getResponseBody()) { output.write(page); }
      } finally {
        exchange.close();
        if (status == 200) {
          if (error != null) code.completeExceptionally(error);
          else code.complete(value);
        }
      }
    }

    @Override public void close() {
      code.cancel(false);
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
