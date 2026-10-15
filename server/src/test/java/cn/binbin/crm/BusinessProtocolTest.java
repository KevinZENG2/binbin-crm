package cn.binbin.crm;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessProtocolTest {
    private Map<String,Object> policy(String scope,boolean leadWrite){return Map.of("record_scope",scope,"lead_can_read",true,"lead_can_write",leadWrite,"contact_can_read",true,"contact_can_write",leadWrite);}
    @Test void broadLeadReadingDoesNotBroadenWriting(){
        var a=BusinessAccess.from(List.of(policy("ALL",false),policy("OWN_SHARED",true)),CustomerAccess.defaults(Set.of("SALES","VIEWER")));
        assertThat(a.leads().readAll()).isTrue();assertThat(a.leads().write()).isTrue();assertThat(a.leads().writeAll()).isFalse();assertThat(a.leads().writeDepartment()).isFalse();
    }
    @Test void contactsRequireCustomerAccessEvenWithAnEnabledModule(){
        var a=BusinessAccess.from(List.of(policy("ALL",true)),CustomerAccess.defaults(Set.of("TENANT_ADMIN")));
        assertThat(a.contactRead()).isFalse();assertThat(a.contactWrite()).isFalse();assertThat(a.leads().read()).isTrue();assertThat(a.leads().write()).isTrue();
    }
    @Test void moduleSwitchesAreIndependent(){
        var p=Map.<String,Object>of("record_scope","DEPARTMENT","lead_can_read",true,"lead_can_write",true,"contact_can_read",false,"contact_can_write",false);
        var a=BusinessAccess.from(List.of(p),CustomerAccess.defaults(Set.of("SALES_MANAGER")));
        assertThat(a.contactRead()).isFalse();assertThat(a.leads().readAll()).isFalse();assertThat(a.leads().readDepartment()).isTrue();assertThat(a.leads().writeDepartment()).isTrue();
    }
    @Test void defaultAdministrationAndSupportDoNotGrantBusinessAccess(){
        assertThat(BusinessAccess.defaults(Set.of("TENANT_ADMIN")).leads().read()).isFalse();assertThat(BusinessAccess.none().contactRead()).isFalse();assertThat(BusinessAccess.none().leads().read()).isFalse();
        assertThat(BusinessAccess.defaults(Set.of("VIEWER")).leads().write()).isFalse();assertThat(BusinessAccess.defaults(Set.of("SALES_MANAGER")).leads().writeAll()).isTrue();
    }
    @Test void leadLifecycleRejectsSkippingQualificationOrReopeningConvertedRecords(){
        BusinessService.transition("NEW","CONTACTED");BusinessService.transition("CONTACTED","QUALIFIED");BusinessService.transition("QUALIFIED","UNQUALIFIED");BusinessService.transition("UNQUALIFIED","NEW");
        assertThatThrownBy(()->BusinessService.transition("NEW","QUALIFIED")).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->BusinessService.transition("CONVERTED","NEW")).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->BusinessService.transition("QUALIFIED","CONVERTED")).isInstanceOf(ApiError.class);
    }
}
