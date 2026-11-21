package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CelebrationEditSettings;
import java.util.*;

/** Portable annotation file. All hit times refer to the original MP3, before pitch/sync changes. */
public final class HitTimeline {
  public int schemaVersion = 1;
  public String audioSha256, songName;
  public long durationMs;
  public String timebase = "original-audio-milliseconds";
  public int detectorVersion = SongAnalysisCache.VERSION;
  public CelebrationEditSettings detectorSettings;
  public float analysisPitch = 1;
  public long editedAtEpochMs;
  public boolean fullyReviewed = false;
  public String annotationScope =
      "Manual edits only; unedited regions are not verified unless fullyReviewed is true.";
  public List<SongAnalysis.Hit> detectedHits = new ArrayList<>();
  public List<SongAnalysis.Hit> hits = new ArrayList<>();
  public List<Long> addedHitTimesMs = new ArrayList<>(), removedHitTimesMs = new ArrayList<>();

  public HitTimeline copy() {
    return ConfigStore.copy(this, HitTimeline.class);
  }

  public void updateEdits() {
    editedAtEpochMs = System.currentTimeMillis();
    Set<Long> before = new HashSet<>(), after = new HashSet<>();
    for (var h : detectedHits) before.add(h.timeMs());
    for (var h : hits) after.add(h.timeMs());
    addedHitTimesMs = after.stream().filter(t -> !before.contains(t)).sorted().toList();
    removedHitTimesMs = before.stream().filter(t -> !after.contains(t)).sorted().toList();
  }
}
