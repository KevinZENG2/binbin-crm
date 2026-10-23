package cn.binbin.crm;
import org.junit.jupiter.api.Test;import java.util.List;import static org.assertj.core.api.Assertions.*;
class CommissionAmountsTest {
 @Test void progressiveTiersDoNotRepriceEarlierRevenue(){var tiers=List.of(new CommissionAmounts.Tier(0,300),new CommissionAmounts.Tier(50000,500));assertThat(CommissionAmounts.calculate(50000,tiers)).isEqualTo(1500);assertThat(CommissionAmounts.calculate(60000,tiers)).isEqualTo(2000);assertThat(CommissionAmounts.calculate(0,tiers)).isZero();assertThat(CommissionAmounts.calculate(-10000,tiers)).isZero();}
 @Test void roundingOccursOnceAtTheFinalCurrencyCent(){var tiers=List.of(new CommissionAmounts.Tier(0,5000),new CommissionAmounts.Tier(1,5000));assertThat(CommissionAmounts.calculate(2,tiers)).isEqualTo(1);assertThat(CommissionAmounts.calculate(1,tiers)).isEqualTo(1);}
 @Test void invalidRulesAndOverflowAreRejected(){for(var tiers:List.of(List.<CommissionAmounts.Tier>of(),List.of(new CommissionAmounts.Tier(1,300)),List.of(new CommissionAmounts.Tier(0,300),new CommissionAmounts.Tier(0,500)),List.of(new CommissionAmounts.Tier(0,10001))))assertThatThrownBy(()->CommissionAmounts.validate(tiers)).isInstanceOf(ApiError.class);assertThatThrownBy(()->CommissionAmounts.calculate(Long.MAX_VALUE,List.of(new CommissionAmounts.Tier(0,10000)))).isInstanceOf(ApiError.class);}
}
