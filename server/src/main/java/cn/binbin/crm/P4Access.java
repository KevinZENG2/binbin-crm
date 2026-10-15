package cn.binbin.crm;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public record P4Access(boolean configurationRead,boolean configurationManage,boolean portalAccountManage) {
 public static P4Access from(List<Map<String,Object>> rows){boolean read=rows.stream().anyMatch(r->Boolean.TRUE.equals(r.get("configuration_can_read")));boolean manage=read&&rows.stream().anyMatch(r->Boolean.TRUE.equals(r.get("configuration_can_manage")));boolean portal=rows.stream().anyMatch(r->Boolean.TRUE.equals(r.get("portal_account_can_manage")));return new P4Access(read,manage,portal);}
 public static P4Access none(){return new P4Access(false,false,false);}
 public static P4Access defaults(Set<String> roles){boolean admin=roles.contains("TENANT_ADMIN");return new P4Access(admin,admin,admin);}
 public static void initialize(NamedParameterJdbcTemplate db,UUID tenant){db.update("UPDATE sys_role_policy SET configuration_can_read=role_code='TENANT_ADMIN',configuration_can_manage=role_code='TENANT_ADMIN',portal_account_can_manage=role_code='TENANT_ADMIN' WHERE tenant_id=:tenant",Map.of("tenant",tenant));}
 public static void policy(NamedParameterJdbcTemplate db,UUID tenant,String role,Map<String,Object> old,SaasService.PolicyInput input){boolean read=input.configurationCanRead()==null?Boolean.TRUE.equals(old.get("configuration_can_read")):input.configurationCanRead();boolean manage=input.configurationCanManage()==null?Boolean.TRUE.equals(old.get("configuration_can_manage")):input.configurationCanManage();boolean portal=input.portalAccountCanManage()==null?Boolean.TRUE.equals(old.get("portal_account_can_manage")):input.portalAccountCanManage();if(manage&&!read||!role.equals("TENANT_ADMIN")&&(manage||portal))throw ApiError.bad("P4 配置管理和门户账号管理仅限企业管理员，配置管理需先授予读取");db.update("UPDATE sys_role_policy SET configuration_can_read=:read,configuration_can_manage=:manage,portal_account_can_manage=:portal WHERE tenant_id=:tenant AND role_code=:role",Map.of("read",read,"manage",manage,"portal",portal,"tenant",tenant,"role",role));}
}
