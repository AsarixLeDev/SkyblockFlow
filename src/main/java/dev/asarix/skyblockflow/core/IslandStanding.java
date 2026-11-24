package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.ExcaliaApiData.Island;
import java.util.*;

/** Exact positions come from the ordered public snapshot. A power-only comparison is an estimate. */
public record IslandStanding(Island published,String estimate,Island target,long toPass,Island behind,long lead) {
  public static IslandStanding compare(List<Island> board,UUID verifiedId,long power) {
    if(board.isEmpty())return new IslandStanding(null,"",null,0,null,0);
    Island own=null,higher=null,lower=null;int above=0,equal=0;
    for(var row:board){
      if(row.id().equals(verifiedId)){own=row;continue;}
      if(row.power()>power){above++;higher=row;}
      else if(row.power()==power)equal++;
      else if(lower==null)lower=row;
    }
    if(power<0)return new IslandStanding(own,"",null,0,null,0);
    boolean unchanged=own!=null && power==own.power();
    String estimate="";
    if(!unchanged){
      if(power<board.getLast().power() && own==null)estimate="Outside top "+board.size();
      else if(power<=board.getLast().power())estimate="#"+(above+1)+" or lower";
      else estimate=equal==0?"#"+(above+1):"#"+(above+1)+"–"+(above+equal+1);
    }
    if(unchanged){
      higher=own.rank()>1?board.get(own.rank()-2):null;
      lower=own.rank()<board.size()?board.get(own.rank()):null;
    }else if(equal>0){
      for(var row:board)if(!row.id().equals(verifiedId)&&row.power()==power){higher=row;break;}
    }
    return new IslandStanding(own,estimate,higher,higher==null?0:higher.power()-power+1,
        lower,lower==null?0:power-lower.power());
  }
  public static long whole(String value){
    if(value==null || !value.matches("[0-9]+(?:[ '\\u00a0\\u202f][0-9]{3})*"))return -1;
    try{return Long.parseLong(value.replaceAll("[ '\\u00a0\\u202f]",""));}catch(NumberFormatException e){return -1;}
  }
}
