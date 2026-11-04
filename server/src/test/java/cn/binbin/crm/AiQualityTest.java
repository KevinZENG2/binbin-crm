package cn.binbin.crm;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiQualityTest {
 @Test void unsupportedMoneyAndDatesRequireReview(){for(String result:List.of("预计回款 2027-03-04","建议赔偿20000元","赢单率95%"))assertEquals("REVIEW",AiQualityService.inspect("当前处于评估阶段",result,true,List.of()).status());}
 @Test void knownValuesRemainUsable(){assertEquals("PASS",AiQualityService.inspect("有效期2026-10-21；预算100元","已知事实：有效期2026-10-21，预算100元",true,List.of()).status());}
 @Test void tenantSecretNeverReachesReturnedOutput(){var r=AiQualityService.inspect("保密素材","内部项目猎户座应当投入试点",true,List.of("猎户座"));assertEquals("BLOCKED",r.status());assertFalse(r.text().contains("猎户座"));}
 @Test void redactionDoesNotFalselyClaimFullQualityApproval(){var r=AiQualityService.inspect("资料","联系test@example.com",true,List.of());assertEquals("REVIEW",r.status());assertFalse(r.text().contains("test@example.com"));}
 @Test void fabricatedExecutionClaimsRequireHumanReview(){for(String text:List.of("已自动创建线索","已完成付款","已经批准报价"))assertEquals("REVIEW",AiQualityService.inspect("待确认",text,true,List.of()).status());}
 @Test void ordinarySuggestionsPassWithoutInventingFacts(){assertEquals("PASS",AiQualityService.inspect("客户尚在需求评估","建议：核实决策流程；待确认：采购计划",true,List.of()).status());}
 @Test void strictFactsCanBeDisabledWithoutDisablingSensitiveChecks(){assertEquals("PASS",AiQualityService.inspect("未知日期","2027-01-01",false,List.of()).status());assertEquals("BLOCKED",AiQualityService.inspect("未知","保密代号",false,List.of("保密代号")).status());}
 @Test void rolloutCohortIsStableAndHasBothIncludedAndExcludedTenants(){var prompt=UUID.fromString("80000000-0000-4000-8000-000000000001");var buckets=new HashSet<Integer>();for(int n=0;n<300;n++){var tenant=new UUID(0,n);int bucket=AiQualityService.cohort(tenant,"NEW_LEAD",prompt);assertEquals(bucket,AiQualityService.cohort(tenant,"NEW_LEAD",prompt));assertTrue(bucket>=0&&bucket<100);buckets.add(bucket);}assertTrue(buckets.stream().anyMatch(v->v<10));assertTrue(buckets.stream().anyMatch(v->v>=10));}
}
