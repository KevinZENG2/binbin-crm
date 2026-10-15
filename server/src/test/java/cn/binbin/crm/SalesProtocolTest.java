package cn.binbin.crm;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SalesProtocolTest {
    private SalesService.StageInput stage(String to,String reason,String next,Long amount){return new SalesService.StageInput(0L,to,reason,next,amount);}
    @Test void stageProgressRequiresAdjacentStepsAndNextAction(){
        SalesService.transition("CONTACT",stage("NEEDS","确认需求","整理方案",null));SalesService.transition("NEEDS",stage("CONTACT","需要再次联系","致电确认",null));
        assertThatThrownBy(()->SalesService.transition("CONTACT",stage("READY","跳跃","签约",null))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->SalesService.transition("CONTACT",stage("NEEDS","原因"," ",null))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->SalesService.transition("CONTACT",stage("NEEDS"," ","下一步",null))).isInstanceOf(ApiError.class);
    }
    @Test void wonRequiresReadyAndItsOwnActualAmount(){
        SalesService.transition("READY",stage("WON","客户签约","",12500L));
        assertThatThrownBy(()->SalesService.transition("NEEDS",stage("WON","提前签约","",12500L))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->SalesService.transition("READY",stage("WON","签约","",null))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->SalesService.transition("READY",stage("WON","签约","",0L))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->SalesService.transition("READY",stage("WON","签约","",100000000001L))).isInstanceOf(ApiError.class);
    }
    @Test void lostIsAvailableBeforeCloseAndClosedRecordsCannotReopen(){
        for(String from:SalesService.STAGES.subList(0,5))SalesService.transition(from,stage("LOST","客户取消","",null));
        assertThatThrownBy(()->SalesService.transition("LOST",stage("CONTACT","重开","跟进",null))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->SalesService.transition("WON",stage("READY","重开","跟进",null))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->SalesService.transition("READY",stage("LOST","取消","",100L))).isInstanceOf(ApiError.class);
    }
    @Test void salesModulesKeepBroadReadSeparateFromWrite(){
        var broad=Map.<String,Object>of("record_scope","ALL","opportunity_can_read",true,"task_can_read",true);
        var own=Map.<String,Object>of("record_scope","OWN_SHARED","opportunity_can_read",true,"opportunity_can_write",true,"task_can_read",true,"task_can_write",true,"activity_can_read",true,"activity_can_write",true);
        var a=BusinessAccess.from(List.of(broad,own),CustomerAccess.defaults(Set.of("SALES","VIEWER")));
        assertThat(a.opportunities().readAll()).isTrue();assertThat(a.opportunities().writeAll()).isFalse();assertThat(a.tasks().readAll()).isTrue();assertThat(a.tasks().writeAll()).isFalse();assertThat(a.activityWrite()).isTrue();
    }
    @Test void moduleDefaultsRespectAdministrationAndReadonly(){
        var admin=BusinessAccess.defaults(Set.of("TENANT_ADMIN"));assertThat(admin.opportunities().read()).isFalse();assertThat(admin.tasks().read()).isFalse();assertThat(admin.activityRead()).isFalse();
        var viewer=BusinessAccess.defaults(Set.of("VIEWER"));assertThat(viewer.opportunities().read()).isTrue();assertThat(viewer.tasks().write()).isFalse();assertThat(viewer.activityWrite()).isFalse();assertThat(BusinessAccess.none().opportunities().read()).isFalse();
    }
}
