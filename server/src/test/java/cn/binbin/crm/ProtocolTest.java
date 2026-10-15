package cn.binbin.crm;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    @Test void protectedTenantCannotEnterCustomerDto() {
        var mapper=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThatThrownBy(()->mapper.readValue("{\"name\":\"x\",\"tenantId\":\"00000000-0000-4000-8000-000000000002\"}",CustomerService.Create.class)).isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }
    @Test void editCannotReassignOwnerOrTenant() {
        var mapper=new ObjectMapper();
        assertThatThrownBy(()->mapper.readValue("{\"ownerMemberId\":\"00000000-0000-4000-8000-000000000210\"}",CustomerService.Edit.class)).isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }
    @Test void creditChecksumRejectsSuperficiallyValidCode() {
        assertThatThrownBy(()->CustomerService.credit("123456789012345679")).isInstanceOf(ApiError.class);
    }
    @Test void optionalCreditIsNull() { assertThat(CustomerService.credit("  ")).isNull(); }
    @Test void tokenStorageAndCsrfComparison() {
        String token=Crypto.token();assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Crypto.hash(token)).hasSize(64).isNotEqualTo(token);
        assertThat(Crypto.same(token,token)).isTrue();assertThat(Crypto.same(token,token+"x")).isFalse();assertThat(Crypto.same(token,null)).isFalse();
    }
    @Test void operatorAndTenantAdminDoNotImplyBusinessAccess() {
        var tenant=new AuthService.Tenant(DevelopmentSeed.id(1),"t","Tenant","ACTIVE",java.time.Instant.now().plusSeconds(60),DevelopmentSeed.id(1),"Admin",java.util.Set.of("TENANT_ADMIN"));
        var actor=new AuthService.Actor(null,tenant);
        assertThat(actor.canReadCustomers()).isFalse();assertThat(actor.canWriteCustomers()).isFalse();
    }
    @Test void viewerCannotWrite() {
        var tenant=new AuthService.Tenant(DevelopmentSeed.id(1),"t","Tenant","ACTIVE",java.time.Instant.now().plusSeconds(60),DevelopmentSeed.id(1),"Viewer",java.util.Set.of("VIEWER"));
        var actor=new AuthService.Actor(null,tenant);assertThat(actor.canReadCustomers()).isTrue();assertThatThrownBy(actor::requireWrite).isInstanceOf(ApiError.class);
    }
    @Test void expiredManagerIsReadOnly() {
        var tenant=new AuthService.Tenant(DevelopmentSeed.id(1),"t","Tenant","ACTIVE",java.time.Instant.now().minusSeconds(1),DevelopmentSeed.id(1),"Manager",java.util.Set.of("SALES_MANAGER"));
        var actor=new AuthService.Actor(null,tenant);assertThat(actor.canReadCustomers()).isTrue();assertThatThrownBy(actor::requireWrite).isInstanceOf(ApiError.class);
    }
}
