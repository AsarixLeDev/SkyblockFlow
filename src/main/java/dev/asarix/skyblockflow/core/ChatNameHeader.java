package dev.asarix.skyblockflow.core;

/** Locates the sender before a chat separator, never a name inside the message body. */
public final class ChatNameHeader {
  private ChatNameHeader() {}
  public record Sender(String name, int start, int end) {}

  public static Sender find(String text) {
    int brackets = 0;
    for (int i = 0; i < Math.min(text.length(), 160); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') return null;
      if (c == '[') { brackets++; continue; }
      if (c == ']') { brackets = Math.max(0, brackets - 1); continue; }
      if (brackets > 0 || c != ':' && c != '：' && c != '>' && c != '»' && c != '›' && c != '→' && c != '➜') continue;
      int end = i;
      while (end > 0 && space(text.charAt(end - 1))) end--;
      int start = end;
      while (start > 0 && nameChar(text.charAt(start - 1))) start--;
      if (start == end || end - start > 16) return null;
      if (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) return null;
      return new Sender(text.substring(start, end), start, end);
    }
    return null;
  }

  private static boolean space(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
  private static boolean nameChar(char c) {
    return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
  }
}
