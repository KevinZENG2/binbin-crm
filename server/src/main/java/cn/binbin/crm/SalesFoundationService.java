package cn.binbin.crm;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SalesFoundationService {
    public record PolicyInput(@Min(1) @Max(100000) int leadLimit,@Min(1) @Max(100000) int customerLimit,
        @NotNull @Pattern(regexp="BLOCK|WARN") String overflowAction,@Min(1) @Max(365) int leadStaleDays,
        @Min(1) @Max(365) int customerStaleDays,@Min(0) long expectedVersion) {}
    private final NamedParameterJdbcTemplate db;private final TransactionTemplate tx;private final AuthService auth;
    public SalesFoundationService(NamedParameterJdbcTemplate db,TransactionTemplate tx,AuthService auth){this.db=db;this.tx=tx;this.auth=auth;}
    private static Map<String,Object> map(Object... values){return SaasService.parameters(values);}
    private <T>T scoped(AuthService.Session s,boolean write,Function<AuthService.Actor,T> fn){return tx.execute(status->{
        if(write&&s.tenantId()!=null)db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",Map.of("tenant",s.tenantId()),UUID.class);
        var a=auth.actor(s);if(!a.admin())throw ApiError.forbidden("需要本企业管理员权限");if(a.supportGrantId()!=null)throw ApiError.forbidden("支持授权不能管理销售资源规则");
        if(write&&!a.tenant().subscriptionEnd().isAfter(Instant.now()))throw ApiError.forbidden("订阅已到期，当前仅允许查看");
        db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",a.tenant().id().toString()),String.class);return fn.apply(a);
    });}
    public List<Map<String,Object>> policies(AuthService.Session s){return scoped(s,false,a->db.queryForList("""
        SELECT m.id AS "memberId",m.display_name AS "memberName",m.employee_code AS "employeeCode",m.job_title AS "jobTitle",m.sales_region AS "salesRegion",m.service_status AS "serviceStatus",m.status,
         p.id,p.lead_limit AS "leadLimit",p.customer_limit AS "customerLimit",p.overflow_action AS "overflowAction",p.lead_stale_days AS "leadStaleDays",p.customer_stale_days AS "customerStaleDays",p.version,
         (SELECT count(*) FROM crm_lead l WHERE l.tenant_id=m.tenant_id AND l.owner_member_id=m.id AND NOT l.in_pool AND l.status NOT IN('UNQUALIFIED','CONVERTED')) AS "leadCount",
         (SELECT count(*) FROM crm_customer c WHERE c.tenant_id=m.tenant_id AND c.owner_member_id=m.id AND NOT c.in_pool AND c.merged_into_customer_id IS NULL) AS "customerCount"
        FROM sys_tenant_member m JOIN crm_sales_resource_policy p ON p.tenant_id=m.tenant_id AND p.member_id=m.id
        WHERE m.tenant_id=:tenant ORDER BY m.status,m.display_name,m.id
        """,Map.of("tenant",a.tenant().id())));}
    public Map<String,Object> save(AuthService.Session s,UUID member,PolicyInput i,UUID trace){return scoped(s,true,a->{
        var p=map("tenant",a.tenant().id(),"member",member,"lead",i.leadLimit(),"customer",i.customerLimit(),"action",i.overflowAction(),"leadDays",i.leadStaleDays(),"customerDays",i.customerStaleDays(),"version",i.expectedVersion());
        int changed=db.update("UPDATE crm_sales_resource_policy SET lead_limit=:lead,customer_limit=:customer,overflow_action=:action,lead_stale_days=:leadDays,customer_stale_days=:customerDays,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND member_id=:member AND version=:version",p);
        if(changed!=1)throw ApiError.conflict("销售资源规则已变化，请刷新");
        db.update("INSERT INTO sys_audit_log(id,tenant_id,actor_member_id,action,entity_type,entity_id,business_version,trace_id) VALUES(:id,:tenant,:actor,'SALES_RESOURCE_POLICY_UPDATED','RESOURCE_POLICY',:member,:businessVersion,:trace)",map("id",UUID.randomUUID(),"tenant",a.tenant().id(),"actor",a.tenant().memberId(),"member",member,"businessVersion",i.expectedVersion()+1,"trace",trace));
        return policies(a.session()).stream().filter(x->member.equals(x.get("memberId"))).findFirst().orElseThrow(ApiError::missing);
    });}
    public List<Map<String,Object>> alerts(AuthService.Session s){return scoped(s,false,a->db.queryForList("SELECT x.id,x.resource_type AS \"resourceType\",x.current_count AS \"currentCount\",x.limit_value AS \"limitValue\",x.created_at AS \"createdAt\",x.read_at AS \"readAt\",m.display_name AS \"memberName\" FROM crm_resource_alert x JOIN sys_tenant_member m ON m.tenant_id=x.tenant_id AND m.id=x.member_id WHERE x.tenant_id=:tenant ORDER BY x.created_at DESC LIMIT 100",Map.of("tenant",a.tenant().id())));}
    public void readAlert(AuthService.Session s,UUID id){scoped(s,true,a->{db.update("UPDATE crm_resource_alert SET read_at=coalesce(read_at,now()) WHERE tenant_id=:tenant AND id=:id",Map.of("tenant",a.tenant().id(),"id",id));return null;});}
    void ensureMemberPolicy(UUID tenant,UUID member){db.update("INSERT INTO crm_sales_resource_policy(id,tenant_id,member_id) VALUES(:id,:tenant,:member) ON CONFLICT(tenant_id,member_id) DO NOTHING",map("id",UUID.randomUUID(),"tenant",tenant,"member",member));}
    void checkCapacity(AuthService.Actor a,UUID owner,String type){
        ensureMemberPolicy(a.tenant().id(),owner);var p=map("tenant",a.tenant().id(),"member",owner);
        var policy=db.queryForMap("SELECT lead_limit,customer_limit,overflow_action FROM crm_sales_resource_policy WHERE tenant_id=:tenant AND member_id=:member FOR UPDATE",p);
        boolean lead="LEAD".equals(type);int limit=((Number)policy.get(lead?"lead_limit":"customer_limit")).intValue();
        int count=db.queryForObject(lead?"SELECT count(*) FROM crm_lead WHERE tenant_id=:tenant AND owner_member_id=:member AND NOT in_pool AND status NOT IN('UNQUALIFIED','CONVERTED')":"SELECT count(*) FROM crm_customer WHERE tenant_id=:tenant AND owner_member_id=:member AND NOT in_pool AND merged_into_customer_id IS NULL",p,Integer.class);
        if(count<limit)return;
        if("BLOCK".equals(policy.get("overflow_action")))throw new ApiError(HttpStatus.CONFLICT,lead?"LEAD_HOLDING_LIMIT":"CUSTOMER_HOLDING_LIMIT",lead?"该销售的线索保有量已达上限":"该销售的客户保有量已达上限");
        db.update("INSERT INTO crm_resource_alert(id,tenant_id,member_id,resource_type,current_count,limit_value,actor_member_id) VALUES(:id,:tenant,:member,:type,:count,:limit,:actor)",map("id",UUID.randomUUID(),"tenant",a.tenant().id(),"member",owner,"type",type,"count",count,"limit",limit,"actor",a.tenant().memberId()));
    }
}
