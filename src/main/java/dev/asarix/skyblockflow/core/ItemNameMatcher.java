package dev.asarix.skyblockflow.core;

import java.text.Normalizer;
import java.util.Locale;

/** Compare visible item names without color codes, case or decorative small-cap differences. */
public final class ItemNameMatcher {
  private ItemNameMatcher() {}

  public static boolean contains(String name, String wanted) {
    return normalize(name).contains(normalize(wanted));
  }

  private static String normalize(String text) {
    String value = text.toLowerCase(Locale.ROOT).replaceAll("§[0-9a-fk-orx]", "");
    String small = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢ";
    String normal = "abcdefghijklmnopqrstuvwyz";
    StringBuilder result = new StringBuilder();
    for (char c : value.toCharArray()) {
      int i = small.indexOf(c);
      result.append(i >= 0 ? normal.charAt(i) : Character.isSpaceChar(c) ? ' ' : c);
    }
    // Map decorative 'ǫ' to q before Unicode decomposition turns it into an o.
    return Normalizer.normalize(result, Normalizer.Form.NFKD)
        .replaceAll("\\p{M}+", "")
        .replaceAll("\\s+", " ")
        .strip();
  }
}
