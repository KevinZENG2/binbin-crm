package cn.binbin.crm;

import java.util.*;

public record DeliveryAccess(boolean reports,boolean export,boolean customerImport,boolean attachmentRead,boolean attachmentWrite) {
    public static DeliveryAccess none(){return new DeliveryAccess(false,false,false,false,false);}
    public static DeliveryAccess from(List<Map<String,Object>> policies){
        return new DeliveryAccess(enabled(policies,"report_can_read"),enabled(policies,"export_can_read"),enabled(policies,"import_can_write"),enabled(policies,"attachment_can_read"),enabled(policies,"attachment_can_write"));
    }
    private static boolean enabled(List<Map<String,Object>> p,String name){return p.stream().anyMatch(r->Boolean.TRUE.equals(r.get(name)));}
    public static DeliveryAccess defaults(Set<String> roles){boolean sales=roles.contains("SALES")||roles.contains("SALES_MANAGER"),read=sales||roles.contains("VIEWER");return new DeliveryAccess(read,sales,roles.contains("SALES_MANAGER"),read,sales);}
}
