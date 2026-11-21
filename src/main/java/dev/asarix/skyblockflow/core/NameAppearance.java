package dev.asarix.skyblockflow.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** A publisher-signed palette. Remote settings cannot introduce fonts, URLs or actions. */
public record NameAppearance(long revision, Look defaultLook, Map<UUID, Look> overrides) {
  public static final Map<String, Integer> COLORS = Map.of(
      "mint", 0x80DCC7, "aqua", 0x75DAEE, "blue", 0x94BFFF,
      "gold", 0xF3D879, "white", 0xEEEEEE, "lavender", 0xC4B5FD);
  public static final Set<String> FONTS = Set.of("server", "default", "uniform", "smooth");
  public record Look(List<String> colors, String font) {
    public Look { colors = List.copyOf(colors); }
    public Look(String color, String font) { this(List.of(color), font); }
    public static Look server() { return new Look("server", "server"); }
    public boolean keepsColor() { return colors.equals(List.of("server")); }
    public boolean keepsFont() { return font.equals("server"); }
    public int colorAt(double progress) {
      if (keepsColor()) throw new IllegalStateException("The server supplies this color");
      double stop = Math.clamp(progress, 0, 1) * (colors.size() - 1);
      int index = (int) stop, left = colorValue(colors.get(index));
      int right = colorValue(colors.get(Math.min(index + 1, colors.size() - 1)));
      double t = stop - index;
      int color = 0;
      for (int shift : new int[]{16, 8, 0})
        color |= (int)Math.round(((left >> shift) & 255) * (1 - t) + ((right >> shift) & 255) * t) << shift;
      return color;
    }
  }
  public Look forPlayer(UUID id) { return overrides.getOrDefault(id, defaultLook); }

  public static NameAppearance verify(String envelope, PublicKey key, long minimumRevision) throws Exception {
    if (envelope.length() > 262144) throw new IllegalArgumentException("Appearance settings too large");
    var outer = JsonParser.parseString(envelope).getAsJsonObject();
    exact(outer, "payload", "signature");
    byte[] payload = Base64.getDecoder().decode(outer.get("payload").getAsString());
    byte[] signature = Base64.getDecoder().decode(outer.get("signature").getAsString());
    if (payload.length > 131072 || signature.length != 64) throw new IllegalArgumentException("Invalid appearance signature");
    var verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key); verifier.update(payload);
    if (!verifier.verify(signature)) throw new SignatureException("Appearance signature did not match");
    var doc = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
    exact(doc, "schema", "revision", "default", "overrides");
    long revision = doc.get("revision").getAsBigDecimal().longValueExact();
    if (doc.get("schema").getAsInt() != 1 || revision < 1 || revision < minimumRevision)
      throw new IllegalArgumentException("Unsupported or older appearance settings");
    var entries = doc.getAsJsonObject("overrides");
    if (entries.size() > 1000) throw new IllegalArgumentException("Too many player styles");
    Map<UUID, Look> overrides = new HashMap<>();
    for (var entry : entries.entrySet()) {
      UUID id = UUID.fromString(entry.getKey());
      if (!id.toString().equals(entry.getKey())) throw new IllegalArgumentException("Invalid player UUID");
      overrides.put(id, look(entry.getValue().getAsJsonObject()));
    }
    return new NameAppearance(revision, look(doc.getAsJsonObject("default")), Map.copyOf(overrides));
  }

  public static PublicKey publisherKey() throws Exception {
    try (var in = NameAppearance.class.getResourceAsStream("/appearance-public.der")) {
      if (in == null) throw new IllegalStateException("Missing appearance public key");
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(in.readAllBytes()));
    }
  }

  private static Look look(JsonObject doc) {
    exact(doc, "color", "font");
    var colors = new ArrayList<String>(); var field = doc.get("color");
    if (field.isJsonArray()) for (var color : field.getAsJsonArray()) colors.add(color.getAsString());
    else colors.add(field.getAsString());
    var font = doc.get("font").getAsString();
    if (colors.isEmpty() || colors.size() > 4 || (field.isJsonArray() && colors.size() < 2) || !((field.isJsonPrimitive() && field.getAsString().equals("server")) || validGradient(colors)) || !FONTS.contains(font))
      throw new IllegalArgumentException("Unsupported color or font");
    return new Look(colors, font);
  }

  public static int colorValue(String value) {
    var named = COLORS.get(value);
    if (named != null) return named;
    if (value.matches("#[0-9A-Fa-f]{6}")) return Integer.parseInt(value.substring(1), 16);
    throw new IllegalArgumentException("Invalid color");
  }
  public static boolean allowedColor(int rgb) {
    int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
    int high = Math.max(r, Math.max(g, b)), low = Math.min(r, Math.min(g, b)), delta = high - low;
    return high >= 64 && !(r == high && (double)delta / high >= .35 && Math.abs(g - b) * 3 < delta);
  }
  private static int mix(int a, int b, double t) {
    int result = 0;
    for (int shift : new int[]{16, 8, 0})
      result |= (int)Math.round(((a >> shift) & 255) * (1-t) + ((b >> shift) & 255) * t) << shift;
    return result;
  }
  public static boolean validGradient(List<String> colors) {
    try {
      if (colors.isEmpty() || colors.size() > 4) return false;
      int[] values = colors.stream().mapToInt(NameAppearance::colorValue).toArray();
      for (int value : values) if (!allowedColor(value)) return false;
      for (int i = 1; i < values.length; i++) {
        int a = values[i-1], b = values[i];
        var points = new TreeSet<Double>(List.of(0., 1.));
        for (int shift : new int[]{16, 8, 0}) {
          int left = a >> shift & 255, right = b >> shift & 255;
          for (int n = Math.min(left, right); n < Math.max(left, right); n++) points.add((n + .5 - left) / (right - left));
        }
        double previous = 0;
        for (double t : points) {
          if (!allowedColor(mix(a, b, t)) || !allowedColor(mix(a, b, (previous+t)/2))) return false;
          previous = t;
        }
      }
      return true;
    } catch (IllegalArgumentException e) { return false; }
  }

  private static void exact(JsonObject doc, String... keys) {
    if (!doc.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("Unexpected appearance fields");
  }
}
