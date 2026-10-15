package cn.binbin.crm;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    public record Session(UUID id,UUID userId,String username,String displayName,boolean platformOperator,String csrf,UUID tenantId,Instant expiresAt,boolean mustChangePassword,UUID supportGrantId) {}
    public record Tenant(UUID id,String code,String name,String status,Instant subscriptionEnd,UUID memberId,String memberName,Set<String> roles) {}
    public record Actor(Session session,Tenant tenant,CustomerAccess access,UUID departmentId,UUID supportGrantId,BusinessAccess businessAccess,DeliveryAccess deliveryAccess,CommerceAccess commerceAccess,TradeAccess tradeAccess,P3Access p3Access,P4Access p4Access) {
        public Actor(Session s,Tenant t,CustomerAccess c,UUID d,UUID g,BusinessAccess b,DeliveryAccess f,CommerceAccess commerce,TradeAccess trade,P3Access p3){this(s,t,c,d,g,b,f,commerce,trade,p3,P4Access.defaults(t.roles()));}
        public Actor(Session s,Tenant t,CustomerAccess c,UUID d,UUID g,BusinessAccess b,DeliveryAccess f,CommerceAccess commerce,TradeAccess trade){this(s,t,c,d,g,b,f,commerce,trade,P3Access.defaults(t.roles()));}
        public Actor(Session s,Tenant t,CustomerAccess c,UUID d,UUID g,BusinessAccess b,DeliveryAccess f,CommerceAccess commerce){this(s,t,c,d,g,b,f,commerce,TradeAccess.defaults(t.roles()));}
        public Actor(Session s,Tenant t,CustomerAccess c,UUID d,UUID g,BusinessAccess b,DeliveryAccess f){this(s,t,c,d,g,b,f,CommerceAccess.defaults(t.roles()));}
        public Actor(Session session,Tenant tenant){this(session,tenant,CustomerAccess.defaults(tenant.roles()),null,null,BusinessAccess.defaults(tenant.roles()),DeliveryAccess.defaults(tenant.roles()));}
        public Actor(Session s,Tenant t,CustomerAccess c,UUID d,UUID g,BusinessAccess b){this(s,t,c,d,g,b,DeliveryAccess.defaults(t.roles()));}
        public boolean allCustomers() { return access.readAll(); }
        public boolean canReadCustomers() { return access.read(); }
        public boolean canWriteCustomers() { return access.write() && supportGrantId==null; }
        public boolean admin(){return supportGrantId==null&&tenant.roles().contains("TENANT_ADMIN");}
        public void requireWrite() { if(!canWriteCustomers()) throw ApiError.forbidden("当前角色没有客户写入权限"); if(!tenant.subscriptionEnd().isAfter(Instant.now())) throw ApiError.forbidden("订阅已到期，当前仅允许查看"); }
    }
    public record LoginResult(String token,Session session,boolean rejected) {}
    private final NamedParameterJdbcTemplate db;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    private final String dummyHash=passwords.encode("not-a-valid-login-"+UUID.randomUUID());
    private final long hours;
    public AuthService(NamedParameterJdbcTemplate db,TransactionTemplate tx,@Value("${crm.session-hours}") long hours) { this.db=db;this.tx=tx;this.hours=hours; }
    private UUID uuid(Map<String,Object> row,String key) { return (UUID)row.get(key); }
    private Instant at(Map<String,Object> row,String key) { return ((Timestamp)row.get(key)).toInstant(); }
    private void audit(UUID user,String action,UUID target,UUID trace) {
        var p=new HashMap<String,Object>();p.put("id",UUID.randomUUID());p.put("user",user);p.put("action",action);p.put("target",target);p.put("trace",trace);
        db.update("INSERT INTO iam_audit(id,user_id,action,target_id,trace_id) VALUES(:id,:user,:action,:target,:trace)",p);
    }
    public LoginResult login(String username,String password,UUID trace) {
        if(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw ApiError.bad("密码长度超出支持范围");
        String name=username.strip().toLowerCase(Locale.ROOT), key=Crypto.hash("login:"+name);
        LoginResult result=tx.execute(status -> {
            db.update("INSERT INTO auth_login_limit(key_hash) VALUES(:key) ON CONFLICT DO NOTHING",Map.of("key",key));
            var limit=db.queryForMap("SELECT * FROM auth_login_limit WHERE key_hash=:key FOR UPDATE",Map.of("key",key));
            Timestamp blocked=(Timestamp)limit.get("blocked_until");
            if(blocked!=null && blocked.toInstant().isAfter(Instant.now())) return new LoginResult(null,null,true);
            var users=db.queryForList("SELECT * FROM iam_user WHERE username=:name",Map.of("name",name));
            Map<String,Object> user=users.isEmpty()?null:users.getFirst();
            boolean matches=passwords.matches(password,user==null?dummyHash:(String)user.get("password_hash"));
            if(user==null || !matches || !"ACTIVE".equals(user.get("status"))) {
                boolean old=at(limit,"updated_at").isBefore(Instant.now().minusSeconds(900));
                int failures=blocked!=null || old ? 1 : ((Number)limit.get("failures")).intValue()+1;
                var p=new HashMap<String,Object>();p.put("key",key);p.put("failures",failures);p.put("blocked",failures>=5?Timestamp.from(Instant.now().plusSeconds(900)):null);
                db.update("UPDATE auth_login_limit SET failures=:failures,blocked_until=:blocked,updated_at=now() WHERE key_hash=:key",p);
                audit(null,"LOGIN_REJECTED",null,trace);return new LoginResult(null,null,true);
            }
            UUID id=UUID.randomUUID(), uid=uuid(user,"id");String token=Crypto.token(),csrf=Crypto.token();Instant expires=Instant.now().plusSeconds(hours*3600);
            db.update("UPDATE auth_login_limit SET failures=0,blocked_until=NULL,updated_at=now() WHERE key_hash=:key",Map.of("key",key));
            db.update("DELETE FROM auth_session WHERE user_id=:user AND (expires_at<now() OR revoked_at IS NOT NULL)",Map.of("user",uid));
            var p=Map.of("id",id,"hash",Crypto.hash(token),"csrf",csrf,"user",uid,"version",user.get("auth_version"),"expires",Timestamp.from(expires));
            db.update("INSERT INTO auth_session(id,token_hash,csrf_token,user_id,user_auth_version,expires_at) VALUES(:id,:hash,:csrf,:user,:version,:expires)",p);
            audit(uid,"LOGIN_SUCCEEDED",id,trace);
            return new LoginResult(token,new Session(id,uid,name,(String)user.get("display_name"),(boolean)user.get("is_platform_operator"),csrf,null,expires,(boolean)user.get("must_change_password"),null),false);
        });
        if(result==null || result.rejected()) throw new ApiError(org.springframework.http.HttpStatus.UNAUTHORIZED,"LOGIN_FAILED","账号或密码错误，请稍后重试");
        return result;
    }
    public Session authenticate(HttpServletRequest request) {
        String token=null;if(request.getCookies()!=null) for(Cookie c:request.getCookies()) if("CRM_SESSION".equals(c.getName())) token=c.getValue();
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw ApiError.unauthorized();
        var rows=db.queryForList("SELECT s.*,u.username,u.display_name,u.is_platform_operator,u.must_change_password FROM auth_session s JOIN iam_user u ON u.id=s.user_id WHERE s.token_hash=:hash AND s.revoked_at IS NULL AND s.expires_at>now() AND u.status='ACTIVE' AND u.auth_version=s.user_auth_version",Map.of("hash",Crypto.hash(token)));
        if(rows.isEmpty())throw ApiError.unauthorized();var x=rows.getFirst();
        return new Session(uuid(x,"id"),uuid(x,"user_id"),(String)x.get("username"),(String)x.get("display_name"),(boolean)x.get("is_platform_operator"),(String)x.get("csrf_token"),uuid(x,"active_tenant_id"),at(x,"expires_at"),(boolean)x.get("must_change_password"),uuid(x,"support_grant_id"));
    }
    public void csrf(Session session,HttpServletRequest request) { if(!Crypto.same(session.csrf(),request.getHeader("X-CSRF-Token"))) throw ApiError.forbidden("请求校验失败，请刷新登录状态"); }
    public List<Tenant> tenants(Session s) {
        var rows=db.queryForList("SELECT t.*,m.id AS member_id,m.display_name AS member_name FROM sys_tenant t JOIN sys_tenant_member m ON m.tenant_id=t.id WHERE m.user_id=:user AND m.status='ACTIVE' ORDER BY t.code",Map.of("user",s.userId()));
        return rows.stream().map(r -> {
            UUID tid=uuid(r,"id"),mid=uuid(r,"member_id");
            Set<String> roles=new TreeSet<>(db.queryForList("SELECT role_code FROM sys_member_role WHERE tenant_id=:tenant AND member_id=:member",Map.of("tenant",tid,"member",mid),String.class));
            return new Tenant(tid,(String)r.get("code"),(String)r.get("name"),(String)r.get("status"),at(r,"subscription_end"),mid,(String)r.get("member_name"),roles);
        }).toList();
    }
    public Session switchTenant(Session s,UUID tenant,UUID trace) {
        return tx.execute(status -> {
            if(tenants(s).stream().noneMatch(t -> t.id().equals(tenant)))throw ApiError.missing();
            db.update("UPDATE auth_session SET active_tenant_id=:tenant,support_grant_id=NULL WHERE id=:id AND revoked_at IS NULL AND expires_at>now()",Map.of("tenant",tenant,"id",s.id()));
            audit(s.userId(),"TENANT_SELECTED",tenant,trace);
            return new Session(s.id(),s.userId(),s.username(),s.displayName(),s.platformOperator(),s.csrf(),tenant,s.expiresAt(),s.mustChangePassword(),null);
        });
    }
    public void logout(Session s,UUID trace) { tx.executeWithoutResult(status -> { db.update("UPDATE auth_session SET revoked_at=now() WHERE id=:id",Map.of("id",s.id()));audit(s.userId(),"LOGOUT",s.id(),trace); }); }
    public Actor actor(Session s) {
        ready(s);
        if(s.tenantId()==null)throw ApiError.forbidden("请先选择企业");
        if(s.supportGrantId()!=null){
            if(!s.platformOperator())throw ApiError.forbidden("支持授权无效");
            var grants=db.queryForList("SELECT g.*,t.code,t.name,t.status,t.subscription_end FROM sys_support_grant g JOIN sys_tenant t ON t.id=g.tenant_id WHERE g.id=:grant AND g.tenant_id=:tenant AND g.operator_user_id=:user AND g.revoked_at IS NULL AND g.expires_at>now()",Map.of("grant",s.supportGrantId(),"tenant",s.tenantId(),"user",s.userId()));
            if(grants.isEmpty())throw ApiError.forbidden("支持授权已到期或被撤销");var g=grants.getFirst();if(!"ACTIVE".equals(g.get("status")))throw ApiError.forbidden("企业已暂停使用");
            Tenant t=new Tenant(s.tenantId(),(String)g.get("code"),(String)g.get("name"),(String)g.get("status"),at(g,"subscription_end"),null,"运营支持（授权只读）",Set.of());
            return new Actor(s,t,CustomerAccess.support((boolean)g.get("allow_credit"),(boolean)g.get("allow_description")),null,s.supportGrantId(),BusinessAccess.none(),DeliveryAccess.none());
        }
        Tenant tenant=tenants(s).stream().filter(t -> t.id().equals(s.tenantId())).findFirst().orElseThrow(ApiError::missing);
        if(!"ACTIVE".equals(tenant.status()))throw ApiError.forbidden("企业已暂停使用，请联系运营人员");
        return tx.execute(status->{db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",tenant.id().toString()),String.class);
            var policy=db.queryForList("SELECT p.* FROM sys_role_policy p JOIN sys_member_role r ON r.tenant_id=p.tenant_id AND r.role_code=p.role_code WHERE r.tenant_id=:tenant AND r.member_id=:member",Map.of("tenant",tenant.id(),"member",tenant.memberId()));
            UUID department=db.queryForObject("SELECT department_id FROM sys_tenant_member WHERE tenant_id=:tenant AND id=:member",Map.of("tenant",tenant.id(),"member",tenant.memberId()),UUID.class);
            var customers=CustomerAccess.from(policy);return new Actor(s,tenant,customers,department,null,BusinessAccess.from(policy,customers),DeliveryAccess.from(policy),CommerceAccess.from(policy),TradeAccess.from(policy),P3Access.from(policy),P4Access.from(policy));
        });
    }
    public void ready(Session s){if(s.mustChangePassword())throw new ApiError(org.springframework.http.HttpStatus.FORBIDDEN,"PASSWORD_CHANGE_REQUIRED","首次登录请先修改初始密码");}
    public void platform(Session s){ready(s);if(!s.platformOperator())throw ApiError.forbidden("需要平台运营权限");}
    public Session workspace(HttpServletRequest r){Session s=authenticate(r);String expected=r.getHeader("X-Workspace-ID");if(expected!=null&&!expected.equals(s.tenantId()==null?"":s.tenantId().toString()))throw new ApiError(org.springframework.http.HttpStatus.CONFLICT,"TENANT_CONTEXT_CHANGED","企业选择已变化，请刷新并确认当前企业");return s;}
    public static void validatePassword(String value){if(value==null||value.length()<12||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw ApiError.bad("密码需至少12个字符，且不超过72字节");}
    public String passwordHash(String value){validatePassword(value);return passwords.encode(value);}
    public void changePassword(Session s,String oldPassword,String nextPassword,UUID trace){validatePassword(nextPassword);if(oldPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw ApiError.bad("原密码长度无效");tx.executeWithoutResult(status->{var user=db.queryForMap("SELECT password_hash FROM iam_user WHERE id=:id FOR UPDATE",Map.of("id",s.userId()));if(!passwords.matches(oldPassword,(String)user.get("password_hash")))throw ApiError.forbidden("原密码不正确");if(Crypto.same(oldPassword,nextPassword))throw ApiError.bad("新密码不能与原密码相同");db.update("UPDATE iam_user SET password_hash=:hash,must_change_password=false,auth_version=auth_version+1 WHERE id=:id",Map.of("hash",passwords.encode(nextPassword),"id",s.userId()));audit(s.userId(),"PASSWORD_CHANGED",s.userId(),trace);});}
    public Map<String,Object> view(Session s) {
        var result=new LinkedHashMap<String,Object>();
        result.put("user",Map.of("id",s.userId(),"username",s.username(),"displayName",s.displayName(),"platformOperator",s.platformOperator(),"mustChangePassword",s.mustChangePassword()));
        result.put("csrfToken",s.csrf());result.put("expiresAt",s.expiresAt());result.put("activeTenantId",s.tenantId());result.put("tenants",tenants(s));
        result.put("businessAccess",BusinessAccess.none());result.put("deliveryAccess",DeliveryAccess.none());result.put("commerceAccess",CommerceAccess.none());result.put("tradeAccess",TradeAccess.none());result.put("p3Access",P3Access.none());result.put("p4Access",P4Access.none());result.put("customerAccess",null);result.put("supportTenant",null);result.put("supportGrantId",s.supportGrantId());
        if(s.tenantId()!=null&&!s.mustChangePassword()){try{var a=actor(s);result.put("customerAccess",a.access());result.put("businessAccess",a.businessAccess());result.put("deliveryAccess",a.deliveryAccess());result.put("commerceAccess",a.commerceAccess());result.put("tradeAccess",a.tradeAccess());result.put("p3Access",a.p3Access());result.put("p4Access",a.p4Access());if(a.supportGrantId()!=null)result.put("supportTenant",a.tenant());}catch(ApiError ignored){}}
        return result;
    }
}
