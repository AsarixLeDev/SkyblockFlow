package dev.asarix.skyblockflow.core;

import java.util.*;
import java.util.regex.*;

/** Protocol text observed in Excalia's job sidebar/chat and its resource-pack XP glyph. */
public final class JobMessages {
  public record Rank(int rank, int level, String name) {}
  public record LevelUp(String job, int level) {}
  private static final Pattern RANK = Pattern.compile("^\\s*(\\d{1,5})[.)]\\s*\\[?(\\d{1,3})]?\\s*([A-Za-z0-9_]{1,16})\\s*$");
  private static final Pattern PAGE = Pattern.compile("^[^a-z0-9]*prev\\s+(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s+next[^a-z0-9]*$");
  private static final Pattern MAX_LEVEL = Pattern.compile("^(mineur|bucheron|fermier|chasseur|artisan|aventurier|pecheur)\\b[^a-z0-9]*niveau\\s+max\\s+200[\\s\\p{P}\\p{S}\\p{Co}\\p{Cf}]*$");
  private static final Pattern ACTION_GAIN = Pattern.compile("\\+\\s*([0-9]+(?:[.,][0-9]+)?)\\s*\uf747");
  private static final Pattern LEVEL = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*)*[^a-z0-9]*tu\\s+passes\\s+(mineur|bucheron|fermier|chasseur|artisan|aventurier|pecheur)\\s+niveau\\s+(\\d{1,3})\\s*!");
  public static Rank rank(String raw) {
    var m = RANK.matcher(raw.replaceAll("§[0-9a-fk-orxA-FK-ORX]", "").replaceAll("[\\p{Cf}\\p{Co}]", "").replace('\u00a0', ' '));
    if (!m.matches()) return null;
    int rank = Integer.parseInt(m.group(1)), level = Integer.parseInt(m.group(2));
    return rank > 0 && level > 0 && level <= 200 ? new Rank(rank, level, m.group(3)) : null;
  }
  public static int[] page(String raw) {
    var m = PAGE.matcher(JobXp.normalize(raw));
    if (!m.matches()) return null;
    int page = Integer.parseInt(m.group(1)), total = Integer.parseInt(m.group(2));
    return page > 0 && total >= page ? new int[]{page, total} : null;
  }
  public static double actionGain(String raw) {
    if (raw == null || raw.length() > 4096) return Double.NaN;
    var m = ACTION_GAIN.matcher(raw.replaceAll("§[0-9a-fk-orxA-FK-ORX]", ""));
    if (!m.find()) return Double.NaN;
    double gain = Double.parseDouble(m.group(1).replace(',', '.'));
    return !m.find() && gain > 0 && gain < 1e12 ? gain : Double.NaN;
  }
  public static LevelUp levelUp(String raw) {
    if (raw == null || raw.length() > 4096) return null;
    // Decode the whole-word job glyph first, then ignore resource-pack icons and spacing.
    // Excalia includes an extra job icon between "tu passes" and the job-name glyph.
    String text = JobXp.normalize(raw).replaceAll("[\\p{Co}\\p{Cf}]", " ");
    var m = LEVEL.matcher(text);
    if (!m.find()) return null;
    int level = Integer.parseInt(m.group(2));
    return level > 1 && level <= 200 ? new LevelUp(m.group(1), level) : null;
  }
  public static boolean statsHeader(String plain, String target) {
    // Underscores belong to player names, including at either end of the header.
    return !target.isEmpty() && plain.matches("(?i)^[^\\p{L}\\p{N}_]*" + Pattern.quote(target) + "[^\\p{L}\\p{N}_]*$");
  }
  public static JobXp.Reading stats(String raw) {
    if (raw == null || raw.length() > 4096) return null;
    String text = JobXp.normalize(raw).replaceFirst("^[\\s\\p{P}\\p{S}\\p{Co}\\p{Cf}]+", "");
    var maximum = MAX_LEVEL.matcher(text);
    if (maximum.matches()) return new JobXp.Reading(maximum.group(1), 200, 0, 0, 0);
    if (!text.matches("^(?:mineur|bucheron|fermier|chasseur|artisan|aventurier|pecheur)\\b[^a-z0-9]*niveau\\b.*")) return null;
    return JobXp.parse(text);
  }

  public static boolean statsLine(String raw) {
    if (raw == null || raw.length() > 4096) return false;
    String text = JobXp.normalize(raw).replaceFirst("^[\\s\\p{P}\\p{S}\\p{Co}\\p{Cf}]+", "");
    return text.matches("^(?:mineur|bucheron|fermier|chasseur|artisan|aventurier|pecheur)\\b[^a-z0-9]*niveau\\b.*");
  }

  public static double gap(JobTracker.Job self, JobXp.Reading other) {
    return gap(self.id, self.level, self.xp, self.required, other);
  }
  public static double gap(String job, int level, double xp, double required, JobXp.Reading other) {
    if (other.level() == level) return other.xp() - xp;
    if (other.level() > level)
      return JobXp.remaining(job, level, xp, required, other.level()) + other.xp();
    return -(JobXp.remaining(other.job(), other.level(), other.xp(), other.required(), level) + xp);
  }
}
