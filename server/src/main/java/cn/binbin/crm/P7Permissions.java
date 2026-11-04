package cn.binbin.crm;
import java.util.*;import java.time.Instant;import org.springframework.stereotype.Service;import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;import static cn.binbin.crm.TradeService.map;
/** Additional object permissions can only restrict the existing domain authorization. */
@Service
public class P7Permissions {
 final NamedParameterJdbcTemplate db;
 public P7Permissions(NamedParameterJdbcTemplate db){this.db=db;}
 public boolean permits(AuthService.Actor a,String type,boolean write){var p=map("tenant",a.tenant().id(),"type",type,"roles",a.tenant().roles().toArray(String[]::new));var rows=db.queryForList("SELECT role_code,can_read,can_write FROM crm_object_policy WHERE tenant_id=:tenant AND entity_type=:type",p);return rows.isEmpty()||rows.stream().anyMatch(r->a.tenant().roles().contains(String.valueOf(r.get("role_code")))&&Boolean.TRUE.equals(r.get(write?"can_write":"can_read")));}
 public void check(AuthService.Actor a,String type,boolean write){if(a.supportGrantId()!=null||!permits(a,type,write))throw ApiError.forbidden("当前角色没有此业务对象权限");if(write&&!a.tenant().subscriptionEnd().isAfter(Instant.now()))throw ApiError.forbidden("订阅已到期，当前只可查看");}
 public Map<String,Object> plan(AuthService.Actor a){return db.queryForMap("SELECT p.automation_enabled,p.automation_rule_limit,p.automation_monthly_limit,p.workspace_enabled,p.public_form_limit,p.public_submission_monthly_limit,p.migration_enabled,p.migration_monthly_row_limit,p.version FROM sys_tenant t JOIN sys_plan p ON p.id=t.plan_id WHERE t.id=:tenant",map("tenant",a.tenant().id()));}
 public void feature(AuthService.Actor a,String feature){var p=plan(a);if(!Boolean.TRUE.equals(p.get(feature+"_enabled")))throw ApiError.forbidden("当前套餐未启用此功能，请联系产品方调整套餐");if(!a.tenant().subscriptionEnd().isAfter(Instant.now()))throw ApiError.forbidden("订阅已到期，当前只可查看");}
 public void admin(AuthService.Actor a){if(!a.admin())throw ApiError.forbidden("需要企业管理员权限");}
 public void quota(AuthService.Actor a,String limit,long used){if(used>=((Number)plan(a).get(limit)).longValue())throw ApiError.conflict("当前套餐的功能用量已达到上限");}
}
