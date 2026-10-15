package cn.binbin.crm;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Each module merges read and write scope separately; linked records also require customer access. */
public record BusinessAccess(boolean contactRead,boolean contactWrite,Scope leads,Scope opportunities,Scope tasks,boolean activityRead,boolean activityWrite) {
    public record Scope(boolean read,boolean write,boolean readAll,boolean readDepartment,boolean writeAll,boolean writeDepartment) {}
    private static Scope empty(){return new Scope(false,false,false,false,false,false);}
    public static BusinessAccess none(){return new BusinessAccess(false,false,empty(),empty(),empty(),false,false);}
    private static boolean enabled(List<Map<String,Object>> policies,String column){return policies.stream().anyMatch(p->Boolean.TRUE.equals(p.get(column)));}
    private static Scope scope(List<Map<String,Object>> policies,String module){
        boolean r=false,w=false,ra=false,rd=false,wa=false,wd=false;
        for(var p:policies){
            boolean pr=Boolean.TRUE.equals(p.get(module+"_can_read")),pw=Boolean.TRUE.equals(p.get(module+"_can_write"));
            String scope=(String)p.get("record_scope");r|=pr;w|=pw;
            ra|=pr&&"ALL".equals(scope);rd|=pr&&"DEPARTMENT".equals(scope);
            wa|=pw&&"ALL".equals(scope);wd|=pw&&"DEPARTMENT".equals(scope);
        }
        return new Scope(r,w&&r,ra,rd,wa,wd);
    }
    public static BusinessAccess from(List<Map<String,Object>> policies,CustomerAccess customers){
        boolean cr=enabled(policies,"contact_can_read")&&customers.read(),cw=cr&&enabled(policies,"contact_can_write")&&customers.write();
        boolean ar=enabled(policies,"activity_can_read")&&customers.read(),aw=ar&&enabled(policies,"activity_can_write")&&customers.write();
        return new BusinessAccess(cr,cw,scope(policies,"lead"),scope(policies,"opportunity"),scope(policies,"task"),ar,aw);
    }
    public static BusinessAccess defaults(Set<String> roles){
        var policies=roles.stream().map(role->{boolean write=role.equals("SALES")||role.equals("SALES_MANAGER"),read=write||role.equals("VIEWER");
            Map<String,Object> p=new java.util.HashMap<>();p.put("record_scope",role.equals("SALES_MANAGER")||role.equals("VIEWER")?"ALL":"OWN_SHARED");
            for(String module:List.of("contact","lead","opportunity","task","activity")){p.put(module+"_can_read",read);p.put(module+"_can_write",write);}return p;
        }).toList();
        return from(policies,CustomerAccess.defaults(roles));
    }
}
