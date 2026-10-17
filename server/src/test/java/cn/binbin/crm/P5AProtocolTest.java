package cn.binbin.crm;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class P5AProtocolTest {
    @Test void legacyProductInputGetsSafeCommercialDefaults() {
        var input = new CommerceService.ProductInput("CRM-01", "CRM 标准版", "PRODUCT", "套", 199900L, "说明", "ACTIVE", 0);
        assertThat(input.costCents()).isZero();
        assertThat(input.taxRateBps()).isEqualTo(1300);
        assertThat(input.category()).isEmpty();
        var omitted = new CommerceService.ProductInput("CRM-02", "CRM 服务", "SERVICE", "次", 9900L, null, null, null, "说明", "ACTIVE", 0);
        assertThat(omitted.costCents()).isZero();
        assertThat(omitted.taxRateBps()).isEqualTo(1300);
    }

    @Test void legacyCampaignInputKeepsExistingApiCompatible() {
        var input = new CampaignService.Input("秋季活动", "说明", UUID.randomUUID(), "FOLLOW_UP", null, 100000L,
            null, null, "活动跟进", "请及时跟进", LocalDate.of(2099, 10, 1), 0);
        assertThat(input.sourceChannel()).isEqualTo("OTHER");
        assertThat(input.plannedStart()).isNull();
        assertThat(input.plannedEnd()).isNull();
    }

    @Test void resourcePolicyAcceptsExplicitCapacityAndVersion() {
        var input = new SalesFoundationService.PolicyInput(200, 100, "BLOCK", 3, 30, 7);
        assertThat(input.leadLimit()).isEqualTo(200);
        assertThat(input.customerLimit()).isEqualTo(100);
        assertThat(input.expectedVersion()).isEqualTo(7);
    }

    @Test void legacyMemberEditPreservesNewBusinessProfile() {
        var input = new SaasService.MemberEdit("销售小林", null, java.util.Set.of("SALES"), "ACTIVE", 2);
        assertThat(input.employeeCode()).isNull();
        assertThat(input.jobTitle()).isNull();
        assertThat(input.salesRegion()).isNull();
        assertThat(input.serviceStatus()).isNull();
    }
}
