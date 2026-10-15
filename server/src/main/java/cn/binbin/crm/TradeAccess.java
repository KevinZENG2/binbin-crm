package cn.binbin.crm;
import java.util.*;
/** Contract and cash permissions are independent from quote approval and organization administration. */
public record TradeAccess(BusinessAccess.Scope contracts,boolean amend,boolean receiptRead,boolean receiptWrite,boolean confirm,boolean reverse,boolean export) {
 public static TradeAccess none(){return new TradeAccess(new BusinessAccess.Scope(false,false,false,false,false,false),false,false,false,false,false,false);}
 public static TradeAccess from(List<Map<String,Object>> policies){boolean r=false,w=false,ra=false,rd=false,wa=false,wd=false,amend=false,rr=false,rw=false,confirm=false,reverse=false,export=false;
  for(var p:policies){boolean cr=Boolean.TRUE.equals(p.get("contract_can_read")),cw=cr&&Boolean.TRUE.equals(p.get("contract_can_write")),cash=cr&&Boolean.TRUE.equals(p.get("receipt_can_read")),manager="SALES_MANAGER".equals(p.get("role_code"));String scope=(String)p.get("record_scope");r|=cr;w|=cw;ra|=cr&&"ALL".equals(scope);rd|=cr&&"DEPARTMENT".equals(scope);wa|=cw&&"ALL".equals(scope);wd|=cw&&"DEPARTMENT".equals(scope);amend|=manager&&cw&&Boolean.TRUE.equals(p.get("contract_can_amend"));rr|=cash;rw|=cash&&Boolean.TRUE.equals(p.get("receipt_can_write"));confirm|=manager&&cash&&Boolean.TRUE.equals(p.get("receipt_can_confirm"));reverse|=manager&&cash&&Boolean.TRUE.equals(p.get("receipt_can_reverse"));export|=cr&&Boolean.TRUE.equals(p.get("trade_can_export"));}
  return new TradeAccess(new BusinessAccess.Scope(r,w,ra,rd,wa,wd),amend,rr,rw,confirm,reverse,export);
 }
 public static TradeAccess defaults(Set<String> roles){return from(roles.stream().map(role->{boolean manager=role.equals("SALES_MANAGER"),sales=role.equals("SALES"),read=manager||sales||role.equals("VIEWER");return SaasService.parameters("role_code",role,"record_scope",manager||role.equals("VIEWER")?"ALL":"OWN_SHARED","contract_can_read",read,"contract_can_write",manager||sales,"contract_can_amend",manager,"receipt_can_read",read,"receipt_can_write",manager||sales,"receipt_can_confirm",manager,"receipt_can_reverse",manager,"trade_can_export",manager);}).toList());}
}
