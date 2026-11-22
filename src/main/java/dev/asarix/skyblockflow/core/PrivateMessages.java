package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;

/** Shares the private-message alert's matching rules, independently of alarm playback. */
public final class PrivateMessages {
  private record Rule(String pattern, MatchMode mode, boolean sensitive) {}
  private List<Rule> previous = List.of();
  private List<AlertPattern> patterns = List.of();

  public boolean matches(String text, List<AlertRule> alerts) {
    var rules = alerts.stream().filter(r -> "private-message".equals(r.id)
        || "mp".equalsIgnoreCase(r.name.strip()) || "Message privé".equalsIgnoreCase(r.name.strip()))
        .map(r -> new Rule(r.pattern, r.mode, r.caseSensitive)).toList();
    if (rules.isEmpty()) rules = List.of(new Rule("[[player] -> me] [msg]", MatchMode.TEMPLATE, false));
    if (!rules.equals(previous)) {
      previous = rules;
      var compiled = new ArrayList<AlertPattern>();
      for (var rule : rules) {
        add(compiled, rule);
        if (rule.mode == MatchMode.TEMPLATE && rule.pattern.contains("[player] -> me"))
          add(compiled, new Rule(rule.pattern.replace("[player] -> me", "me -> [player]"), rule.mode, rule.sensitive));
      }
      patterns = List.copyOf(compiled);
    }
    for (var pattern : patterns)
      try { if (pattern.match(text, "") != null) return true; }
      catch (IllegalArgumentException ignored) { /* Invalid user patterns do not interrupt chat. */ }
    return false;
  }
  private static void add(List<AlertPattern> result, Rule value) {
    var rule = new AlertRule(); rule.pattern = value.pattern; rule.mode = value.mode; rule.caseSensitive = value.sensitive;
    try { result.add(new AlertPattern(rule)); } catch (IllegalArgumentException ignored) {}
  }
}
