package cn.binbin.crm;
import java.math.*;import java.util.*;
public final class CommissionAmounts {
 public record Tier(long thresholdCents,int rateBps){}
 private CommissionAmounts(){}
 public static void validate(List<Tier> tiers){if(tiers==null||tiers.isEmpty()||tiers.size()>20||tiers.getFirst()==null||tiers.getFirst().thresholdCents()!=0)throw ApiError.bad("首档起点需为0，最多20档");long last=-1;for(var tier:tiers){if(tier==null||tier.thresholdCents()<=last||tier.thresholdCents()>100000000000L||tier.rateBps()<0||tier.rateBps()>10000)throw ApiError.bad("提成档位起点需递增，比例为0%至100%");last=tier.thresholdCents();}}
 public static long calculate(long base,List<Tier> tiers){validate(tiers);if(base<=0)return 0;BigDecimal sum=BigDecimal.ZERO;for(int n=0;n<tiers.size();n++){var tier=tiers.get(n);long end=n+1<tiers.size()?tiers.get(n+1).thresholdCents():base;long slice=Math.max(0,Math.min(base,end)-tier.thresholdCents());sum=sum.add(BigDecimal.valueOf(slice).multiply(BigDecimal.valueOf(tier.rateBps())).movePointLeft(4));}try{long result=sum.setScale(0,RoundingMode.HALF_UP).longValueExact();if(result>100000000000L)throw ApiError.bad("试算金额超出上限");return result;}catch(ArithmeticException e){throw ApiError.bad("试算金额超出上限");}}
}
