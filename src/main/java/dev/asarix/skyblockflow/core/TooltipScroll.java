package dev.asarix.skyblockflow.core;

/** A tooltip owns the wheel only while it is visible under the same pointer. */
public final class TooltipScroll {
  private Object screen;
  private long key, lastDraw;
  private int anchorX, anchorY, maxX, maxY, viewWidth, viewHeight;
  private double x, y;
  private boolean active;

  public void beginFrame() { active = false; }

  public void show(Object screen, long key, int mouseX, int mouseY,
                   int width, int height, int visibleWidth, int visibleHeight, long now) {
    if (this.screen != screen || this.key != key || now - lastDraw > 250
        || viewWidth != visibleWidth || viewHeight != visibleHeight) x = y = 0;
    this.screen = screen; this.key = key; lastDraw = now;
    anchorX = mouseX; anchorY = mouseY;
    viewWidth = visibleWidth; viewHeight = visibleHeight;
    maxX = Math.max(0, width - visibleWidth); maxY = Math.max(0, height - visibleHeight);
    x = Math.clamp(x, 0, maxX); y = Math.clamp(y, 0, maxY);
    active = maxX > 0 || maxY > 0;
  }

  public boolean scroll(Object screen, double mouseX, double mouseY, double dx, double dy,
                        boolean shift, long now) {
    if (!active || this.screen != screen || now - lastDraw > 250 || now < lastDraw
        || Math.abs(mouseX - anchorX) > 4 || Math.abs(mouseY - anchorY) > 4
        || !Double.isFinite(dx) || !Double.isFinite(dy) || dx == 0 && dy == 0) return false;
    if (shift || dy == 0 || maxY == 0) {
      if (maxX == 0) return false;
      x = Math.clamp(x - (dy != 0 ? dy : dx) * 24, 0, maxX);
    } else {
      y = Math.clamp(y - dy * 24, 0, maxY);
    }
    // Consume the wheel at either end too, so the underlying menu cannot change pages.
    return true;
  }
  public int x() { return (int)Math.round(x); }
  public int y() { return (int)Math.round(y); }
  public int maxX() { return maxX; }
  public int maxY() { return maxY; }
}
