package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.*;
import java.util.*;

public final class DefaultChatAlerts {
  private DefaultChatAlerts() {}

  public static List<AlertRule> create() {
    return new ArrayList<>(List.of(
        rule("skill-ready", "Skill ready", "[skill] est à nouveau disponible.",
            "Skill [skill] can be used again", ""),
        rule("private-message", "Message privé", "[[player] -> me] [msg]",
            "Message de [player]", "Vous avez reçu un message privé."),
        rule("pet-hungry", "Hungry pet",
            "⌈ ᴘᴇᴛꜱ ⌉ Votre pet a faim, plus aucun effet actif. Nourrissez-le avec /pets.",
            "Your pet is hungry", "you lost its boost")));
  }

  private static AlertRule rule(String id, String name, String pattern, String title, String subtitle) {
    var rule = new AlertRule();
    rule.id = id; rule.name = name; rule.pattern = pattern;
    rule.title = title; rule.subtitle = subtitle;
    rule.soundKind = SoundKind.VANILLA; rule.sound = "minecraft:block.note_block.pling";
    rule.volume = .5f;

    return rule;
  }

  public static void upgrade(Config config) {
    if (config.defaultAlertsVersion >= 1) return;
    for (var rule : create()) {
      boolean exists = config.alerts.stream().anyMatch(existing ->
          existing.id.equals(rule.id) || existing.pattern.equalsIgnoreCase(rule.pattern));
      if (!exists) config.alerts.add(rule);
    }
    config.defaultAlertsVersion = 1;
  }
}
