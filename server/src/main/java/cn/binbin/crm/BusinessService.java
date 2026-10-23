package cn.binbin.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BusinessService {
    public record ContactCreate(@NotNull UUID customerId,@NotBlank @Size(max=100) String name,@Size(max=100) String jobTitle,
        @Size(max=40) @Pattern(regexp="[0-9+().\\-\\s]*") String phone,@Email @Size(max=254) String email,boolean primary,@Size(max=4000) String description,
        @Pattern(regexp="DECISION_MAKER|INFLUENCER|USER|GATEKEEPER|OTHER") String decisionRole,@Pattern(regexp="HIGH|MEDIUM|LOW|UNKNOWN") String influence,
        @Pattern(regexp="PHONE|EMAIL|WECHAT|IN_PERSON|ANY") String contactPreference) {
        public ContactCreate(UUID customerId,String name,String jobTitle,String phone,String email,boolean primary,String description){this(customerId,name,jobTitle,phone,email,primary,description,"OTHER","UNKNOWN","ANY");}
    }
    public record ContactEdit(@NotNull @Min(0) Long expectedVersion,@NotNull UUID customerId,@NotBlank @Size(max=100) String name,@Size(max=100) String jobTitle,
        @Size(max=40) @Pattern(regexp="[0-9+().\\-\\s]*") String phone,@Email @Size(max=254) String email,boolean primary,@Size(max=4000) String description,
        @Pattern(regexp="DECISION_MAKER|INFLUENCER|USER|GATEKEEPER|OTHER") String decisionRole,@Pattern(regexp="HIGH|MEDIUM|LOW|UNKNOWN") String influence,
        @Pattern(regexp="PHONE|EMAIL|WECHAT|IN_PERSON|ANY") String contactPreference) {}
    public record Contact(UUID id,UUID customerId,String customerName,String name,String jobTitle,String phone,String email,boolean primary,String description,long version,Instant updatedAt,boolean canWrite,boolean canRelink,String decisionRole,String influence,String contactPreference) {}
    public record LeadCreate(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=200) String companyName,@NotBlank @Size(max=80) String industry,
        @NotBlank @Size(max=80) String source,@Size(max=100) String region,@Size(max=100) String jobTitle,@Size(max=40) @Pattern(regexp="[0-9+().\\-\\s]*") String phone,
        @Email @Size(max=254) String email,@Size(max=4000) String description,@NotNull UUID ownerMemberId) {}
    public record LeadEdit(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=200) String companyName,@NotBlank @Size(max=80) String industry,
        @NotBlank @Size(max=80) String source,@Size(max=100) String region,@Size(max=100) String jobTitle,@Size(max=40) @Pattern(regexp="[0-9+().\\-\\s]*") String phone,
        @Email @Size(max=254) String email,@Size(max=4000) String description,@NotNull UUID ownerMemberId,@NotNull @Pattern(regexp="NEW|CONTACTED|QUALIFIED|UNQUALIFIED") String status) {}
    public record Convert(@NotNull @Min(0) Long expectedVersion,UUID targetCustomerId,@Size(max=200) String customerName,@Pattern(regexp="[ABC]") String customerLevel,UUID ownerMemberId,boolean primaryContact) {}
    public record Lead(UUID id,String name,String companyName,String industry,String source,String region,String jobTitle,String phone,String email,String description,
        String status,UUID ownerMemberId,String ownerName,long version,Instant updatedAt,UUID convertedCustomerId,String convertedCustomerName,UUID convertedContactId,Instant convertedAt,boolean canWrite,boolean canConvert) {}
    public record Page<T>(List<T> items,int page,int pageSize,long total) {}
    private record ConversionRequest(UUID leadId,Convert request) {}
    private final NamedParameterJdbcTemplate db;
    private final TransactionTemplate tx;
    private final AuthService auth;
    private final CustomerService customers;
    private final SalesFoundationService foundation;
    private final ResourcePoolService resourcePools;
    private final ObjectMapper json;
    public BusinessService(NamedParameterJdbcTemplate db,TransactionTemplate tx,AuthService auth,CustomerService customers,SalesFoundationService foundation,ResourcePoolService resourcePools,ObjectMapper json){this.db=db;this.tx=tx;this.auth=auth;this.customers=customers;this.foundation=foundation;this.resourcePools=resourcePools;this.json=json;}
    private static String text(String value){return value==null?"":value.strip();}
    private <T> T scoped(AuthService.Session s,boolean contacts,boolean write,Function<AuthService.Actor,T> fn){
        return tx.execute(status->{
            if(write&&s.tenantId()!=null)db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",Map.of("tenant",s.tenantId()),UUID.class);
            var a=auth.actor(s);
            if(a.supportGrantId()!=null)throw ApiError.forbidden("当前支持授权不包含联系人和线索");
            var access=a.businessAccess();
            if(!(contacts?access.contactRead():access.leads().read()))throw ApiError.forbidden("当前角色没有该模块读取权限");
            if(write){
                if(!(contacts?access.contactWrite():access.leads().write()))throw ApiError.forbidden("当前角色没有该模块写入权限");
                if(!a.tenant().subscriptionEnd().isAfter(Instant.now()))throw ApiError.forbidden("订阅已到期，当前仅允许查看");
            }
            db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",a.tenant().id().toString()),String.class);
            return fn.apply(a);
        });
    }
    Map<String,Object> params(AuthService.Actor a){
        var p=customers.scope(a);var lead=a.businessAccess().leads();
        p.put("contactWriter",a.businessAccess().contactWrite());p.put("contactReader",a.businessAccess().contactRead());p.put("customerReader",a.canReadCustomers());
        p.put("leadReadAll",lead.readAll());p.put("leadReadDepartment",lead.readDepartment());p.put("leadWriteAll",lead.writeAll());p.put("leadWriteDepartment",lead.writeDepartment());
        p.put("leadWriter",lead.write()&&a.tenant().subscriptionEnd().isAfter(Instant.now()));return p;
    }
    private static final String CONTACT_FROM=" FROM crm_contact ct JOIN crm_customer c ON c.tenant_id=ct.tenant_id AND c.id=ct.customer_id ";
    private static final String CONTACT_SELECT="SELECT ct.*,c.customer_name,(:contactWriter AND "+CustomerService.WRITE+") AS can_write,NOT EXISTS(SELECT 1 FROM crm_lead linked WHERE linked.tenant_id=ct.tenant_id AND linked.converted_contact_id=ct.id) AS can_relink"+CONTACT_FROM;
    private Contact contactRow(ResultSet r)throws SQLException{return new Contact(r.getObject("id",UUID.class),r.getObject("customer_id",UUID.class),r.getString("customer_name"),r.getString("full_name"),r.getString("job_title"),r.getString("phone"),r.getString("email"),r.getBoolean("is_primary"),r.getString("description"),r.getLong("version"),r.getTimestamp("updated_at").toInstant(),r.getBoolean("can_write"),r.getBoolean("can_relink"),r.getString("decision_role"),r.getString("influence"),r.getString("contact_preference"));}
    Contact contact(AuthService.Actor a,UUID id){var p=params(a);p.put("id",id);var found=db.query(CONTACT_SELECT+"WHERE "+CustomerService.READ+" AND ct.id=:id",p,(r,i)->contactRow(r));if(found.isEmpty())throw ApiError.missing();return found.getFirst();}
    private static void page(int page,int size,String q){if(page<1||page>100000||size<1||size>100||q.length()>100)throw ApiError.bad("分页或搜索条件无效");}
    public Page<Contact> contacts(AuthService.Session s,int page,int size,String q,UUID customerId){page(page,size,q);return scoped(s,true,false,a->{
        var p=params(a);p.put("customer",customerId);p.put("q",text(q));p.put("limit",size);p.put("offset",((long)page-1)*size);
        if(customerId!=null)customers.get(a,customerId);
        String where=CustomerService.READ+" AND (cast(:customer AS uuid) IS NULL OR ct.customer_id=:customer) AND (:q='' OR strpos(lower(ct.full_name),lower(:q))>0 OR strpos(lower(c.customer_name),lower(:q))>0 OR strpos(ct.phone,:q)>0 OR strpos(lower(ct.email),lower(:q))>0)";
        long total=Objects.requireNonNull(db.queryForObject("SELECT count(*)"+CONTACT_FROM+"WHERE "+where,p,Long.class));
        return new Page<>(db.query(CONTACT_SELECT+"WHERE "+where+" ORDER BY ct.updated_at DESC,ct.id LIMIT :limit OFFSET :offset",p,(r,i)->contactRow(r)),page,size,total);
    });}
    public Contact contact(AuthService.Session s,UUID id){return scoped(s,true,false,a->contact(a,id));}
    private void customerWrite(AuthService.Actor a,UUID id){if(!customers.get(a,id).canWrite())throw ApiError.forbidden("无权编辑该客户及其联系人");}
    private void primary(Map<String,Object> p){db.update("UPDATE crm_contact SET is_primary=false,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND customer_id=:customer AND is_primary AND id<>:id",p);}
    private Map<String,Object> contactParams(AuthService.Actor a,UUID id,ContactCreate c){var p=params(a);p.put("id",id);p.put("customer",c.customerId());p.put("name",text(c.name()));p.put("title",text(c.jobTitle()));p.put("phone",text(c.phone()));p.put("email",text(c.email()));p.put("primary",c.primary());p.put("description",text(c.description()));p.put("decision",c.decisionRole());p.put("influence",c.influence());p.put("preference",c.contactPreference());return p;}
    private ContactCreate normalize(ContactCreate c){return new ContactCreate(c.customerId(),text(c.name()),text(c.jobTitle()),text(c.phone()),text(c.email()),c.primary(),text(c.description()),c.decisionRole()==null?"OTHER":c.decisionRole(),c.influence()==null?"UNKNOWN":c.influence(),c.contactPreference()==null?"ANY":c.contactPreference());}
    private UUID insertContact(AuthService.Actor a,ContactCreate c,UUID trace){
        if(!a.businessAccess().contactWrite())throw ApiError.forbidden("当前角色没有联系人写入权限");customerWrite(a,c.customerId());
        UUID id=UUID.randomUUID();var p=contactParams(a,id,c);if(c.primary())primary(p);
        db.update("INSERT INTO crm_contact(id,tenant_id,customer_id,full_name,job_title,phone,email,is_primary,description,decision_role,influence,contact_preference) VALUES(:id,:tenant,:customer,:name,:title,:phone,:email,:primary,:description,:decision,:influence,:preference)",p);
        audit(a,"CONTACT_CREATED","CONTACT",id,0,trace);return id;
    }
    public Contact createContact(AuthService.Session s,ContactCreate input,String key,UUID trace){var c=normalize(input);return scoped(s,true,true,a->{UUID id=once(a,"contact.create",key,c,()->insertContact(a,c,trace));return contact(a,id);});}
    public Contact editContact(AuthService.Session s,UUID id,ContactEdit input,UUID trace){return scoped(s,true,true,a->{
        var current=contact(a,id);if(!current.canWrite())throw ApiError.forbidden("无权编辑该联系人");
        if(current.version()!=input.expectedVersion())throw ApiError.conflict("联系人已变化，请刷新后再保存");
        if(!current.customerId().equals(input.customerId())&&!current.canRelink())throw ApiError.conflict("线索转换生成的联系人需保留原客户关联");
        customerWrite(a,input.customerId());var p=contactParams(a,id,normalize(new ContactCreate(input.customerId(),input.name(),input.jobTitle(),input.phone(),input.email(),input.primary(),input.description(),input.decisionRole(),input.influence(),input.contactPreference())));p.put("version",input.expectedVersion());
        if(input.primary())primary(p);
        int changed=db.update("UPDATE crm_contact SET customer_id=:customer,full_name=:name,job_title=:title,phone=:phone,email=:email,is_primary=:primary,description=:description,decision_role=:decision,influence=:influence,contact_preference=:preference,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id AND version=:version",p);
        if(changed!=1)throw ApiError.conflict("联系人已变化，请刷新后再保存");audit(a,"CONTACT_UPDATED","CONTACT",id,input.expectedVersion()+1,trace);return contact(a,id);
    });}
    private static final String LEAD_DEPARTMENT="EXISTS(SELECT 1 FROM sys_tenant_member lm WHERE lm.tenant_id=l.tenant_id AND lm.id=l.owner_member_id AND lm.department_id=:department)";
    static final String LEAD_READ="l.tenant_id=:tenant AND NOT l.in_pool AND (:leadReadAll OR (:leadReadDepartment AND "+LEAD_DEPARTMENT+") OR l.owner_member_id=:member)";
    private static final String LEAD_WRITE="(:leadWriter AND (:leadWriteAll OR (:leadWriteDepartment AND "+LEAD_DEPARTMENT+") OR l.owner_member_id=:member))";
    private static final String VISIBLE_CUSTOMER="(:customerReader AND c.id IS NOT NULL AND ("+CustomerService.READ+"))";
    private static final String LEAD_FROM=" FROM crm_lead l JOIN sys_tenant_member m ON m.tenant_id=l.tenant_id AND m.id=l.owner_member_id LEFT JOIN crm_customer c ON c.tenant_id=l.tenant_id AND c.id=l.converted_customer_id ";
    private static final String LEAD_SELECT="SELECT l.*,m.display_name AS owner_name,(l.status<>'CONVERTED' AND "+LEAD_WRITE+") AS can_write,CASE WHEN "+VISIBLE_CUSTOMER+" THEN c.id END AS visible_customer,CASE WHEN "+VISIBLE_CUSTOMER+" THEN c.customer_name END AS visible_customer_name,CASE WHEN :contactReader AND "+VISIBLE_CUSTOMER+" THEN l.converted_contact_id END AS visible_contact"+LEAD_FROM;
    private Lead leadRow(ResultSet r,AuthService.Actor a)throws SQLException{
        boolean write=r.getBoolean("can_write");var converted=r.getTimestamp("converted_at");
        return new Lead(r.getObject("id",UUID.class),r.getString("contact_name"),r.getString("company_name"),r.getString("industry"),r.getString("source_code"),r.getString("region"),r.getString("job_title"),r.getString("phone"),r.getString("email"),r.getString("description"),r.getString("status"),r.getObject("owner_member_id",UUID.class),r.getString("owner_name"),r.getLong("version"),r.getTimestamp("updated_at").toInstant(),r.getObject("visible_customer",UUID.class),r.getString("visible_customer_name"),r.getObject("visible_contact",UUID.class),converted==null?null:converted.toInstant(),write,write&&"QUALIFIED".equals(r.getString("status"))&&a.canWriteCustomers()&&a.businessAccess().contactWrite());
    }
    Lead lead(AuthService.Actor a,UUID id){var p=params(a);p.put("id",id);var found=db.query(LEAD_SELECT+"WHERE "+LEAD_READ+" AND l.id=:id",p,(r,i)->leadRow(r,a));if(found.isEmpty())throw ApiError.missing();return found.getFirst();}
    public Lead lead(AuthService.Session s,UUID id){return scoped(s,false,false,a->lead(a,id));}
    public Page<Lead> leads(AuthService.Session s,int page,int size,String q,String state){page(page,size,q);if(!state.isEmpty()&&!Set.of("NEW","CONTACTED","QUALIFIED","UNQUALIFIED","CONVERTED").contains(state))throw ApiError.bad("线索状态无效");return scoped(s,false,false,a->{
        var p=params(a);p.put("q",text(q));p.put("state",state);p.put("limit",size);p.put("offset",((long)page-1)*size);
        String where=LEAD_READ+" AND (:state='' OR l.status=:state) AND (:q='' OR strpos(lower(l.company_name),lower(:q))>0 OR strpos(lower(l.contact_name),lower(:q))>0 OR strpos(l.phone,:q)>0 OR strpos(lower(l.email),lower(:q))>0)";
        long total=Objects.requireNonNull(db.queryForObject("SELECT count(*) FROM crm_lead l WHERE "+where,p,Long.class));
        return new Page<>(db.query(LEAD_SELECT+"WHERE "+where+" ORDER BY l.updated_at DESC,l.id LIMIT :limit OFFSET :offset",p,(r,i)->leadRow(r,a)),page,size,total);
    });}
    public List<Map<String,Object>> leadOwners(AuthService.Session s){return scoped(s,false,false,a->{var p=params(a);return db.queryForList("SELECT m.id,m.display_name AS name FROM sys_tenant_member m JOIN iam_user u ON u.id=m.user_id WHERE m.tenant_id=:tenant AND m.status='ACTIVE' AND u.status='ACTIVE' AND (:leadWriteAll OR (:leadWriteDepartment AND m.department_id=:department) OR m.id=:member) AND EXISTS(SELECT 1 FROM sys_member_role r JOIN sys_role_policy rp ON rp.tenant_id=r.tenant_id AND rp.role_code=r.role_code WHERE r.tenant_id=m.tenant_id AND r.member_id=m.id AND rp.lead_can_write) ORDER BY m.display_name,m.id",p);});}
    private void owner(Map<String,Object> p){int count=Objects.requireNonNull(db.queryForObject("SELECT count(*) FROM sys_tenant_member m JOIN iam_user u ON u.id=m.user_id WHERE m.tenant_id=:tenant AND m.id=:owner AND m.status='ACTIVE' AND u.status='ACTIVE' AND (:leadWriteAll OR (:leadWriteDepartment AND m.department_id=:department) OR m.id=:member) AND EXISTS(SELECT 1 FROM sys_member_role r JOIN sys_role_policy rp ON rp.tenant_id=r.tenant_id AND rp.role_code=r.role_code WHERE r.tenant_id=m.tenant_id AND r.member_id=m.id AND rp.lead_can_write)",p,Integer.class));if(count!=1)throw ApiError.bad("线索负责人无效或超出权限");}
    private LeadCreate normalize(LeadCreate l){return new LeadCreate(text(l.name()),text(l.companyName()),text(l.industry()),text(l.source()),text(l.region()),text(l.jobTitle()),text(l.phone()),text(l.email()),text(l.description()),l.ownerMemberId());}
    private Map<String,Object> leadParams(AuthService.Actor a,UUID id,LeadCreate l){var p=params(a);p.put("id",id);p.put("name",text(l.name()));p.put("company",text(l.companyName()));p.put("industry",text(l.industry()));p.put("source",text(l.source()));p.put("region",text(l.region()));p.put("title",text(l.jobTitle()));p.put("phone",text(l.phone()));p.put("email",text(l.email()));p.put("description",text(l.description()));p.put("owner",l.ownerMemberId());return p;}
    public Lead createLead(AuthService.Session s,LeadCreate input,String key,UUID trace){var l=normalize(input);return scoped(s,false,true,a->{UUID id=once(a,"lead.create",key,l,()->{UUID created=UUID.randomUUID();var p=leadParams(a,created,l);owner(p);foundation.checkCapacity(a,l.ownerMemberId(),"LEAD");db.update("INSERT INTO crm_lead(id,tenant_id,contact_name,company_name,industry,source_code,region,job_title,phone,email,description,owner_member_id) VALUES(:id,:tenant,:name,:company,:industry,:source,:region,:title,:phone,:email,:description,:owner)",p);audit(a,"LEAD_CREATED","LEAD",created,0,trace);return created;});return lead(a,id);});}
    public static void transition(String from,String to){
        var choices=Map.of("NEW",Set.of("NEW","CONTACTED","UNQUALIFIED"),"CONTACTED",Set.of("CONTACTED","QUALIFIED","UNQUALIFIED"),"QUALIFIED",Set.of("QUALIFIED","CONTACTED","UNQUALIFIED"),"UNQUALIFIED",Set.of("UNQUALIFIED","NEW"));
        if(!choices.getOrDefault(from,Set.of()).contains(to))throw ApiError.bad("线索状态转换无效，请按联系和确认流程推进");
    }
    public Lead editLead(AuthService.Session s,UUID id,LeadEdit input,UUID trace){return scoped(s,false,true,a->{
        var current=lead(a,id);if("CONVERTED".equals(current.status()))throw ApiError.conflict("该线索已转换，原始线索保持只读");if(!current.canWrite())throw ApiError.forbidden("无权编辑该线索");
        if(current.version()!=input.expectedVersion())throw ApiError.conflict("线索已变化，请刷新后再保存");transition(current.status(),input.status());
        var p=leadParams(a,id,new LeadCreate(input.name(),input.companyName(),input.industry(),input.source(),input.region(),input.jobTitle(),input.phone(),input.email(),input.description(),input.ownerMemberId()));owner(p);if(!current.ownerMemberId().equals(input.ownerMemberId()))foundation.checkCapacity(a,input.ownerMemberId(),"LEAD");p.put("version",input.expectedVersion());p.put("status",input.status());
        int changed=db.update("UPDATE crm_lead SET contact_name=:name,company_name=:company,industry=:industry,source_code=:source,region=:region,job_title=:title,phone=:phone,email=:email,description=:description,owner_member_id=:owner,status=:status,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id AND version=:version AND status<>'CONVERTED'",p);
        if(changed!=1)throw ApiError.conflict("线索已变化，请刷新后再保存");if(!current.ownerMemberId().equals(input.ownerMemberId()))resourcePools.directTransfer(a,"LEAD",id,current.ownerMemberId(),input.ownerMemberId(),input.expectedVersion()+1,trace);audit(a,"LEAD_UPDATED","LEAD",id,input.expectedVersion()+1,trace);return lead(a,id);
    });}
    public Lead convert(AuthService.Session s,UUID id,Convert input,String key,UUID trace){
        Convert normalized=new Convert(input.expectedVersion(),input.targetCustomerId(),input.customerName()==null?null:text(input.customerName()),input.customerLevel(),input.ownerMemberId(),input.primaryContact());
        if(input.targetCustomerId()==null){if(text(input.customerName()).isEmpty()||input.customerLevel()==null||input.ownerMemberId()==null)throw ApiError.bad("新建客户需填写客户名称、等级和负责人");}
        else if(input.customerName()!=null||input.customerLevel()!=null||input.ownerMemberId()!=null)throw ApiError.bad("关联已有客户不能同时填写新建客户字段");
        return scoped(s,false,true,a->{
            a.requireWrite();if(!a.businessAccess().contactWrite())throw ApiError.forbidden("转换还需要客户和联系人写入权限");
            UUID converted=once(a,"lead.convert",key,new ConversionRequest(id,normalized),()->{
                var l=lead(a,id);db.queryForObject("SELECT id FROM crm_lead WHERE tenant_id=:tenant AND id=:id FOR UPDATE",Map.of("tenant",a.tenant().id(),"id",id),UUID.class);
                if("CONVERTED".equals(l.status()))throw ApiError.conflict("该线索已转换，不能重复创建客户或联系人");
                if(!l.canWrite())throw ApiError.forbidden("无权转换该线索");if(l.version()!=input.expectedVersion())throw ApiError.conflict("线索已变化，请刷新后转换");
                if(!"QUALIFIED".equals(l.status()))throw ApiError.bad("请先联系并确认线索合格，再转换客户");
                UUID customer=input.targetCustomerId();
                if(customer==null){var created=customers.create(s,new CustomerService.Create(normalized.customerName(),l.industry(),input.customerLevel(),l.source(),l.region(),null,input.ownerMemberId(),a.access().descriptionWrite()?l.description():""),"conversion:"+UUID.randomUUID(),trace);customer=created.id();}
                else customerWrite(a,customer);
                UUID contact=insertContact(a,new ContactCreate(customer,l.name(),l.jobTitle(),l.phone(),l.email(),input.targetCustomerId()==null||input.primaryContact(),l.description()),trace);
                var p=params(a);p.put("id",id);p.put("customer",customer);p.put("contact",contact);p.put("version",input.expectedVersion());
                int changed=db.update("UPDATE crm_lead SET status='CONVERTED',converted_customer_id=:customer,converted_contact_id=:contact,converted_at=now(),version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id AND version=:version AND status='QUALIFIED'",p);
                if(changed!=1)throw ApiError.conflict("线索已变化，请刷新后转换");audit(a,"LEAD_CONVERTED","LEAD",id,input.expectedVersion()+1,trace);return id;
            });return lead(a,converted);
        });
    }
    private UUID once(AuthService.Actor a,String operation,String key,Object input,Supplier<UUID> fn){
        if(!key.matches("[A-Za-z0-9_.:-]{1,100}"))throw ApiError.bad("请求编号格式无效");String hash;
        try{hash=Crypto.hash(json.writeValueAsString(input));}catch(Exception e){throw new IllegalStateException(e);}
        var p=params(a);p.put("op",operation);p.put("key",key);p.put("hash",hash);
        db.update("INSERT INTO sys_idempotency_request(tenant_id,member_id,operation,request_key,request_hash) VALUES(:tenant,:member,:op,:key,:hash) ON CONFLICT DO NOTHING",p);
        var prior=db.queryForMap("SELECT request_hash,result_id FROM sys_idempotency_request WHERE tenant_id=:tenant AND member_id=:member AND operation=:op AND request_key=:key FOR UPDATE",p);
        if(!hash.equals(prior.get("request_hash")))throw ApiError.conflict("同一请求编号不能提交不同内容");
        if(prior.get("result_id")!=null)return (UUID)prior.get("result_id");UUID id=fn.get();p.put("id",id);
        db.update("UPDATE sys_idempotency_request SET result_id=:id WHERE tenant_id=:tenant AND member_id=:member AND operation=:op AND request_key=:key",p);return id;
    }
    private void audit(AuthService.Actor a,String action,String type,UUID id,long version,UUID trace){db.update("INSERT INTO sys_audit_log(id,tenant_id,actor_member_id,action,entity_type,entity_id,business_version,trace_id) VALUES(:id,:tenant,:member,:action,:type,:entity,:version,:trace)",Map.of("id",UUID.randomUUID(),"tenant",a.tenant().id(),"member",a.tenant().memberId(),"action",action,"type",type,"entity",id,"version",version,"trace",trace));}
}
