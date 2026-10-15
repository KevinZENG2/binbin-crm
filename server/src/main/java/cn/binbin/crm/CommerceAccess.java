package cn.binbin.crm;
import java.util.*;

/** Commercial permissions are independent of organization administration. */
public record CommerceAccess(boolean productRead,boolean productWrite,BusinessAccess.Scope quotes,boolean pdfExport,boolean approve) {
    public CommerceAccess(boolean pr,boolean pw,BusinessAccess.Scope q,boolean pdf){this(pr,pw,q,pdf,false);}
    public static CommerceAccess none(){return new CommerceAccess(false,false,new BusinessAccess.Scope(false,false,false,false,false,false),false,false);}
    public static CommerceAccess from(List<Map<String,Object>> policies){
        boolean pr=false,pw=false,r=false,w=false,ra=false,rd=false,wa=false,wd=false,pdf=false,approve=false;
        for(var p:policies){boolean qr=Boolean.TRUE.equals(p.get("quote_can_read")),qw=qr&&Boolean.TRUE.equals(p.get("quote_can_write"));String scope=(String)p.get("record_scope");
            pr|=Boolean.TRUE.equals(p.get("product_can_read"));pw|=Boolean.TRUE.equals(p.get("product_can_read"))&&Boolean.TRUE.equals(p.get("product_can_write"));
            r|=qr;w|=qw;ra|=qr&&"ALL".equals(scope);rd|=qr&&"DEPARTMENT".equals(scope);wa|=qw&&"ALL".equals(scope);wd|=qw&&"DEPARTMENT".equals(scope);pdf|=qr&&Boolean.TRUE.equals(p.get("quote_pdf_can_export"));approve|=qr&&"SALES_MANAGER".equals(p.get("role_code"))&&Boolean.TRUE.equals(p.get("quote_can_approve"));
        }return new CommerceAccess(pr,pw,new BusinessAccess.Scope(r,w,ra,rd,wa,wd),pdf,approve);
    }
    public static CommerceAccess defaults(Set<String> roles){return from(roles.stream().map(role->{boolean manager=role.equals("SALES_MANAGER"),sales=role.equals("SALES"),read=manager||sales||role.equals("VIEWER");return Map.<String,Object>of("role_code",role,"record_scope",manager||role.equals("VIEWER")?"ALL":"OWN_SHARED","product_can_read",read,"product_can_write",manager,"quote_can_read",read,"quote_can_write",manager||sales,"quote_pdf_can_export",manager||sales,"quote_can_approve",manager);}).toList());}
}
