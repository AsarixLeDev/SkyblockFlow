package dev.asarix.skyblockflow.core;

import java.util.*;

/** One independent session per job. Duplicate boss titles never extend a session. */
public final class JobTracker {
  public static class Gain {
    public long at; public double xp;
    public Gain(long at, double xp) { this.at = at; this.xp = xp; }
  }

  public static class Session {
    public List<Gain> gains = new ArrayList<>();
    public long started, lastGain, ended;
    public double xp;

    public void gain(double amount, long now) {
      if (gains == null) gains = new ArrayList<>();
      gains.removeIf(g -> g.at < now - 3600000);
      if (!gains.isEmpty() && gains.getLast().at > started && gains.getLast().at / 1000 == now / 1000) {
        gains.getLast().xp += amount; gains.getLast().at = now;
      } else gains.add(new Gain(now, amount));
    }

    public double rate(long ignoredNow, long windowMs) {
      // Zero means the entire session. Use its total, even after old gain samples are pruned.
      // As with rolling pace, anchor the estimate to the latest gain instead of idle ticks.
      if (windowMs == 0) {
        long elapsed = Math.max(0, lastGain - started);
        return elapsed < 1000 ? 0 : xp * 60000 / elapsed;
      }
      long span = Math.min(Math.max(0, lastGain - started), windowMs);
      if (span < 1000) return 0;
      if (gains == null || gains.isEmpty()) return xp * 60000 / Math.max(1000, lastGain - started);
      double amount = 0;
      long cutoff = Math.max(started, lastGain - windowMs);
      for (Gain gain : gains) if (gain.at > cutoff) amount += gain.xp;
      return amount * 60000 / span;
    }

    public long elapsed(long now) {
      return Math.max(0, (ended > 0 ? ended : now) - started);
    }

    public double rate(long now) {
      long ms = elapsed(now);
      return ms < 1000 ? 0 : xp * 60000 / ms;
    }
  }

  public static class Job {
    public String id = "";
    public int level;
    public double xp, required;
    public long updated, sessions;
    public Session active, last;

    public Session displayed() {
      return active != null ? active : last;
    }

    public double remaining(int target) {
      return JobXp.remaining(id, level, xp, required, target);
    }

    public boolean curveMismatch() {
      long expected = JobXp.requirement(id, level);
      return level < 200 && expected >= 0 && Math.abs(expected - required) > 1;
    }

    /** Current-level costs come from the server; only later transitions need the inferred curve. */
    public boolean inferredProjection(int target) {
      return JobXp.inferredCoefficient(id) > 0 && Math.min(target, 200) > Math.max(level + 1, 151);
    }
  }

  public static class Data {
    public int version = 1;
    public Map<String, Job> jobs = new LinkedHashMap<>();
  }

  public final Data data;
  private final Set<String> observed = new HashSet<>();
  private String latestGainingJob = "";

  public JobTracker() {
    this(new Data());
  }

  public JobTracker(Data data) {
    this.data = data;
  }

  public void newConnection() {
    observed.clear();
  }

  public boolean resetSession(String id, long now) {
    Job job = data.jobs.get(id);
    if (job == null) return false;
    if (job.active == null) job.sessions++;
    job.active = new Session();
    job.active.started = now;
    job.active.lastGain = now;
    // Keep the level/XP baseline so repeated HUD messages cannot restore discarded XP.
    return true;
  }

  public boolean observe(JobXp.Reading reading, long now, long timeoutMs) {
    boolean changed = expire(now, timeoutMs);
    Job job = data.jobs.get(reading.job());
    boolean exists = job != null;
    if (!exists) {
      job = new Job();
      job.id = reading.job();
      data.jobs.put(job.id, job);
    }
    boolean hadBaseline = observed.contains(job.id);
    observed.add(job.id);
    if (exists && job.level == reading.level() && Math.abs(job.xp - reading.xp()) < .000001) {
      if (job.required != reading.required()) {
        job.required = reading.required();
        changed = true;
      }
      return changed;
    }
    double gain = reading.gain();
    boolean backwards =
        exists
            && (reading.level() < job.level
                || reading.level() == job.level && reading.xp() < job.xp);
    if (backwards) {
      finish(job, now);
      gain = 0;
    } else if (exists && hadBaseline) {
      if (reading.level() == job.level) gain = reading.xp() - job.xp;
      else if (reading.level() == job.level + 1 || gain <= 0) {
        // One rollover uses the observed cost. For larger jumps prefer the server's
        // explicit gain, since unseen level costs can differ from the published curve.
        double delta = job.remaining(reading.level());
        if (Double.isFinite(delta)) gain = delta + reading.xp();
      }
    }
    job.level = reading.level();
    job.xp = reading.xp();
    job.required = reading.required();
    job.updated = now;
    if (gain > 0 && Double.isFinite(gain)) {
      if (job.active == null) {
        job.active = new Session();
        job.active.started = now;
        job.sessions++;
      }
      job.active.xp += gain;
      job.active.gain(gain, now);
      job.active.lastGain = now;
      latestGainingJob = job.id;
    }
    return true;
  }

  public boolean expire(long now, long timeoutMs) {
    boolean changed = false;
    for (Job job : data.jobs.values()) {
      Session s = job.active;
      if (s != null && now >= s.lastGain + timeoutMs) {
        finish(job, s.lastGain + timeoutMs);
        changed = true;
      } else if (s != null && now < s.started) {
        // A wall-clock correction must not produce a negative or enormous rate.
        finish(job, s.lastGain);
        changed = true;
      }
    }
    return changed;
  }

  public void finishAll(long now) {
    for (Job job : data.jobs.values()) finish(job, now);
    observed.clear();
  }

  private static void finish(Job job, long now) {
    if (job.active == null) return;
    if (job.active.xp > 0) {
      job.active.ended = Math.max(job.active.started + 1000, now);
      job.last = job.active;
    }
    job.active = null;
  }

  public Job selected(String pin) {
    if (!pin.isBlank()) return data.jobs.get(pin);
    var active = activeJob();
    if (active != null) return active;
    return data.jobs.values().stream().max(Comparator.comparingLong(
        j -> j.last == null ? j.updated : j.last.lastGain)).orElse(null);
  }

  public long activeSessions() {
    return data.jobs.values().stream().filter(j -> j.active != null).count();
  }

  public Job activeJob() {
    return data.jobs.values().stream().filter(j -> j.active != null)
        .max(Comparator.<Job>comparingLong(j -> j.active.lastGain)
            .thenComparing(j -> j.id.equals(latestGainingJob))).orElse(null);
  }
}
