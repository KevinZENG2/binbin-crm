package cn.binbin.crm;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class TradeProtocolTest {
 @Test void technicalAdminDoesNotAcquireContractsOrCash(){assertThat(TradeAccess.defaults(Set.of("TENANT_ADMIN"))).isEqualTo(TradeAccess.none());assertThat(TradeAccess.defaults(Set.of("VIEWER")).receiptWrite()).isFalse();assertThat(TradeAccess.defaults(Set.of("SALES")).confirm()).isFalse();}
 @Test void salesHasOwnContractsAndManagerHasSeparateDecisions(){var sales=TradeAccess.defaults(Set.of("SALES"));assertThat(sales.contracts().write()).isTrue();assertThat(sales.contracts().writeAll()).isFalse();assertThat(sales.receiptWrite()).isTrue();var manager=TradeAccess.from(List.of(SaasService.parameters("role_code","SALES_MANAGER","record_scope","ALL","contract_can_read",true,"contract_can_write",false,"receipt_can_read",true,"receipt_can_confirm",true,"receipt_can_reverse",true)));assertThat(manager.contracts().write()).isFalse();assertThat(manager.confirm()).isTrue();assertThat(manager.reverse()).isTrue();}
 @Test void nonManagerAndUnreadableCashCannotGainDecisionRights(){for(String role:List.of("SALES","VIEWER","TENANT_ADMIN"))assertThat(TradeAccess.from(List.of(SaasService.parameters("role_code",role,"contract_can_read",true,"receipt_can_read",true,"receipt_can_confirm",true))).confirm()).isFalse();assertThat(TradeAccess.from(List.of(SaasService.parameters("role_code","SALES_MANAGER","receipt_can_read",true,"receipt_can_confirm",true))).confirm()).isFalse();}
 @Test void bankReferencesNormalizeCaseWidthAndSpaces(){assertThat(FinanceService.reference(" ａｂＣ １２3 ")).isEqualTo("ABC123");assertThat(FinanceService.reference("银行-凭证01")).isEqualTo("银行-凭证01");for(String value:List.of("  ","bank\n123","bank\t123","bank\u200b123"))assertThatThrownBy(()->FinanceService.reference(value)).isInstanceOf(ApiError.class);}
 @Test void aggregateMoneyNeverUsesFloatingPoint(){assertThat(TradeService.money("900719925474099300")).isEqualTo("9007199254740993.00");assertThat(TradeService.money(1)).isEqualTo("0.01");assertThat(TradeService.money(null)).isEmpty();}
}
