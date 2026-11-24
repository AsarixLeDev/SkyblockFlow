package dev.asarix.skyblockflow.core;

import java.util.*;
import java.util.regex.*;

/** A profile is accepted only when the name and the profile sheet's fields occur on the same item. */
public final class InspectedProfile {
  public record Item(String title,List<String> lore) {}
  public record Fact(String value,long at) {}
  public record Sheet(String name,long at,Map<String,Fact> facts) {}
  private record Rule(String key,Pattern pattern) {}
  private static final List<Rule> ISLAND_RULES=List.of(
      new Rule("Tier",Pattern.compile("\\bpalier\\s*[:·]?\\s*(\\d+)\\s*/\\s*(\\d+)")),
      new Rule("Dimensions",Pattern.compile("\\btaille\\s*[:·]?\\s*(\\d+)\\s*[x×]\\s*(\\d+)")),
      new Rule("Generator",Pattern.compile("\\bgenerateur\\s*[:·]?\\s*(?:tier\\s*)?(\\d+)")),
      new Rule("Members",Pattern.compile("\\bmembres\\s*[:·]?\\s*(\\d+)\\s*/\\s*(\\d+)")));
  private static final List<Rule> RULES=new ArrayList<>();
  private static final String NUM="([0-9]+(?:[ '’.,][0-9]+)*)";
  static {
    number("Balance","skydollars?"); number("Vote coins","votecoins?");
    number("Island power","puissance"); number("Season tier","(?:battle pass\\s+)?palier");
    number("Blocks mined","blocs? casses?"); number("Crops harvested","cultures? recoltees?");
    number("Mobs killed","mobs? tues?"); number("Deaths","morts?");
    for(var entry:JobXp.NAMES.entrySet())
      number("Job/"+entry.getKey(),entry.getKey().equals("pecheur")?"(?:pecheur|peche)":entry.getKey());
    rule("Achievements","(?:succes|score)\\s*[:·]?\\s*"+NUM+"\\s*/\\s*"+NUM);
    rule("Playtime","temps de jeu\\s*[:·]?\\s*([0-9]+\\s*[jdhms](?:\\s*[0-9]+\\s*[jdhms])*)");
    rule("Island rank","rang\\s*[:·]?\\s*#?\\s*(non classe|[0-9]+)");
    rule("Grade","grade\\s*[:·]?\\s*([\\p{L}][\\p{L}0-9+_-]{0,24})");
    rule("Duel rating","duels?\\s+elo\\s*[:·]?\\s*"+NUM);
    rule("Duel wins","duels?\\s+elo\\s*[:·]?\\s*[0-9 ,.]+\\s*[·|]\\s*"+NUM+"\\s*v\\b");
    rule("Duel losses","\\bv\\s*[·|]\\s*"+NUM+"\\s*d\\b");
    for(String dungeon:List.of("le vaisseau mere","la base rouge")) {
      String group=dungeon.startsWith("le ")?"Mothership":"Red Base";
      rule(group+"/Clears",dungeon+"\\s+completes?\\s*[:·]?\\s*"+NUM);
      rule(group+"/Mobs",dungeon+".*?\\bmobs\\s*[:·]?\\s*"+NUM);
    }
  }
  private static void number(String key,String prefix){rule(key,"\\b"+prefix+"\\s*[:·]?\\s*"+NUM);}
  private static void rule(String key,String regex){RULES.add(new Rule(key,Pattern.compile(regex)));}
  public static Sheet read(List<Item> items,long at) {
    String name="";
    for(var item:items) {
      String candidate=JobXp.decodeGlyphs(item.title()).replaceAll("§[0-9A-FK-ORXa-fk-orx]","")
          .replaceAll("^[^A-Za-z0-9_]+|[^A-Za-z0-9_]+$","");
      if(!candidate.matches("[A-Za-z0-9_]{1,16}"))continue;
      String lore=JobXp.normalize(String.join("\n",item.lore()));
      if(lore.contains("skydollar") && (lore.contains("grade") || lore.contains("puissance"))
          && (lore.contains("temps de jeu") || lore.contains("battle pass") || lore.contains("blocs casses"))) {
        if(!name.isEmpty() && !candidate.equalsIgnoreCase(name))return null;
        name=candidate;
      }
    }
    if(name.isEmpty())return null;
    var facts=new LinkedHashMap<String,Fact>();
    for(var item:items) {
      String title=JobXp.normalize(item.title());
      for(String raw:item.lore()) {
        if(raw.length()>2048)continue;
        String line=JobXp.normalize(raw.replace("\ua473"," VIP ").replace("\ua477"," MVP ").replace("\ua479"," MVP+ ").replace("\ua489"," Citoyen ").replace("\uf700"," Heros ")).replaceAll("[\\p{Co}\\p{Cf}]"," ").replaceAll("\\s+"," ").strip();
        if(title.matches(".*\\bile\\b.*")) {
          for(var rule:ISLAND_RULES){
            var match=rule.pattern.matcher(line);
            if(match.find()){
              String value=match.group(1).strip();
              if(match.groupCount()==2)value+=(rule.key.equals("Dimensions")?" × ":" / ")+match.group(2).strip();
              facts.put("Island/"+rule.key,new Fact(value,at));
            }
          }
        }
        for(var rule:RULES) {
          if(rule.key.equals("Season tier") && !line.contains("battle pass") && !title.contains("battle pass"))continue;
          var match=rule.pattern.matcher(line);
          if(!match.find())continue;
          String value=match.group(1).strip();
          if(rule.key.equals("Achievements"))value+=" / "+match.group(2).strip();
          if(rule.key.equals("Grade"))value=value.toUpperCase(Locale.ROOT);
          if(rule.key.equals("Island rank") && value.equals("non classe"))value="Unranked";
          facts.put(rule.key,new Fact(value,at));
        }
      }
    }
    return new Sheet(name,at,Map.copyOf(facts));
  }
}
