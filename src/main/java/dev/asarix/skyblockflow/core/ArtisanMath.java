package dev.asarix.skyblockflow.core;

import java.util.*;

public final class ArtisanMath {
  public static final int[] TIERS = {0, 10, 20, 40, 60, 80, 100, 120, 140, 160, 180, 200};
  public static final int[] XP = {
    16, 82, 960, 3400, 7600, 13800, 22000, 36300, 49500, 72100, 90500, 122300
  };

  public static List<String> rows(JobTracker.Job job, int count) {
    if (job == null) return List.of("Earn Artisan XP to detect your level.");
    if (job.level >= 200) return List.of("Level 200 · maximum reached");
    int milestone = Math.min(200, (job.level / 20 + 1) * 20);
    var rows = new ArrayList<String>();
    rows.add("Artisan · Lv. " + job.level + " → " + (job.level + 1) + " / " + milestone);
    for (int i = TIERS.length - 1; i >= 0 && rows.size() <= count; i--)
      if (TIERS[i] <= job.level) {
        long next = (long) Math.ceil(job.remaining(job.level + 1) / XP[i]),
            goal = (long) Math.ceil(job.remaining(milestone) / XP[i]);
        rows.add("Lv. " + TIERS[i] + " tools: " + next + " / " + goal);
      }
    rows.add("Next level / next 20-level milestone");
    return rows;
  }
}
