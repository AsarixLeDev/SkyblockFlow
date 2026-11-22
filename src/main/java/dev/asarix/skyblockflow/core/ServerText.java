package dev.asarix.skyblockflow.core;

/** Converts the decorative small capitals used in Excalia's messages. */
final class ServerText {
  private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢ";
  private static final String LETTERS = "abcdefghijklmnopqrstuvwyz";

  private ServerText() {}

  static String regularLetters(String text) {
    StringBuilder result = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      int index = SMALL.indexOf(c);
      result.append(index >= 0 ? LETTERS.charAt(index) : Character.isSpaceChar(c) ? ' ' : c);
    }
    return result.toString();
  }
}
