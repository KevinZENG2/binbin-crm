package cn.binbin.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerService {
    public record Create(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=80) String industry,@NotNull @Pattern(regexp="[ABC]") String level,@NotBlank @Size(max=80) String source,@Size(max=100) String region,@Size(max=18) String creditCode,@NotNull UUID ownerMemberId,@Size(max=4000) String description) {}
    public record Edit(@Min(0) long expectedVersion,@NotBlank @Size(max=200) String name,@NotBlank @Size(max=80) String industry,@NotNull @Pattern(regexp="[ABC]") String level,@NotBlank @Size(max=80) String source,@Size(max=100) String region,@Size(max=4000) String description,@Size(max=18) String creditCode) {}
    public record Customer(UUID id,String name,String industry,String level,String source,String region,String creditCode,UUID ownerMemberId,String ownerName,String description,long version,Instant updatedAt,boolean canWrite,boolean creditReadable,boolean creditWritable,boolean descriptionReadable,boolean descriptionWritable,boolean canShare,boolean canTransfer) {}
    public record Sharing(@NotNull UUID memberId,boolean canWrite,@NotNull Instant expiresAt) {}
    public record Transfer(@NotNull UUID ownerMemberId,@Min(0) long expectedVersion) {}
    public record Page(List<Customer> items,int page,int pageSize,long total) {}
    public record Option(UUID id,String name) {}
    public record Options(List<Option> items,int page,int pageSize,long total) {}
    private final NamedParameterJdbcTemplate db;
    private final TransactionTemplate tx;
    private final AuthService auth;
    private final ObjectMapper json;
    private final SalesFoundationService foundation;
    private final ResourcePoolService resourcePools;
    public CustomerService(NamedParameterJdbcTemplate db,TransactionTemplate tx,AuthService auth,ObjectMapper json,SalesFoundationService foundation,ResourcePoolService resourcePools) { this.db=db;this.tx=tx;this.auth=auth;this.json=json;this.foundation=foundation;this.resourcePools=resourcePools; }
    private <T> T scoped(AuthService.Session session,Function<AuthService.Actor,T> fn) {
        return scoped(session,fn,false);
    }
    private <T> T scoped(AuthService.Session session,Function<AuthService.Actor,T> fn,boolean write) {
        return tx.execute(status -> {
            if(write&&session.tenantId()!=null)db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",Map.of("tenant",session.tenantId()),UUID.class);
            var actor=auth.actor(session);if(!actor.canReadCustomers())throw ApiError.forbidden("当前角色没有客户读取权限");
            db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",actor.tenant().id().toString()),String.class);
            return fn.apply(actor);
        });
    }
    Map<String,Object> scope(AuthService.Actor a) { var p=new HashMap<String,Object>();p.put("tenant",a.tenant().id());p.put("member",a.tenant().memberId());p.put("all",a.access().readAll());p.put("readDepartment",a.access().readDepartment());p.put("writeDepartment",a.access().writeDepartment());p.put("department",a.departmentId());p.put("manager",a.access().writeAll());p.put("writer",a.canWriteCustomers() && a.tenant().subscriptionEnd().isAfter(Instant.now()));return p; }
    private static final String DEPARTMENT="EXISTS(SELECT 1 FROM sys_tenant_member dm WHERE dm.tenant_id=c.tenant_id AND dm.id=c.owner_member_id AND dm.department_id=:department)";
    static final String READ="c.tenant_id=:tenant AND NOT c.in_pool AND c.merged_into_customer_id IS NULL AND (:all OR (:readDepartment AND "+DEPARTMENT+") OR c.owner_member_id=:member OR EXISTS(SELECT 1 FROM crm_customer_share s WHERE s.tenant_id=c.tenant_id AND s.customer_id=c.id AND s.member_id=:member AND (s.expires_at IS NULL OR s.expires_at>now())))";
    static final String WRITE="(:writer AND (:manager OR (:writeDepartment AND "+DEPARTMENT+") OR c.owner_member_id=:member OR EXISTS(SELECT 1 FROM crm_customer_share s WHERE s.tenant_id=c.tenant_id AND s.customer_id=c.id AND s.member_id=:member AND s.can_write AND (s.expires_at IS NULL OR s.expires_at>now()))))";
    private static final String SELECT="SELECT c.*,m.display_name AS owner_name,m.department_id AS owner_department,"+WRITE+" AS can_write FROM crm_customer c JOIN sys_tenant_member m ON m.tenant_id=c.tenant_id AND m.id=c.owner_member_id WHERE ";
    private Customer row(ResultSet r,AuthService.Actor a) throws SQLException { var p=a.access();boolean write=r.getBoolean("can_write"),manager=p.writeAll()||(p.writeDepartment()&&a.departmentId()!=null&&a.departmentId().equals(r.getObject("owner_department",UUID.class)));UUID owner=r.getObject("owner_member_id",UUID.class);return new Customer(r.getObject("id",UUID.class),r.getString("customer_name"),r.getString("industry"),r.getString("level_code"),r.getString("source_code"),r.getString("region"),p.creditRead()?r.getString("credit_code"):null,owner,r.getString("owner_name"),p.descriptionRead()?r.getString("description"):null,r.getLong("version"),r.getTimestamp("updated_at").toInstant(),write,p.creditRead(),write&&p.creditWrite(),p.descriptionRead(),write&&p.descriptionWrite(),write&&p.share()&&(manager||owner.equals(a.tenant().memberId())),write&&manager); }
    Customer get(AuthService.Actor a,UUID id) { var p=scope(a);p.put("id",id);var rows=db.query(SELECT+READ+" AND c.id=:id",p,(r,i)->row(r,a));if(rows.isEmpty())throw ApiError.missing();return rows.getFirst(); }
    public Page list(AuthService.Session s,int page,int size,String query) {
        if(page<1 || page>100000 || size<1 || size>100 || query.length()>100)throw ApiError.bad("分页或搜索条件无效");
        return scoped(s,a -> { var p=scope(a);p.put("query",query.strip());p.put("offset",((long)page-1)*size);p.put("limit",size);
            String where=READ+" AND (:query='' OR strpos(lower(c.customer_name),lower(:query))>0)";
            long total=Objects.requireNonNull(db.queryForObject("SELECT count(*) FROM crm_customer c WHERE "+where,p,Long.class));
            return new Page(db.query(SELECT+where+" ORDER BY c.updated_at DESC,c.id LIMIT :limit OFFSET :offset",p,(r,i)->row(r,a)),page,size,total);
        });
    }
    public Options options(AuthService.Session s,int page,int size,String query,boolean writable){
        if(page<1||page>100000||size<1||size>100||query.length()>100)throw ApiError.bad("分页或搜索条件无效");
        return scoped(s,a->{var p=scope(a);p.put("query",query.strip());p.put("limit",size);p.put("offset",((long)page-1)*size);
            String where=READ+(writable?" AND "+WRITE:"")+" AND (:query='' OR strpos(lower(c.customer_name),lower(:query))>0)";
            long total=Objects.requireNonNull(db.queryForObject("SELECT count(*) FROM crm_customer c WHERE "+where,p,Long.class));
            return new Options(db.query("SELECT c.id,c.customer_name FROM crm_customer c WHERE "+where+" ORDER BY c.customer_name,c.id LIMIT :limit OFFSET :offset",p,(r,i)->new Option(r.getObject("id",UUID.class),r.getString("customer_name"))),page,size,total);
        });
    }
    public Customer detail(AuthService.Session s,UUID id) { return scoped(s,a -> get(a,id)); }
    public List<Map<String,Object>> owners(AuthService.Session s) {
        return scoped(s,a -> {
            var p=scope(a);
            if(a.supportGrantId()!=null)return List.of();
            return db.queryForList("SELECT m.id,m.display_name AS name FROM sys_tenant_member m JOIN iam_user u ON u.id=m.user_id WHERE m.tenant_id=:tenant AND m.status='ACTIVE' AND u.status='ACTIVE' AND (:manager OR (:writeDepartment AND m.department_id=:department) OR m.id=:member) AND EXISTS(SELECT 1 FROM sys_member_role r JOIN sys_role_policy p ON p.tenant_id=r.tenant_id AND p.role_code=r.role_code WHERE r.tenant_id=m.tenant_id AND r.member_id=m.id AND p.can_write) ORDER BY m.display_name",p);
        });
    }
    private static String text(String value) { return value==null?"":value.strip(); }
    public static String credit(String value) {
        String code=text(value).toUpperCase(Locale.ROOT);if(code.isEmpty())return null;
        String alphabet="0123456789ABCDEFGHJKLMNPQRTUWXY";int[] weights={1,3,9,27,19,26,16,17,20,29,25,13,8,24,10,30,28};int sum=0;
        if(code.length()!=18)throw ApiError.bad("统一社会信用代码需为有效的18位编码");
        for(int i=0;i<17;i++){int n=alphabet.indexOf(code.charAt(i));if(n<0)throw ApiError.bad("统一社会信用代码格式无效");sum+=n*weights[i];}
        if(alphabet.charAt((31-sum%31)%31)!=code.charAt(17))throw ApiError.bad("统一社会信用代码校验失败");return code;
    }
    private String hash(Create c) { try { return Crypto.hash(json.writeValueAsString(c)); } catch(Exception e){throw new IllegalStateException(e);} }
    public Customer create(AuthService.Session s,Create raw,String key,UUID trace) {
        if(!key.matches("[A-Za-z0-9_.:-]{1,100}"))throw ApiError.bad("请求编号格式无效");
        Create c=new Create(text(raw.name()),text(raw.industry()),raw.level(),text(raw.source()),text(raw.region()),credit(raw.creditCode()),raw.ownerMemberId(),text(raw.description()));
        String hash=hash(c);
        return scoped(s,a -> {
            a.requireWrite();var p=scope(a);p.put("key",key);p.put("hash",hash);
            db.update("INSERT INTO sys_idempotency_request(tenant_id,member_id,operation,request_key,request_hash) VALUES(:tenant,:member,'customer.create',:key,:hash) ON CONFLICT DO NOTHING",p);
            var prior=db.queryForMap("SELECT request_hash,result_id FROM sys_idempotency_request WHERE tenant_id=:tenant AND member_id=:member AND operation='customer.create' AND request_key=:key FOR UPDATE",p);
            if(!hash.equals(prior.get("request_hash")))throw ApiError.conflict("同一请求编号不能提交不同内容");
            if(prior.get("result_id")!=null)return get(a,(UUID)prior.get("result_id"));
            quota(a);
            if(c.creditCode()!=null&&!a.access().creditWrite())throw ApiError.forbidden("无权填写信用代码字段");
            if(!c.description().isEmpty()&&!a.access().descriptionWrite())throw ApiError.forbidden("无权填写客户备注字段");
            p.put("owner",c.ownerMemberId());
            int owner=validOwner(p);
            if(owner!=1)throw ApiError.bad("客户负责人无效或超出权限");
            foundation.checkCapacity(a,c.ownerMemberId(),"CUSTOMER");
            UUID id=UUID.randomUUID();p.put("id",id);p.put("name",c.name());p.put("industry",c.industry());p.put("level",c.level());p.put("source",c.source());p.put("region",c.region());p.put("credit",c.creditCode());p.put("description",c.description());
            db.update("INSERT INTO crm_customer(id,tenant_id,customer_name,industry,level_code,source_code,region,credit_code,owner_member_id,description) VALUES(:id,:tenant,:name,:industry,:level,:source,:region,:credit,:owner,:description)",p);
            db.update("UPDATE sys_idempotency_request SET result_id=:id WHERE tenant_id=:tenant AND member_id=:member AND operation='customer.create' AND request_key=:key",p);
            audit(a,"CUSTOMER_CREATED",id,0,trace);return get(a,id);
        },true);
    }
    public Customer edit(AuthService.Session s,UUID id,Edit c,UUID trace) {
        return scoped(s,a -> { a.requireWrite();Customer existing=get(a,id);if(!existing.canWrite())throw ApiError.forbidden("无权修改该客户");
            if(c.description()!=null&&!a.access().descriptionWrite())throw ApiError.forbidden("无权修改客户备注字段");
            if(c.creditCode()!=null&&!a.access().creditWrite())throw ApiError.forbidden("无权修改信用代码字段");
            var p=scope(a);p.put("id",id);p.put("version",c.expectedVersion());p.put("name",text(c.name()));p.put("industry",text(c.industry()));p.put("level",c.level());p.put("source",text(c.source()));p.put("region",text(c.region()));p.put("description",c.description()==null?null:text(c.description()));p.put("credit",c.creditCode()==null?null:credit(c.creditCode()));p.put("changeCredit",c.creditCode()!=null);
            int count=db.update("UPDATE crm_customer c SET customer_name=:name,industry=:industry,level_code=:level,source_code=:source,region=:region,description=coalesce(:description,c.description),credit_code=CASE WHEN :changeCredit THEN :credit ELSE c.credit_code END,version=version+1,updated_at=now() WHERE c.id=:id AND "+READ+" AND "+WRITE+" AND c.version=:version",p);
            if(count!=1)throw ApiError.conflict("记录已更新，请刷新后再保存");audit(a,"CUSTOMER_UPDATED",id,c.expectedVersion()+1,trace);return get(a,id);
        },true);
    }
    private int validOwner(Map<String,Object> p){return Objects.requireNonNull(db.queryForObject("SELECT count(*) FROM sys_tenant_member m JOIN iam_user u ON u.id=m.user_id WHERE m.tenant_id=:tenant AND m.id=:owner AND m.status='ACTIVE' AND u.status='ACTIVE' AND (:manager OR (:writeDepartment AND m.department_id=:department) OR m.id=:member) AND EXISTS(SELECT 1 FROM sys_member_role r JOIN sys_role_policy rp ON rp.tenant_id=r.tenant_id AND rp.role_code=r.role_code WHERE r.tenant_id=m.tenant_id AND r.member_id=m.id AND rp.can_write)",p,Integer.class));}
    private void quota(AuthService.Actor a){var q=db.queryForMap("SELECT t.customer_count,p.customer_limit FROM sys_tenant t JOIN sys_plan p ON p.id=t.plan_id WHERE t.id=:tenant",Map.of("tenant",a.tenant().id()));if(((Number)q.get("customer_count")).longValue()>=((Number)q.get("customer_limit")).longValue())throw new ApiError(org.springframework.http.HttpStatus.CONFLICT,"CUSTOMER_QUOTA_EXCEEDED","客户数量已达到套餐额度");}
    public List<Map<String,Object>> shares(AuthService.Session s,UUID id){return scoped(s,a->{var c=get(a,id);if(!c.canShare())throw ApiError.forbidden("无权管理客户共享");return db.queryForList("SELECT sh.member_id AS \"memberId\",m.display_name AS name,sh.can_write AS \"canWrite\",sh.expires_at AS \"expiresAt\" FROM crm_customer_share sh JOIN sys_tenant_member m ON m.id=sh.member_id AND m.tenant_id=sh.tenant_id WHERE sh.tenant_id=:tenant AND sh.customer_id=:id",Map.of("tenant",a.tenant().id(),"id",id));});}
    public List<Map<String,Object>> shareRecipients(AuthService.Session s,UUID id){return scoped(s,a->{if(!get(a,id).canShare())throw ApiError.forbidden("无权管理客户共享");return db.queryForList("SELECT m.id,m.display_name AS name FROM sys_tenant_member m JOIN iam_user u ON u.id=m.user_id WHERE m.tenant_id=:tenant AND m.status='ACTIVE' AND u.status='ACTIVE' AND EXISTS(SELECT 1 FROM sys_member_role r JOIN sys_role_policy p ON p.tenant_id=r.tenant_id AND p.role_code=r.role_code WHERE r.tenant_id=m.tenant_id AND r.member_id=m.id AND p.can_read) ORDER BY m.display_name",Map.of("tenant",a.tenant().id()));});}
    public Customer share(AuthService.Session s,UUID id,Sharing input,UUID trace){return scoped(s,a->{a.requireWrite();var c=get(a,id);if(!c.canShare())throw ApiError.forbidden("无权管理客户共享");if(!input.expiresAt().isAfter(Instant.now())||input.expiresAt().isAfter(Instant.now().plusSeconds(90*86400L)))throw ApiError.bad("共享期限需在未来90天以内");if(c.ownerMemberId().equals(input.memberId()))throw ApiError.bad("负责人无需重复共享");var p=scope(a);p.put("id",id);p.put("recipient",input.memberId());p.put("write",input.canWrite());p.put("end",java.sql.Timestamp.from(input.expiresAt()));int count=db.queryForObject("SELECT count(*) FROM sys_tenant_member m JOIN iam_user u ON u.id=m.user_id WHERE m.tenant_id=:tenant AND m.id=:recipient AND m.status='ACTIVE' AND u.status='ACTIVE' AND EXISTS(SELECT 1 FROM sys_member_role r JOIN sys_role_policy rp ON rp.tenant_id=r.tenant_id AND rp.role_code=r.role_code WHERE r.tenant_id=m.tenant_id AND r.member_id=m.id AND rp.can_read AND (NOT :write OR rp.can_write))",p,Integer.class);if(count!=1)throw ApiError.bad("共享成员无效或缺少对应权限");db.update("INSERT INTO crm_customer_share(tenant_id,customer_id,member_id,can_write,expires_at) VALUES(:tenant,:id,:recipient,:write,:end) ON CONFLICT(tenant_id,customer_id,member_id) DO UPDATE SET can_write=excluded.can_write,expires_at=excluded.expires_at",p);audit(a,"CUSTOMER_SHARED",id,c.version(),trace);return get(a,id);},true);}
    public void revokeShare(AuthService.Session s,UUID id,UUID recipient,UUID trace){scoped(s,a->{a.requireWrite();var c=get(a,id);if(!c.canShare())throw ApiError.forbidden("无权管理客户共享");db.update("DELETE FROM crm_customer_share WHERE tenant_id=:tenant AND customer_id=:id AND member_id=:member",Map.of("tenant",a.tenant().id(),"id",id,"member",recipient));audit(a,"CUSTOMER_SHARE_REVOKED",id,c.version(),trace);return null;},true);}
    public Customer transfer(AuthService.Session s,UUID id,Transfer input,UUID trace){return scoped(s,a->{a.requireWrite();var c=get(a,id);if(!c.canTransfer())throw ApiError.forbidden("无权转交该客户");var p=scope(a);p.put("id",id);p.put("owner",input.ownerMemberId());p.put("version",input.expectedVersion());if(validOwner(p)!=1)throw ApiError.bad("新负责人无效或超出权限");if(!c.ownerMemberId().equals(input.ownerMemberId()))foundation.checkCapacity(a,input.ownerMemberId(),"CUSTOMER");int changed=db.update("UPDATE crm_customer c SET owner_member_id=:owner,version=version+1,updated_at=now() WHERE c.tenant_id=:tenant AND c.id=:id AND c.version=:version AND "+WRITE,p);if(changed!=1)throw ApiError.conflict("客户已被修改，请刷新后转交");if(!c.ownerMemberId().equals(input.ownerMemberId()))resourcePools.directTransfer(a,"CUSTOMER",id,c.ownerMemberId(),input.ownerMemberId(),input.expectedVersion()+1,trace);audit(a,"CUSTOMER_TRANSFERRED",id,input.expectedVersion()+1,trace);return get(a,id);},true);}
    public void logSupport(AuthService.Session s,String action,UUID resource,UUID trace){if(s.supportGrantId()==null)return;scoped(s,a->{var p=scope(a);p.put("id",UUID.randomUUID());p.put("grant",a.supportGrantId());p.put("operator",s.userId());p.put("action",action);p.put("resource",resource);p.put("trace",trace);db.update("INSERT INTO sys_support_access_log(id,tenant_id,grant_id,operator_user_id,action,resource_id,trace_id) VALUES(:id,:tenant,:grant,:operator,:action,:resource,:trace)",p);return null;});}
    private void audit(AuthService.Actor a,String action,UUID id,long version,UUID trace) {
        db.update("INSERT INTO sys_audit_log(id,tenant_id,actor_member_id,action,entity_type,entity_id,business_version,trace_id) VALUES(:id,:tenant,:member,:action,'CUSTOMER',:entity,:version,:trace)",Map.of("id",UUID.randomUUID(),"tenant",a.tenant().id(),"member",a.tenant().memberId(),"action",action,"entity",id,"version",version,"trace",trace));
    }
}
