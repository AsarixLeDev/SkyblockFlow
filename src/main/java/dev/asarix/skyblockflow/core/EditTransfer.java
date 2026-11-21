package dev.asarix.skyblockflow.core;

/** An immutable worker-to-UI snapshot. Bytes reflect the actual HTTP body, not elapsed time. */
public record EditTransfer(String phase, long bytes, long total, boolean dataTransfer) {
  public EditTransfer(String phase, long bytes, long total) {
    this(phase, bytes, total, true);
  }

  public static EditTransfer work(String phase, long done, long total) {
    return new EditTransfer(phase, done, total, false);
  }

  public static EditTransfer preparing(String phase) {
    return new EditTransfer(phase, 0, 0);
  }

  public double fraction() {
    return total <= 0 ? -1 : Math.clamp(bytes / (double) total, 0, 1);
  }

  public String detail() {
    if (total <= 0) return "Please wait…";
    if (!dataTransfer) return Math.round(fraction() * 100) + "%";
    return String.format(
        java.util.Locale.ROOT,
        "%d%% · %.1f / %.1f MB",
        Math.round(fraction() * 100),
        bytes / 1048576d,
        total / 1048576d);
  }
}
