package cn.binbin.crm;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record CustomerAccess(boolean read,boolean write,boolean readAll,boolean readDepartment,boolean writeAll,boolean writeDepartment,boolean creditRead,boolean creditWrite,boolean descriptionRead,boolean descriptionWrite,boolean share) {
    public static CustomerAccess from(List<Map<String,Object>> policies) {
        boolean r=false,w=false,ra=false,rd=false,wa=false,wd=false,cr=false,cw=false,dr=false,dw=false,sh=false;
        for(var p:policies){boolean pr=Boolean.TRUE.equals(p.get("can_read")),pw=Boolean.TRUE.equals(p.get("can_write"));String scope=(String)p.get("record_scope"),credit=(String)p.get("credit_access"),description=(String)p.get("description_access");
            r|=pr;w|=pw;ra|=pr&&"ALL".equals(scope);rd|=pr&&"DEPARTMENT".equals(scope);wa|=pw&&"ALL".equals(scope);wd|=pw&&"DEPARTMENT".equals(scope);
            cr|=pr&&!"NONE".equals(credit);cw|=pw&&"WRITE".equals(credit);dr|=pr&&!"NONE".equals(description);dw|=pw&&"WRITE".equals(description);sh|=pw&&Boolean.TRUE.equals(p.get("can_share"));
        }return new CustomerAccess(r,w,ra,rd,wa,wd,cr,cw,dr,dw,sh);
    }
    public static CustomerAccess defaults(Set<String> roles){return from(roles.stream().map(role->{boolean manager=role.equals("SALES_MANAGER"),sales=role.equals("SALES"),viewer=role.equals("VIEWER");return Map.<String,Object>of("can_read",manager||sales||viewer,"can_write",manager||sales,"record_scope",manager||viewer?"ALL":"OWN_SHARED","credit_access",viewer||role.equals("TENANT_ADMIN")?"NONE":"WRITE","description_access",viewer?"READ":role.equals("TENANT_ADMIN")?"NONE":"WRITE","can_share",manager||sales);}).toList());}
    public static CustomerAccess support(boolean credit,boolean description){return new CustomerAccess(true,false,true,false,false,false,credit,false,description,false,false);}
}
