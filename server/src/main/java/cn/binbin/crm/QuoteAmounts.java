package cn.binbin.crm;
import java.math.*;

public final class QuoteAmounts {
    public static final long MAX=100000000000L;
    public static long line(BigDecimal quantity,long cents,int discountBps){
        if(quantity==null||quantity.signum()<=0||quantity.scale()>4||quantity.compareTo(new BigDecimal("1000000"))>0||cents<0||cents>MAX||discountBps<0||discountBps>10000)throw ApiError.bad("数量、单价或折扣超出范围");
        try{long amount=quantity.multiply(BigDecimal.valueOf(cents)).multiply(BigDecimal.valueOf(10000-discountBps)).divide(BigDecimal.valueOf(10000),0,RoundingMode.HALF_UP).longValueExact();if(amount>MAX)throw ApiError.bad("单行金额超过上限");return amount;}catch(ArithmeticException e){throw ApiError.bad("金额超过上限");}
    }
    public static long add(long total,long amount){try{long value=Math.addExact(total,amount);if(value>MAX)throw ApiError.bad("报价总额超过上限");return value;}catch(ArithmeticException e){throw ApiError.bad("报价总额超过上限");}}
    private QuoteAmounts(){}
}
