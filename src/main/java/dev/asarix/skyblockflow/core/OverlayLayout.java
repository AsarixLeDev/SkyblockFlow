package dev.asarix.skyblockflow.core;

/** Shared screen geometry for the HUD and its editor. Positions use available screen space. */
public final class OverlayLayout {
  private OverlayLayout() {}
  public record Rect(double x, double y, double width, double height) {
    public boolean contains(double px, double py) {
      return px >= x && py >= y && px < x + width && py < y + height;
    }
  }
  public record Placement(double x, double y, float scale) {}

  public static float fittedScale(int sw, int sh, double w, double h, float scale) {
    return (float) Math.max(.01, Math.min(scale, Math.min(Math.max(1, sw - 8) / w, Math.max(1, sh - 8) / h)));
  }
  public static Rect bounds(int sw, int sh, double w, double h, double x, double y, float scale) {
    float fit = fittedScale(sw, sh, w, h, scale);
    return new Rect(Math.max(0, sw - w * fit) * Math.clamp(x, 0, 1),
        Math.max(0, sh - h * fit) * Math.clamp(y, 0, 1), w * fit, h * fit);
  }
  private static Placement placement(double x, double y, double w, double h, float scale, int sw, int sh) {
    return new Placement(Math.clamp(x / Math.max(1, sw - w), 0, 1),
        Math.clamp(y / Math.max(1, sh - h), 0, 1), Math.clamp(scale, .5f, 3f));
  }
  public static Placement move(Rect start, double dx, double dy, float scale, int sw, int sh) {
    return placement(start.x + dx, start.y + dy, start.width, start.height, scale, sw, sh);
  }
  /** Corners run clockwise from top left. The opposite corner stays fixed. */
  public static Placement resize(Rect start, float effectiveScale, int corner, double dx, double dy, int sw, int sh) {
    int sx = corner == 0 || corner == 3 ? -1 : 1, sy = corner < 2 ? -1 : 1;
    double bw = start.width / effectiveScale, bh = start.height / effectiveScale;
    double ax = sx < 0 ? start.x + start.width : start.x, ay = sy < 0 ? start.y + start.height : start.y;
    double factor = (start.width * (start.width + sx * dx) + start.height * (start.height + sy * dy))
        / (start.width * start.width + start.height * start.height);
    double max = Math.min(fittedScale(sw, sh, bw, bh, 3),
        Math.min((sx < 0 ? ax : sw - ax) / bw, (sy < 0 ? ay : sh - ay) / bh));
    float scale = (float) Math.clamp(effectiveScale * factor, Math.min(.5, max), max);
    double w = bw * scale, h = bh * scale;
    return placement(sx < 0 ? ax - w : ax, sy < 0 ? ay - h : ay, w, h, scale, sw, sh);
  }
}
