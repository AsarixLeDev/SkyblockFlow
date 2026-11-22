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
    // Map decorative 'ǫ' to q before Unicode decomposition turns it into an o.
    String result = ServerText.regularLetters(value);
    return Normalizer.normalize(result, Normalizer.Form.NFKD)
        .replaceAll("\\p{M}+", "")
        .replaceAll("\\s+", " ")
        .strip();
  }
}
