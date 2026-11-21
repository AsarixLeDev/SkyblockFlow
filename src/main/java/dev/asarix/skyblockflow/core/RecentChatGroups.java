package dev.asarix.skyblockflow.core;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Groups equal messages within five minutes of the newest occurrence. */
public final class RecentChatGroups {
  public static final long WINDOW_MS = 300_000;
  private RecentChatGroups() {}
  private record Group<T>(T newest, long time) {}

  public static <T> Map<T, Integer> counts(List<T> messages, List<T> evicted,
      Function<T, ?> key, ToLongFunction<T> time) {
    var result = new IdentityHashMap<T, Integer>();
    var groups = new HashMap<Object, List<Group<T>>>();
    for (T message : messages) {
      var same = groups.computeIfAbsent(key.apply(message), ignored -> new ArrayList<>());
      long at = time.applyAsLong(message);
      Group<T> match = matching(same, at);
      if (match == null) { same.add(new Group<>(message, at)); result.put(message, 1); }
      else { result.put(message, 0); increment(result, match.newest()); }
    }
    for (T message : evicted) {
      var same = groups.get(key.apply(message));
      Group<T> match = same == null ? null : matching(same, time.applyAsLong(message));
      if (match != null) increment(result, match.newest());
    }
    return result;
  }
  private static <T> Group<T> matching(List<Group<T>> groups, long at) {
    for (Group<T> group : groups) {
      long age = group.time() - at;
      if (age >= 0 && age <= WINDOW_MS) return group;
    }
    return null;
  }
  private static <T> void increment(Map<T, Integer> counts, T message) {
    counts.compute(message, (ignored, n) -> n == Integer.MAX_VALUE ? n : n + 1);
  }
}
