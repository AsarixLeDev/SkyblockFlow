package dev.asarix.skyblockflow.core;

/** Provisional differences of a server action-bar burst counter; boss bars stay authoritative. */
public final class ActionBarXp {
  private String job = "";
  private final ActionXpCalibration calibration = new ActionXpCalibration();
  private double previous = Double.NaN, pending, confirmedAhead;
  private long sampledAt, contextAt;
  public String job() { return job; }
  public void petMultiplier(double value) {
    if (calibration.petMultiplier(value)) { previous = Double.NaN; pending = confirmedAhead = 0; }
  }
  public double multiplier() { return calibration.factor(); }
  public void tick(long now) {
    if (calibration.tick(now)) {
      // Start a fresh prediction at the learned scale; old overlap is in different units.
      previous = Double.NaN; pending = confirmedAhead = 0;
    }
  }
  public void reset() { calibration.reset(); job = ""; previous = Double.NaN; pending = confirmedAhead = 0; sampledAt = contextAt = 0; }
  public void authoritative(String id, double gain, long now) {
    if (!id.equals(job)) { reset(); job = id; gain = 0; }
    if (!Double.isFinite(gain) || gain < 0) {
      calibration.restartWindow(); previous = Double.NaN; pending = confirmedAhead = 0; gain = 0;
    }
    double confirmed = Math.max(0, gain);
    if (Double.isFinite(previous)) calibration.confirmed(confirmed, now);
    double overlap = Math.min(pending, confirmed);
    // A full server reading replaces every earlier prediction, including an overestimate.
    // Subtracting only its gain used to leave excess XP accumulating across confirmations.
    pending = 0;
    // A boss update can arrive before the corresponding action-bar counter.
    if (now - sampledAt < 3000 && Double.isFinite(previous))
      confirmedAhead += confirmed - overlap;
    contextAt = now;
  }
  public boolean sample(String raw, long now) {
    double value = JobMessages.actionGain(raw);
    if (!Double.isFinite(value)) { previous = Double.NaN; calibration.restartWindow(); return false; }
    if (job.isEmpty() || now - contextAt > 10000) return false;
    if (now - sampledAt >= 5000) pending = 0;
    if (!Double.isFinite(previous) || now - sampledAt >= 3000 || value < previous) {
      confirmedAhead = 0; calibration.restartWindow();
    }
    double delta = Double.isFinite(previous) && now - sampledAt < 3000 && value > previous ? value - previous : 0;
    previous = value; sampledAt = now;
    calibration.raw(delta, now);
    delta *= calibration.factor();
    double alreadyConfirmed = Math.min(delta, confirmedAhead);
    confirmedAhead -= alreadyConfirmed;
    pending += delta - alreadyConfirmed;
    return delta > 0;
  }
  public double pending(String id, long now) { return id.equals(job) && now - sampledAt < 5000 ? pending : 0; }
  public long sampledAt() { return sampledAt; }
}
