package cn.binbin.crm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ResourcePoolService {
    public record Version(@Min(0) long expectedVersion,@Size(max=300) String reason) {}
    public record Assignment(@NotNull UUID memberId,@Min(0) long expectedVersion,@Size(max=300) String reason) {}
    public record Item(@NotNull UUID id,@Min(0) long expectedVersion) {}
    public record Batch(@NotNull UUID memberId,@NotEmpty @Size(max=50) List<@Valid Item> items,@Size(max=300) String reason) {}
    public record Page(List<Map<String,Object>> items,int page,int pageSize,long total) {}

    private final NamedParameterJdbcTemplate db;
    private final TransactionTemplate tx;
    private final AuthService auth;
    private final SalesFoundationService foundation;
    private final boolean scheduled;
    public ResourcePoolService(NamedParameterJdbcTemplate db,TransactionTemplate tx,AuthService auth,SalesFoundationService foundation,
        @Value("${CRM_RESOURCE_SCHEDULE_ENABLED:false}") boolean scheduled){
        this.db=db;this.tx=tx;this.auth=auth;this.foundation=foundation;this.scheduled=scheduled;
    }
    private static Map<String,Object> p(Object... values){return SaasService.parameters(values);}
    private static String kind(String type){return switch(type){case "leads"->"LEAD";case "customers"->"CUSTOMER";default->throw ApiError.bad("资源类型无效");};}
    private static String table(String kind){return kind.equals("LEAD")?"crm_lead":"crm_customer";}
    private static boolean manager(AuthService.Actor a,String kind){return a.admin()||(kind.equals("LEAD")?a.businessAccess().leads().writeAll():a.access().writeAll());}
    private <T>T scoped(AuthService.Session session,String type,boolean write,boolean manage,Function<AuthService.Actor,T> fn){
        String kind=kind(type);
        return tx.execute(st->{
            if(write&&session.tenantId()!=null)db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",p("tenant",session.tenantId()),UUID.class);
            var a=auth.actor(session);
            if(a.supportGrantId()!=null)throw ApiError.forbidden("支持授权不能访问资源池");
            boolean read=kind.equals("LEAD")?a.businessAccess().leads().read():a.canReadCustomers();
            boolean canWrite=kind.equals("LEAD")?a.businessAccess().leads().write():a.canWriteCustomers();
            if(!read||write&&!canWrite)throw ApiError.forbidden("当前角色没有资源池操作权限");
            if(manage&&!manager(a,kind))throw ApiError.forbidden("仅主管或企业管理员可以派发资源");
            if(write&&!a.tenant().subscriptionEnd().isAfter(Instant.now()))throw ApiError.forbidden("订阅已到期，当前仅允许查看");
            db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",p("tenant",a.tenant().id().toString()),String.class);
            return fn.apply(a);
        });
    }
    public Page list(AuthService.Session s,String type,String mode,int page,int size,String q){
        if(page<1||page>100000||size<1||size>100||q.length()>100||!Set.of("pool","mine","team").contains(mode))throw ApiError.bad("筛选条件无效");
        String kind=kind(type);
        return scoped(s,type,false,mode.equals("team"),a->{
            String source=table(kind),name=kind.equals("LEAD")?"r.company_name":"r.customer_name";
            String state=mode.equals("pool")?"r.in_pool":mode.equals("mine")?"NOT r.in_pool AND r.owner_member_id=:member":"NOT r.in_pool";
            if(kind.equals("LEAD"))state+=" AND r.status NOT IN ('CONVERTED','UNQUALIFIED')";
            String from=" FROM "+source+" r JOIN sys_tenant_member m ON m.tenant_id=r.tenant_id AND m.id=r.owner_member_id WHERE r.tenant_id=:tenant AND "+state+
                " AND (:q='' OR strpos(lower("+name+"),lower(:q))>0)";
            var args=p("tenant",a.tenant().id(),"member",a.tenant().memberId(),"q",q.strip(),"limit",size,"offset",((long)page-1)*size);
            long total=db.queryForObject("SELECT count(*)"+from,args,Long.class);
            String select="SELECT r.id,"+name+" AS name,r.source_code AS source,r.region,r.owner_member_id AS \"ownerMemberId\",m.display_name AS \"ownerName\",r.in_pool AS \"inPool\",r.pool_entered_at AS \"poolEnteredAt\",r.updated_at AS \"updatedAt\",r.version"+(kind.equals("LEAD")?",r.status":"");
            var rows=db.queryForList(select+from+" ORDER BY "+(mode.equals("pool")?"r.pool_entered_at":"r.updated_at")+" DESC,r.id LIMIT :limit OFFSET :offset",args);
            return new Page(rows,page,size,total);
        });
    }
    public List<Map<String,Object>> recipients(AuthService.Session s,String type){return scoped(s,type,false,true,a->{
        String permission=kind(type).equals("LEAD")?"lead_can_write":"can_write";
        return db.queryForList("SELECT m.id,m.display_name AS name,m.department_id AS \"departmentId\" FROM sys_tenant_member m JOIN iam_user u ON u.id=m.user_id WHERE m.tenant_id=:tenant AND m.status='ACTIVE' AND u.status='ACTIVE' AND EXISTS(SELECT 1 FROM sys_member_role mr JOIN sys_role_policy rp ON rp.tenant_id=mr.tenant_id AND rp.role_code=mr.role_code WHERE mr.tenant_id=m.tenant_id AND mr.member_id=m.id AND rp."+permission+") ORDER BY m.display_name,m.id",p("tenant",a.tenant().id()));
    });}
    private Map<String,Object> locked(UUID tenant,String kind,UUID id){
        var rows=db.queryForList("SELECT id,owner_member_id,in_pool,version"+(kind.equals("LEAD")?",status":"")+" FROM "+table(kind)+" WHERE tenant_id=:tenant AND id=:id FOR UPDATE",p("tenant",tenant,"id",id));
        if(rows.isEmpty())throw ApiError.missing();return rows.getFirst();
    }
    private void current(Map<String,Object> row,long version){if(((Number)row.get("version")).longValue()!=version)throw ApiError.conflict("资源已被其他人处理，请刷新后重试");}
    private void recipient(UUID tenant,String kind,UUID member){
        String permission=kind.equals("LEAD")?"lead_can_write":"can_write";
        int count=db.queryForObject("SELECT count(*) FROM sys_tenant_member m JOIN iam_user u ON u.id=m.user_id WHERE m.tenant_id=:tenant AND m.id=:member AND m.status='ACTIVE' AND u.status='ACTIVE' AND EXISTS(SELECT 1 FROM sys_member_role mr JOIN sys_role_policy rp ON rp.tenant_id=mr.tenant_id AND rp.role_code=mr.role_code WHERE mr.tenant_id=m.tenant_id AND mr.member_id=m.id AND rp."+permission+")",p("tenant",tenant,"member",member),Integer.class);
        if(count!=1)throw ApiError.bad("接收成员未启用或缺少该资源写入权限");
    }
    private boolean protectedCustomer(UUID tenant,UUID id){
        var args=p("tenant",tenant,"id",id);
        return db.queryForObject("SELECT EXISTS(SELECT 1 FROM crm_opportunity WHERE tenant_id=:tenant AND customer_id=:id AND stage NOT IN('WON','LOST')) OR EXISTS(SELECT 1 FROM crm_contract WHERE tenant_id=:tenant AND customer_id=:id AND status IN('SIGNED','ACTIVE')) OR EXISTS(SELECT 1 FROM crm_ticket WHERE tenant_id=:tenant AND customer_id=:id AND status IN('NEW','IN_PROGRESS','WAITING_CUSTOMER')) OR EXISTS(SELECT 1 FROM crm_task WHERE tenant_id=:tenant AND customer_id=:id AND status='PENDING')",args,Boolean.class);
    }
    private void movement(UUID tenant,String kind,UUID id,String action,UUID from,UUID to,UUID actor,String reason,long version,UUID trace){
        db.update("INSERT INTO crm_resource_movement(id,tenant_id,resource_type,resource_id,action,from_member_id,to_member_id,actor_member_id,reason,resource_version) VALUES(:id,:tenant,:type,:resource,:action,:from,:to,:actor,:reason,:version)",p("id",UUID.randomUUID(),"tenant",tenant,"type",kind,"resource",id,"action",action,"from",from,"to",to,"actor",actor,"reason",reason==null?"":reason.strip(),"version",version));
        if(actor!=null)db.update("INSERT INTO sys_audit_log(id,tenant_id,actor_member_id,action,entity_type,entity_id,business_version,trace_id) VALUES(:id,:tenant,:actor,:action,:type,:resource,:version,:trace)",p("id",UUID.randomUUID(),"tenant",tenant,"actor",actor,"action","RESOURCE_"+action,"type",kind,"resource",id,"version",version,"trace",trace==null?UUID.randomUUID():trace));
    }
    private void move(AuthService.Actor a,String kind,UUID id,Map<String,Object> old,UUID to,String action,String reason,UUID trace){
        UUID tenant=a.tenant().id(),before=(UUID)old.get("owner_member_id");boolean wasPool=(boolean)old.get("in_pool");
        if(to==null){
            if(wasPool)throw ApiError.conflict("该资源已经在资源池中");
            if(kind.equals("CUSTOMER")&&protectedCustomer(tenant,id))throw ApiError.conflict("客户仍有进行中的商机、合同、工单或待办，不能退回资源池");
        }else{
            recipient(tenant,kind,to);
            if(!wasPool&&before.equals(to))throw ApiError.conflict("该成员已是当前负责人");
            if(!kind.equals("LEAD")||!"UNQUALIFIED".equals(old.get("status")))foundation.checkCapacity(a,to,kind);
        }
        update(tenant,kind,id,to);
        movement(tenant,kind,id,action,wasPool?null:before,to,a.tenant().memberId(),reason,((Number)old.get("version")).longValue()+1,trace);
    }
    private void update(UUID tenant,String kind,UUID id,UUID to){
        if(to==null){
            db.update("UPDATE "+table(kind)+" SET in_pool=true,pool_entered_at=now(),version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id",p("tenant",tenant,"id",id));
            if(kind.equals("CUSTOMER"))db.update("DELETE FROM crm_customer_share WHERE tenant_id=:tenant AND customer_id=:id",p("tenant",tenant,"id",id));
        }else db.update("UPDATE "+table(kind)+" SET owner_member_id=:owner,in_pool=false,pool_entered_at=NULL,last_assigned_at=now(),version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id",p("tenant",tenant,"id",id,"owner",to));
    }
    public void claim(AuthService.Session s,String type,UUID id,Version input,UUID trace){scoped(s,type,true,false,a->{
        String kind=kind(type);var row=locked(a.tenant().id(),kind,id);current(row,input.expectedVersion());
        if(!(boolean)row.get("in_pool"))throw ApiError.conflict("资源已被领取");
        move(a,kind,id,row,a.tenant().memberId(),"CLAIM",input.reason(),trace);return null;
    });}
    public void release(AuthService.Session s,String type,UUID id,Version input,UUID trace){scoped(s,type,true,false,a->{
        String kind=kind(type);var row=locked(a.tenant().id(),kind,id);current(row,input.expectedVersion());
        if(!manager(a,kind)&&!a.tenant().memberId().equals(row.get("owner_member_id")))throw ApiError.forbidden("只能退回自己负责的资源");
        if(kind.equals("LEAD")&&Set.of("CONVERTED","UNQUALIFIED").contains(row.get("status")))throw ApiError.conflict("已转换或不合格线索不能退回");
        move(a,kind,id,row,null,"RETURN",input.reason(),trace);return null;
    });}
    public void assign(AuthService.Session s,String type,UUID id,Assignment input,UUID trace){scoped(s,type,true,true,a->{
        String kind=kind(type);var row=locked(a.tenant().id(),kind,id);current(row,input.expectedVersion());
        if(kind.equals("LEAD")&&Set.of("CONVERTED","UNQUALIFIED").contains(row.get("status")))throw ApiError.conflict("该线索不能派发");
        move(a,kind,id,row,input.memberId(),"ASSIGN",input.reason(),trace);return null;
    });}
    public int batch(AuthService.Session s,String type,Batch input,UUID trace){return scoped(s,type,true,true,a->{
        String kind=kind(type);if(input.items().stream().map(Item::id).distinct().count()!=input.items().size())throw ApiError.bad("批量记录不能重复");
        recipient(a.tenant().id(),kind,input.memberId());
        var sorted=input.items().stream().sorted(Comparator.comparing(Item::id)).toList();
        for(var item:sorted){var row=locked(a.tenant().id(),kind,item.id());current(row,item.expectedVersion());
            if(kind.equals("LEAD")&&Set.of("CONVERTED","UNQUALIFIED").contains(row.get("status")))throw ApiError.conflict("批量记录含不可派发线索");
            move(a,kind,item.id(),row,input.memberId(),"BATCH_ASSIGN",input.reason(),trace);
        }return sorted.size();
    });}
    public List<Map<String,Object>> history(AuthService.Session s,String type,UUID id){return scoped(s,type,false,false,a->{
        String kind=kind(type);var rows=db.queryForList("SELECT owner_member_id,in_pool FROM "+table(kind)+" WHERE tenant_id=:tenant AND id=:id",p("tenant",a.tenant().id(),"id",id));
        if(rows.isEmpty())throw ApiError.missing();var row=rows.getFirst();
        if(!(boolean)row.get("in_pool")&&!manager(a,kind)&&!a.tenant().memberId().equals(row.get("owner_member_id")))throw ApiError.forbidden("无权查看该资源流转记录");
        return db.queryForList("SELECT x.id,x.action,x.reason,x.from_member_id AS \"fromMemberId\",f.display_name AS \"fromName\",x.to_member_id AS \"toMemberId\",t.display_name AS \"toName\",a.display_name AS \"actorName\",x.resource_version AS \"resourceVersion\",x.created_at AS \"createdAt\" FROM crm_resource_movement x LEFT JOIN sys_tenant_member f ON f.tenant_id=x.tenant_id AND f.id=x.from_member_id LEFT JOIN sys_tenant_member t ON t.tenant_id=x.tenant_id AND t.id=x.to_member_id LEFT JOIN sys_tenant_member a ON a.tenant_id=x.tenant_id AND a.id=x.actor_member_id WHERE x.tenant_id=:tenant AND x.resource_type=:type AND x.resource_id=:id ORDER BY x.created_at DESC,x.id DESC LIMIT 100",p("tenant",a.tenant().id(),"type",kind,"id",id));
    });}
    void directTransfer(AuthService.Actor a,String kind,UUID id,UUID from,UUID to,long version,UUID trace){
        movement(a.tenant().id(),kind,id,"DIRECT_TRANSFER",from,to,a.tenant().memberId(),"资料负责人变更",version,trace);
    }
    // Called inside the same tenant-locked transaction as member deactivation. A failed transfer rolls back deactivation.
    void offboard(AuthService.Actor a,UUID member,UUID successor,UUID trace){
        if(member.equals(successor))throw ApiError.bad("接收成员不能是停用成员本人");
        for(String kind:List.of("LEAD","CUSTOMER")){
            var ids=db.queryForList("SELECT id FROM "+table(kind)+" WHERE tenant_id=:tenant AND owner_member_id=:member AND NOT in_pool"+(kind.equals("LEAD")?" AND status<>'CONVERTED'":"")+" ORDER BY id",p("tenant",a.tenant().id(),"member",member),UUID.class);
            if(!ids.isEmpty())recipient(a.tenant().id(),kind,successor);
            for(UUID id:ids){var row=locked(a.tenant().id(),kind,id);if(!(boolean)row.get("in_pool")&&member.equals(row.get("owner_member_id")))move(a,kind,id,row,successor,"OFFBOARD_TRANSFER","成员停用自动转交",trace);}
        }
    }
    private int recycle(UUID tenant){
        int count=0;
        for(String kind:List.of("LEAD","CUSTOMER")){
            String source=table(kind),stale=kind.equals("LEAD")?"p.lead_stale_days":"p.customer_stale_days";
            String age=kind.equals("LEAD")?"GREATEST(r.updated_at,r.last_assigned_at)":"GREATEST(r.updated_at,r.last_assigned_at,coalesce((SELECT max(a.occurred_at) FROM crm_activity a WHERE a.tenant_id=r.tenant_id AND a.customer_id=r.id),r.created_at))";
            String ready=kind.equals("LEAD")?" AND r.status NOT IN('CONVERTED','UNQUALIFIED')":" AND NOT EXISTS(SELECT 1 FROM crm_opportunity o WHERE o.tenant_id=r.tenant_id AND o.customer_id=r.id AND o.stage NOT IN('WON','LOST')) AND NOT EXISTS(SELECT 1 FROM crm_contract c WHERE c.tenant_id=r.tenant_id AND c.customer_id=r.id AND c.status IN('SIGNED','ACTIVE')) AND NOT EXISTS(SELECT 1 FROM crm_ticket t WHERE t.tenant_id=r.tenant_id AND t.customer_id=r.id AND t.status IN('NEW','IN_PROGRESS','WAITING_CUSTOMER')) AND NOT EXISTS(SELECT 1 FROM crm_task task WHERE task.tenant_id=r.tenant_id AND task.customer_id=r.id AND task.status='PENDING')";
            var ids=db.queryForList("SELECT r.id FROM "+source+" r JOIN crm_sales_resource_policy p ON p.tenant_id=r.tenant_id AND p.member_id=r.owner_member_id WHERE r.tenant_id=:tenant AND NOT r.in_pool"+ready+" AND "+age+"<now()-make_interval(days=>"+stale+") ORDER BY r.updated_at,r.id LIMIT 50 FOR UPDATE OF r SKIP LOCKED",p("tenant",tenant),UUID.class);
            for(UUID id:ids){var row=locked(tenant,kind,id);update(tenant,kind,id,null);movement(tenant,kind,id,"AUTO_RECYCLE",(UUID)row.get("owner_member_id"),null,null,"超过未跟进期限",((Number)row.get("version")).longValue()+1,null);count++;}
        }return count;
    }
    public int recover(AuthService.Session s){return tx.execute(st->{
        if(s.tenantId()!=null)db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",p("tenant",s.tenantId()),UUID.class);
        var a=auth.actor(s);if(!a.admin()||a.supportGrantId()!=null)throw ApiError.forbidden("需要企业管理员权限");
        db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",p("tenant",a.tenant().id().toString()),String.class);
        if(!a.tenant().subscriptionEnd().isAfter(Instant.now()))throw ApiError.forbidden("订阅已到期");return recycle(a.tenant().id());
    });}
    @Scheduled(initialDelay=120000,fixedDelay=3600000)
    public void scheduled(){if(!scheduled)return;
        UUID job=UUID.randomUUID();db.update("INSERT INTO sys_job_run(id,job_name,status) VALUES(:id,'P5_RESOURCE_RECOVERY','RUNNING')",p("id",job));
        int processed=0,failed=0;
        for(UUID tenant:db.queryForList("SELECT id FROM sys_tenant WHERE status='ACTIVE' AND subscription_end>now() ORDER BY id",Map.of(),UUID.class)){
            try{processed+=tx.execute(st->{db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",p("tenant",tenant),UUID.class);db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",p("tenant",tenant.toString()),String.class);return recycle(tenant);});}catch(Exception ignored){failed++;}
        }
        db.update("UPDATE sys_job_run SET status=:status,finished_at=now(),processed_count=:count,error_code=:error WHERE id=:id",p("id",job,"status",failed==0?"SUCCESS":"FAILED","count",processed,"error",failed==0?null:"P5_TENANT_RECOVERY_FAILED"));
    }
}
