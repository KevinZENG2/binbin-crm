package cn.binbin.crm;

import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerCollaborationService {
    public record StageInput(@Min(0) long expectedVersion,@NotNull @Pattern(regexp="PROSPECT|ACTIVE|DORMANT|LOST") String stage,@NotBlank @Size(max=500) String reason) {}
    public record MergeInput(@NotNull UUID targetCustomerId,@Min(0) long sourceVersion,@Min(0) long targetVersion,@NotBlank @Size(max=500) String reason) {}
    public record FollowupInput(@NotNull @Pattern(regexp="LEAD|CUSTOMER|OPPORTUNITY") String targetType,@NotNull UUID targetId,
        @NotNull @Pattern(regexp="PLAN|RECORD") String kind,@NotBlank @Size(max=4000) String content,
        Instant dueAt,Instant occurredAt,@Pattern(regexp="CONNECTED|NO_ANSWER|INTERESTED|NOT_INTERESTED|OTHER") String resultCode,
        @Size(max=1000) String nextAction,UUID attachmentId,UUID planId) {}
    public record CompleteInput(@Min(0) long expectedVersion,@NotBlank @Size(max=4000) String content,
        @NotNull @Pattern(regexp="CONNECTED|NO_ANSWER|INTERESTED|NOT_INTERESTED|OTHER") String resultCode,
        @Size(max=1000) String nextAction,UUID attachmentId) {}

    private final NamedParameterJdbcTemplate db;
    private final TransactionTemplate tx;
    private final AuthService auth;
    private final CustomerService customers;
    private final BusinessService business;
    private final SalesService sales;
    public CustomerCollaborationService(NamedParameterJdbcTemplate db,TransactionTemplate tx,AuthService auth,CustomerService customers,BusinessService business,SalesService sales){
        this.db=db;this.tx=tx;this.auth=auth;this.customers=customers;this.business=business;this.sales=sales;
    }
    private static Map<String,Object> p(Object... values){return SaasService.parameters(values);}
    private static String trim(String s){return s==null?"":s.strip();}
    private <T>T scoped(AuthService.Session s,boolean write,Function<AuthService.Actor,T> fn){return tx.execute(st->{
        if(write&&s.tenantId()!=null)db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",p("tenant",s.tenantId()),UUID.class);
        var a=auth.actor(s);if(a.supportGrantId()!=null)throw ApiError.forbidden("当前支持授权不包含客户协同资料");
        if(write&&!a.tenant().subscriptionEnd().isAfter(Instant.now()))throw ApiError.forbidden("订阅已到期，当前仅允许查看");
        db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",p("tenant",a.tenant().id().toString()),String.class);
        return fn.apply(a);
    });}
    private static Map<String,Object> view(Map<String,Object> row){return SaasService.view(row);}
    private void audit(AuthService.Actor a,String action,UUID id,long version,UUID trace){db.update("INSERT INTO sys_audit_log(id,tenant_id,actor_member_id,action,entity_type,entity_id,business_version,trace_id) VALUES(:id,:tenant,:actor,:action,'CUSTOMER',:customer,:version,:trace)",p("id",UUID.randomUUID(),"tenant",a.tenant().id(),"actor",a.tenant().memberId(),"action",action,"customer",id,"version",version,"trace",trace));}
    private void history(AuthService.Actor a,UUID customer,String action,String from,String to,UUID related,String reason,long version){db.update("INSERT INTO crm_customer_lifecycle_history(id,tenant_id,customer_id,action,from_stage,to_stage,related_customer_id,reason,actor_member_id,resource_version) VALUES(:id,:tenant,:customer,:action,:from,:to,:related,:reason,:actor,:version)",p("id",UUID.randomUUID(),"tenant",a.tenant().id(),"customer",customer,"action",action,"from",from,"to",to,"related",related,"reason",trim(reason),"actor",a.tenant().memberId(),"version",version));}
    public Object profile(AuthService.Session s,UUID id){return scoped(s,false,a->{if(!a.canReadCustomers())throw ApiError.forbidden("没有客户读取权限");var c=customers.get(a,id);var x=p("tenant",a.tenant().id(),"id",id);var data=new LinkedHashMap<String,Object>();data.put("customer",c);
        data.put("lifecycle",db.queryForObject("SELECT lifecycle_stage FROM crm_customer WHERE tenant_id=:tenant AND id=:id",x,String.class));
        data.put("tags",db.queryForList("SELECT t.id,t.name,t.color FROM crm_customer_tag ct JOIN crm_tag t ON t.tenant_id=ct.tenant_id AND t.id=ct.tag_id WHERE ct.tenant_id=:tenant AND ct.customer_id=:id AND t.status='ACTIVE' ORDER BY t.name",x));
        data.put("history",db.queryForList("SELECT h.action,h.from_stage AS \"fromStage\",h.to_stage AS \"toStage\",h.reason,h.created_at AS \"createdAt\",m.display_name AS \"actorName\" FROM crm_customer_lifecycle_history h JOIN sys_tenant_member m ON m.tenant_id=h.tenant_id AND m.id=h.actor_member_id WHERE h.tenant_id=:tenant AND h.customer_id=:id ORDER BY h.created_at DESC,h.id DESC LIMIT 50",x));
        return data;
    });}
    public Object duplicates(AuthService.Session s,UUID id){return scoped(s,false,a->{if(!a.canReadCustomers())throw ApiError.forbidden("没有客户读取权限");var c=customers.get(a,id);var x=customers.scope(a);x.put("id",id);x.put("name",c.name());
        String credit=a.access().creditRead()?c.creditCode():null;x.put("credit",credit);
        return db.queryForList("SELECT c.id,c.customer_name AS name,m.display_name AS \"ownerName\",c.lifecycle_stage AS lifecycle,"+
            "CASE WHEN :credit IS NOT NULL AND c.credit_code=:credit THEN 'CREDIT_CODE' ELSE 'NAME' END AS \"matchType\",c.version "+
            "FROM crm_customer c JOIN sys_tenant_member m ON m.tenant_id=c.tenant_id AND m.id=c.owner_member_id WHERE "+CustomerService.READ+
            " AND c.id<>:id AND (regexp_replace(lower(c.customer_name),'[[:space:]]','','g')=regexp_replace(lower(:name),'[[:space:]]','','g') OR (:credit IS NOT NULL AND c.credit_code=:credit)) ORDER BY c.updated_at DESC,c.id LIMIT 30",x);
    });}
    public Object stage(AuthService.Session s,UUID id,StageInput input,UUID trace){return scoped(s,true,a->{if(!a.canWriteCustomers())throw ApiError.forbidden("没有客户维护权限");var c=customers.get(a,id);if(!c.canWrite())throw ApiError.forbidden("无权维护该客户生命周期");var x=p("tenant",a.tenant().id(),"id",id,"version",input.expectedVersion(),"stage",input.stage());
        var before=db.queryForMap("SELECT lifecycle_stage,version FROM crm_customer WHERE tenant_id=:tenant AND id=:id FOR UPDATE",x);String prior=(String)before.get("lifecycle_stage");if(prior.equals(input.stage()))throw ApiError.conflict("客户已经处于该阶段");
        int count=db.update("UPDATE crm_customer SET lifecycle_stage=:stage,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id AND version=:version AND merged_into_customer_id IS NULL AND NOT in_pool",x);
        if(count!=1)throw ApiError.conflict("客户版本已变化，请刷新");history(a,id,"STAGE",prior,input.stage(),null,input.reason(),input.expectedVersion()+1);audit(a,"CUSTOMER_LIFECYCLE_CHANGED",id,input.expectedVersion()+1,trace);return profile(s,id);
    });}
    private void sourceMustBeSimple(UUID tenant,UUID source){
        for(String table:List.of("crm_opportunity","crm_activity","crm_task","crm_quote","crm_contract","crm_ticket","crm_attachment","crm_followup","portal_account","portal_audit")){
            long count=db.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=:tenant AND customer_id=:source",p("tenant",tenant,"source",source),Long.class);
            if(count>0)throw ApiError.conflict("来源客户已关联业务或互动记录，请保留独立档案；先完成相关业务后再处理");
        }
        long conversions=db.queryForObject("SELECT count(*) FROM crm_lead WHERE tenant_id=:tenant AND converted_customer_id=:source",p("tenant",tenant,"source",source),Long.class);
        if(conversions>0)throw ApiError.conflict("来源客户由线索转换生成，不能改变历史线索关联");
        long nested=db.queryForObject("SELECT count(*) FROM crm_customer WHERE tenant_id=:tenant AND merged_into_customer_id=:source",p("tenant",tenant,"source",source),Long.class);
        if(nested>0)throw ApiError.conflict("来源客户已有合并历史，不能再次作为来源");
    }
    public Object merge(AuthService.Session s,UUID source,MergeInput input,UUID trace){return scoped(s,true,a->{if(!a.canWriteCustomers()||!a.businessAccess().contactWrite())throw ApiError.forbidden("合并需要客户和联系人维护权限");if(source.equals(input.targetCustomerId()))throw ApiError.bad("不能合并同一客户");if(!a.admin()&&!a.access().writeAll())throw ApiError.forbidden("需要企业管理员或销售经理权限");
        if(!a.access().creditRead()||!a.access().descriptionRead())throw ApiError.forbidden("合并需要客户信用代码和备注读取权限，请联系企业管理员");
        UUID target=input.targetCustomerId(),tenant=a.tenant().id();var ids=List.of(source,target).stream().sorted().toList();
        for(UUID id:ids)if(db.queryForList("SELECT id FROM crm_customer WHERE tenant_id=:tenant AND id=:id FOR UPDATE",p("tenant",tenant,"id",id),UUID.class).isEmpty())throw ApiError.missing();
        var from=customers.get(a,source);var to=customers.get(a,target);if(!from.canWrite()||!to.canWrite())throw ApiError.forbidden("两位客户都必须在可编辑范围内");
        if(from.version()!=input.sourceVersion()||to.version()!=input.targetVersion())throw ApiError.conflict("客户信息已变化，请重新检查合并对象");
        sourceMustBeSimple(tenant,source);
        if(from.creditCode()!=null&&to.creditCode()!=null&&!from.creditCode().equals(to.creditCode()))throw ApiError.conflict("两位客户的信用代码不同，不允许合并");
        var x=p("tenant",tenant,"source",source,"target",target,"actor",a.tenant().memberId(),"sv",input.sourceVersion(),"tv",input.targetVersion());
        var sourcePrimary=db.queryForList("SELECT id FROM crm_contact WHERE tenant_id=:tenant AND customer_id=:source AND is_primary",x,UUID.class);
        boolean targetHasPrimary=db.queryForObject("SELECT EXISTS(SELECT 1 FROM crm_contact WHERE tenant_id=:tenant AND customer_id=:target AND is_primary)",x,Boolean.class);
        db.update("UPDATE crm_contact SET is_primary=false,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND customer_id=:source AND is_primary",x);
        db.update("UPDATE crm_contact SET customer_id=:target,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND customer_id=:source",x);
        if(!targetHasPrimary&&!sourcePrimary.isEmpty())db.update("UPDATE crm_contact SET is_primary=true,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND customer_id=:target AND id=:primary",with(x,"primary",sourcePrimary.getFirst()));
        db.update("INSERT INTO crm_customer_tag(tenant_id,customer_id,tag_id,assigned_by_member_id) SELECT :tenant,:target,tag_id,:actor FROM crm_customer_tag WHERE tenant_id=:tenant AND customer_id=:source ON CONFLICT DO NOTHING",x);
        db.update("INSERT INTO crm_custom_value(id,tenant_id,field_id,entity_type,record_id,value,updated_by_member_id) SELECT gen_random_uuid(),tenant_id,field_id,entity_type,:target,value,:actor FROM crm_custom_value WHERE tenant_id=:tenant AND entity_type='CUSTOMER' AND record_id=:source ON CONFLICT DO NOTHING",x);
        db.update("INSERT INTO crm_customer_share(tenant_id,customer_id,member_id,can_write,expires_at) SELECT :tenant,:target,member_id,can_write,expires_at FROM crm_customer_share WHERE tenant_id=:tenant AND customer_id=:source AND member_id<>(SELECT owner_member_id FROM crm_customer WHERE tenant_id=:tenant AND id=:target) ON CONFLICT DO NOTHING",x);
        db.update("UPDATE crm_customer SET credit_code=NULL,merged_into_customer_id=:target,merged_at=now(),version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:source AND version=:sv",x);
        db.update("UPDATE crm_customer SET credit_code=coalesce(credit_code,:credit),description=CASE WHEN description='' THEN :notes ELSE description END,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:target AND version=:tv",with(x,"credit",from.creditCode(),"notes",from.description()==null?"":from.description()));
        history(a,source,"MERGE_SOURCE",null,null,target,input.reason(),input.sourceVersion()+1);history(a,target,"MERGE_TARGET",null,null,source,input.reason(),input.targetVersion()+1);
        audit(a,"CUSTOMER_MERGED",source,input.sourceVersion()+1,trace);audit(a,"CUSTOMER_MERGE_TARGET",target,input.targetVersion()+1,trace);
        return profile(s,target);
    });}
    private static Map<String,Object> with(Map<String,Object> base,Object... extra){var copy=new HashMap<>(base);for(int i=0;i<extra.length;i+=2)copy.put((String)extra[i],extra[i+1]);return copy;}
    private record Target(String type,UUID lead,UUID customer,UUID opportunity) {}
    private Target target(AuthService.Actor a,String type,UUID id,boolean write){
        switch(type){
            case "LEAD"->{if(!a.businessAccess().leads().read()||write&&!a.businessAccess().leads().write())throw ApiError.forbidden("没有线索权限");var lead=business.lead(a,id);if(write&&!lead.canWrite())throw ApiError.forbidden("无权维护该线索跟进");return new Target(type,id,null,null);}
            case "CUSTOMER"->{if(!a.canReadCustomers())throw ApiError.forbidden("没有客户读取权限");var c=customers.get(a,id);if(write&&!c.canWrite())throw ApiError.forbidden("无权维护该客户跟进");return new Target(type,null,id,null);}
            case "OPPORTUNITY"->{if(!a.businessAccess().opportunities().read())throw ApiError.forbidden("没有商机权限");var op=sales.opportunity(a,id);if(write&&!Boolean.TRUE.equals(op.get("canWrite")))throw ApiError.forbidden("无权维护该商机跟进");return new Target(type,null,(UUID)op.get("customerId"),id);}
            default->throw ApiError.bad("关联层级无效");
        }
    }
    private void attachment(AuthService.Actor a,UUID id,UUID customer){if(id==null)return;if(customer==null||!a.deliveryAccess().attachmentRead())throw ApiError.forbidden("线索跟进暂不能关联附件，或当前角色无附件权限");int count=db.queryForObject("SELECT count(*) FROM crm_attachment WHERE tenant_id=:tenant AND id=:id AND customer_id=:customer AND status='READY'",p("tenant",a.tenant().id(),"id",id,"customer",customer),Integer.class);if(count!=1)throw ApiError.bad("附件不属于该客户或已删除");}
    private void followupAccess(AuthService.Actor a,String kind,boolean write){boolean allowed="PLAN".equals(kind)?write?a.businessAccess().tasks().write():a.businessAccess().tasks().read():write?a.businessAccess().activityWrite():a.businessAccess().activityRead();if(!allowed)throw ApiError.forbidden("当前角色没有跟进计划或记录权限");}
    private Map<String,Object> followup(AuthService.Actor a,UUID id){var rows=db.queryForList("SELECT f.*,m.display_name AS \"assigneeName\" FROM crm_followup f JOIN sys_tenant_member m ON m.tenant_id=f.tenant_id AND m.id=f.assignee_member_id WHERE f.tenant_id=:tenant AND f.id=:id",p("tenant",a.tenant().id(),"id",id));if(rows.isEmpty())throw ApiError.missing();var item=view(rows.getFirst());followupAccess(a,(String)item.get("kind"),false);UUID targetId=(UUID)item.get(switch((String)item.get("targetType")){case "LEAD"->"leadId";case "OPPORTUNITY"->"opportunityId";default->"customerId";});target(a,(String)item.get("targetType"),targetId,false);return item;}
    public Object createFollowup(AuthService.Session s,FollowupInput i,UUID trace){return scoped(s,true,a->{followupAccess(a,i.kind(),true);var t=target(a,i.targetType(),i.targetId(),true);Instant now=Instant.now();
        if(i.kind().equals("PLAN")){if(i.dueAt()==null||!i.dueAt().isAfter(now)||i.occurredAt()!=null||i.resultCode()!=null||i.planId()!=null||i.attachmentId()!=null)throw ApiError.bad("计划需填写未来的时间，不能包含跟进结果或附件");}
        else if(i.occurredAt()==null||i.occurredAt().isAfter(now.plusSeconds(300))||i.occurredAt().isBefore(Instant.parse("2000-01-01T00:00:00Z"))||i.resultCode()==null||i.dueAt()!=null||i.planId()!=null)throw ApiError.bad("跟进记录需填写实际时间和结果");
        attachment(a,i.attachmentId(),t.customer());UUID id=UUID.randomUUID();var x=p("id",id,"tenant",a.tenant().id(),"kind",i.kind(),"type",t.type(),"lead",t.lead(),"customer",t.customer(),"opportunity",t.opportunity(),"actor",a.tenant().memberId(),"due",i.dueAt()==null?null:Timestamp.from(i.dueAt()),"occurred",i.occurredAt()==null?null:Timestamp.from(i.occurredAt()),"status",i.kind().equals("PLAN")?"PENDING":"DONE","content",trim(i.content()),"result",i.resultCode(),"next",trim(i.nextAction()),"attachment",i.attachmentId());
        db.update("INSERT INTO crm_followup(id,tenant_id,kind,target_type,lead_id,customer_id,opportunity_id,assignee_member_id,actor_member_id,due_at,occurred_at,status,content,result_code,next_action,attachment_id) VALUES(:id,:tenant,:kind,:type,:lead,:customer,:opportunity,:actor,:actor,:due,:occurred,:status,:content,:result,:next,:attachment)",x);
        auditFollowup(a,id,"FOLLOWUP_"+i.kind()+"_CREATED",trace);return followup(a,id);
    });}
    private void auditFollowup(AuthService.Actor a,UUID id,String action,UUID trace){db.update("INSERT INTO sys_audit_log(id,tenant_id,actor_member_id,action,entity_type,entity_id,business_version,trace_id) VALUES(:id,:tenant,:actor,:action,'FOLLOWUP',:resource,0,:trace)",p("id",UUID.randomUUID(),"tenant",a.tenant().id(),"actor",a.tenant().memberId(),"action",action,"resource",id,"trace",trace));}
    public Object complete(AuthService.Session s,UUID id,CompleteInput i,UUID trace){return scoped(s,true,a->{followupAccess(a,"PLAN",true);followupAccess(a,"RECORD",true);var plan=followup(a,id);if(!"PLAN".equals(plan.get("kind"))||!"PENDING".equals(plan.get("status")))throw ApiError.conflict("该计划已完成");if(!a.tenant().memberId().equals(plan.get("assigneeMemberId"))&&!a.admin()&&!a.businessAccess().tasks().writeAll())throw ApiError.forbidden("只能完成本人负责的计划");
        UUID targetId=(UUID)plan.get(switch((String)plan.get("targetType")){case "LEAD"->"leadId";case "OPPORTUNITY"->"opportunityId";default->"customerId";});target(a,(String)plan.get("targetType"),targetId,true);
        if(((Number)plan.get("version")).longValue()!=i.expectedVersion())throw ApiError.conflict("计划已变化，请刷新");attachment(a,i.attachmentId(),(UUID)plan.get("customerId"));
        var x=p("tenant",a.tenant().id(),"plan",id,"version",i.expectedVersion(),"id",UUID.randomUUID(),"actor",a.tenant().memberId(),"content",trim(i.content()),"result",i.resultCode(),"next",trim(i.nextAction()),"attachment",i.attachmentId());
        int changed=db.update("UPDATE crm_followup SET status='DONE',version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:plan AND kind='PLAN' AND status='PENDING' AND version=:version",x);if(changed!=1)throw ApiError.conflict("计划已被其他人完成");
        db.update("INSERT INTO crm_followup(id,tenant_id,kind,target_type,lead_id,customer_id,opportunity_id,plan_id,assignee_member_id,actor_member_id,occurred_at,status,content,result_code,next_action,attachment_id) SELECT :id,tenant_id,'RECORD',target_type,lead_id,customer_id,opportunity_id,id,assignee_member_id,:actor,now(),'DONE',:content,:result,:next,:attachment FROM crm_followup WHERE tenant_id=:tenant AND id=:plan",x);
        auditFollowup(a,(UUID)x.get("id"),"FOLLOWUP_PLAN_COMPLETED",trace);return followup(a,(UUID)x.get("id"));
    });}
    public Object followups(AuthService.Session s,String type,UUID id){return scoped(s,false,a->{var t=target(a,type,id,false);var x=p("tenant",a.tenant().id(),"lead",t.lead(),"customer",t.customer(),"opportunity",t.opportunity());
        String where=type.equals("LEAD")?"f.lead_id=:lead":type.equals("OPPORTUNITY")?"f.opportunity_id=:opportunity":"(f.customer_id=:customer OR EXISTS(SELECT 1 FROM crm_lead l WHERE l.tenant_id=f.tenant_id AND l.id=f.lead_id AND l.converted_customer_id=:customer))";
        var rows=db.queryForList("SELECT f.*,m.display_name AS \"assigneeName\" FROM crm_followup f JOIN sys_tenant_member m ON m.tenant_id=f.tenant_id AND m.id=f.assignee_member_id WHERE f.tenant_id=:tenant AND "+where+" ORDER BY coalesce(f.occurred_at,f.due_at) DESC,f.id DESC LIMIT 200",x);
        var visible=new ArrayList<Map<String,Object>>();for(var raw:rows){var item=view(raw);try{followupAccess(a,(String)item.get("kind"),false);UUID associated=(UUID)item.get(switch((String)item.get("targetType")){case "LEAD"->"leadId";case "OPPORTUNITY"->"opportunityId";default->"customerId";});target(a,(String)item.get("targetType"),associated,false);boolean complete="PLAN".equals(item.get("kind"))&&"PENDING".equals(item.get("status"))&&a.businessAccess().tasks().write()&&a.businessAccess().activityWrite()&&(a.tenant().memberId().equals(item.get("assigneeMemberId"))||a.admin()||a.businessAccess().tasks().writeAll());if(complete){try{target(a,(String)item.get("targetType"),associated,true);}catch(ApiError denied){complete=false;}}item.put("canComplete",complete);visible.add(item);}catch(ApiError ignored){}}
        return visible;
    });}
}
