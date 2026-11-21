package dev.asarix.skyblockflow.core;

import static dev.asarix.skyblockflow.core.Models.*;

import java.util.*;
import java.util.regex.*;

/** Compiles friendly [variables] or Java regex named groups. */
public final class AlertPattern {
  private static final Pattern TOKEN = Pattern.compile("\\[([A-Za-z][A-Za-z0-9]*|[0-9]+)\\]");
  private final Pattern pattern;

  public AlertPattern(AlertRule rule) {
    if (rule.pattern.isBlank()) throw new IllegalArgumentException("Enter a pattern first");
    if (rule.pattern.length() > 2048) throw new IllegalArgumentException("Pattern is too long");
    String source = rule.pattern;
    if (rule.mode == MatchMode.TEMPLATE) {
      StringBuilder out = new StringBuilder("^");
      Matcher tokens = TOKEN.matcher(source);
      Set<String> names = new HashSet<>();
      int end = 0;
      while (tokens.find()) {
        out.append(Pattern.quote(source.substring(end, tokens.start())));
        String name = tokens.group(1);
        if (Character.isDigit(name.charAt(0)))
          throw new IllegalArgumentException("Template variables must start with a letter");
        if (name.equals("sender") || name.equals("message"))
          throw new IllegalArgumentException("[sender] and [message] are reserved for title text");
        if (names.add(name)) out.append("(?<").append(name).append(">.+?)");
        else out.append("\\k<").append(name).append(">");
        end = tokens.end();
      }
      out.append(Pattern.quote(source.substring(end))).append('$');
      source = out.toString();
    }
    pattern =
        Pattern.compile(
            source, rule.caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  public Map<String, String> match(String message, String sender) {
    if (message.length() > 4096) return null;
    Matcher m = pattern.matcher(new BudgetText(message));
    if (!m.find()) return null;
    Map<String, String> vars = new LinkedHashMap<>();
    for (int i = 0; i <= m.groupCount(); i++)
      vars.put(Integer.toString(i), Objects.toString(m.group(i), ""));
    for (String name : pattern.namedGroups().keySet())
      vars.put(name, Objects.toString(m.group(name), ""));
    vars.put("sender", sender == null || sender.isBlank() ? "Unknown sender" : sender);
    vars.put("message", message);
    return vars;
  }

  public static String render(String text, Map<String, String> variables) {
    Matcher m = TOKEN.matcher(text);
    StringBuilder result = new StringBuilder();
    while (m.find())
      m.appendReplacement(
          result, Matcher.quoteReplacement(variables.getOrDefault(m.group(1), m.group())));
    m.appendTail(result);
    return result.toString();
  }

  /** Stops pathological backtracking before it can freeze a client tick. */
  private static final class BudgetText implements CharSequence {
    private final String text;
    private final int[] budget;

    BudgetText(String text) {
      this(text, new int[] {200000});
    }

    BudgetText(String text, int[] budget) {
      this.text = text;
      this.budget = budget;
    }

    public int length() {
      return text.length();
    }

    public char charAt(int i) {
      if (--budget[0] < 0)
        throw new IllegalArgumentException("Regex is too expensive; simplify nested repetitions");
      return text.charAt(i);
    }

    public CharSequence subSequence(int a, int b) {
      return new BudgetText(text.substring(a, b), budget);
    }

    public String toString() {
      return text;
    }
  }
}
