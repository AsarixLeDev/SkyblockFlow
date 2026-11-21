package dev.asarix.skyblockflow.core;

public final class Geometry {
  private Geometry() {}

  public static double angle(double value) {
    return ((value + 180) % 360 + 360) % 360 - 180;
  }

  public static boolean crosses(double[] a, double[] b, double[] min, double[] max) {
    double enter = 0, leave = 1;
    for (int axis = 0; axis < 3; axis++) {
      double delta = b[axis] - a[axis];
      if (Math.abs(delta) < 1e-12) {
        if (a[axis] < min[axis] || a[axis] > max[axis]) return false;
      } else {
        double first = (min[axis] - a[axis]) / delta, last = (max[axis] - a[axis]) / delta;
        enter = Math.max(enter, Math.min(first, last));
        leave = Math.min(leave, Math.max(first, last));
        if (enter > leave) return false;
      }
    }
    return true;
  }
}
