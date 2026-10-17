package cn.binbin.crm;

import java.math.*;
import java.util.List;

final class PricingRules {
    record Line(BigDecimal quantity,long costCents,int discountBps) {}
    record Result(long estimatedCostCents,long grossMarginBps) {}

    static Result enforce(long totalCents,List<Line> lines,int maxDiscountBps,int minMarginBps){
        if(totalCents<=0||totalCents>QuoteAmounts.MAX||maxDiscountBps<0||maxDiscountBps>10000||minMarginBps<0||minMarginBps>10000)throw ApiError.bad("报价金额或企业规则无效");
        long cost=0;
        for(var line:lines){
            if(line.discountBps()>maxDiscountBps)throw ApiError.conflict("报价行折扣超过企业上限 "+(maxDiscountBps/100.0)+"%，请调整草稿或规则");
            cost=QuoteAmounts.add(cost,QuoteAmounts.line(line.quantity(),line.costCents(),0));
        }
        if(BigDecimal.valueOf(cost).multiply(BigDecimal.valueOf(10000)).compareTo(BigDecimal.valueOf(totalCents).multiply(BigDecimal.valueOf(10000-minMarginBps)))>0)throw ApiError.conflict("报价毛利低于企业底线 "+(minMarginBps/100.0)+"%，请调整草稿或规则");
        long margin=BigDecimal.valueOf(totalCents-cost).multiply(BigDecimal.valueOf(10000)).divide(BigDecimal.valueOf(totalCents),0,RoundingMode.HALF_UP).longValueExact();
        return new Result(cost,margin);
    }
    private PricingRules(){}
}
