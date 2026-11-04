package cn.binbin.crm;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class P5DProtocolTest {
    @Test void discountAndMarginAreCheckedAgainstCostSnapshot(){
        var lines=List.of(new PricingRules.Line(new BigDecimal("2.5"),300,1000));
        var result=PricingRules.enforce(1125,lines,3000,3000);
        assertThat(result.estimatedCostCents()).isEqualTo(750);
        assertThat(result.grossMarginBps()).isEqualTo(3333);
        assertThatThrownBy(()->PricingRules.enforce(1125,lines,999,3000)).isInstanceOf(ApiError.class).hasMessageContaining("折扣");
        assertThatThrownBy(()->PricingRules.enforce(1125,lines,3000,3500)).isInstanceOf(ApiError.class).hasMessageContaining("毛利");
    }
    @Test void zeroPriceAndCostOverflowCannotBeSubmitted(){
        assertThatThrownBy(()->PricingRules.enforce(0,List.of(),3000,1000)).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->PricingRules.enforce(1,List.of(new PricingRules.Line(BigDecimal.TEN,QuoteAmounts.MAX,0)),3000,0)).isInstanceOf(ApiError.class);
    }
}
