package dev.asarix.skyblockflow.core;

/** Season 3 island wiki for tiers 1–50; release 3.7 overrides the preliminary table for 51–60. */
public final class IslandUpgrades {
  private static final int[] POWER={0,0,6,10,15,22,30,40,52,66,82,127,188,271,380,520,697,919,1192,1525,1926,2405,2973,3640,4419,5321,6362,7555,8916,10461,12207,14173,16378,18842,21585,24631,28003,31724,35821,40319,45246,50631,56504,62895,69837,77363,85507,94305,103794,114013,125000,136797,149444,162987,177468,192935,209434,227015,245726,265619,286748};
  private static final int[] SIZE={0,50,52,54,56,58,60,62,64,66,68,72,76,80,84,90,96,102,108,114,120,126,132,138,144,150,156,162,168,174,180,186,192,198,204,210,216,222,228,234,240,246,252,258,264,270,276,282,288,294,300,304,308,312,316,320,324,328,332,336,340};
  public static int tier(int size){for(int i=1;i<SIZE.length;i++)if(SIZE[i]==size)return i;return 0;}
  public static int power(int tier){return tier>0 && tier<POWER.length?POWER[tier]:-1;}
  public static int nextGenerator(int tier){if(tier<=0||tier>=51)return 0;return 11+10*((tier-1)/10);}
  private IslandUpgrades(){}
}
