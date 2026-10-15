package cn.binbin.crm;
import java.time.*;import java.util.*;import org.springframework.beans.factory.annotation.Value;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Service;import static cn.binbin.crm.P3Base.map;
@Service
public class P3Jobs {
 private final P3Base b;private final ServiceDeskService desk;private final CampaignService campaigns;private final IntegrationService integrations;private final boolean enabled;
 public P3Jobs(P3Base b,ServiceDeskService desk,CampaignService campaigns,IntegrationService integrations,@Value("${CRM_P3_SCHEDULE_ENABLED:false}") boolean enabled){this.b=b;this.desk=desk;this.campaigns=campaigns;this.integrations=integrations;this.enabled=enabled;}
 public boolean enabled(){return enabled;}
 @Scheduled(initialDelay=45000,fixedDelay=60000) public void scheduled(){if(enabled)try{run();}catch(Exception ignored){}}
 private void run(){UUID job=UUID.randomUUID();b.db.update("INSERT INTO sys_job_run(id,job_name,status) VALUES(:id,'P3_OPERATIONS','RUNNING')",map("id",job));int count=0,failed=0;for(var t:b.rows("SELECT id FROM sys_tenant WHERE status='ACTIVE' AND subscription_end>now() ORDER BY id",Map.of())){UUID tenant=(UUID)t.get("id");try{count+=b.tx.execute(st->{b.db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",map("tenant",tenant),UUID.class);return desk.breaches(tenant);});count+=campaigns.scheduled(tenant);count+=integrations.deliver(tenant);}catch(Exception e){failed++;}}b.db.update("UPDATE sys_job_run SET status=:status,finished_at=now(),processed_count=:count,error_code=:error WHERE id=:id",map("id",job,"status",failed==0?"SUCCESS":"FAILED","count",count,"error",failed==0?null:"P3_TENANT_JOB_FAILED"));}
}
