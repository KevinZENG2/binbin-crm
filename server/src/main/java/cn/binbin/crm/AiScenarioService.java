package cn.binbin.crm;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static cn.binbin.crm.TradeService.map;

/** Every AI source passes through the same reader as the business workspace. */
@Service
public class AiScenarioService {
 public record Task(String id,String name,String entityType,String action) {}
 public static final List<Task> TASKS=List.of(
  new Task("CUSTOMER_SUMMARY","客户经营摘要","CUSTOMER",""),
  new Task("OPPORTUNITY_COACH","商机推进建议","OPPORTUNITY",""),
  new Task("TICKET_REPLY","工单回复草稿","TICKET",""),
  new Task("CAMPAIGN_ADVICE","营销活动建议","CAMPAIGN",""),
  new Task("LEAD_ASSESSMENT","线索研判","LEAD",""),
  new Task("VISIT_PREP","拜访准备","CUSTOMER",""),
  new Task("FOLLOWUP_DRAFT","跟进记录草稿","CUSTOMER","CREATE_FOLLOWUP"),
  new Task("NEW_LEAD","新建线索草稿","WORKSPACE","CREATE_LEAD"),
  new Task("TICKET_TRIAGE","客诉与工单分诊","TICKET",""),
  new Task("WEEKLY_SUMMARY","销售周报辅助","WEEKLY",""),
  new Task("ANOMALY_EXPLAIN","经营异常解释","WORKSPACE",""),
  new Task("APPROVAL_ASSIST","报价审批辅助","QUOTE",""));
 final P3Base b; final P7Records records; final CustomerService customers;
 final CampaignService campaigns; final AnalyticsService analytics;
 public AiScenarioService(P3Base b,P7Records records,CustomerService customers,CampaignService campaigns,AnalyticsService analytics){this.b=b;this.records=records;this.customers=customers;this.campaigns=campaigns;this.analytics=analytics;}
 public static Task task(String type){return TASKS.stream().filter(t->t.id().equals(type)).findFirst().orElseThrow(()->ApiError.bad("AI 场景无效"));}
 public static String clean(Object value,int max){if(value==null)return "";String v=AiRedaction.redact(String.valueOf(value).replaceAll("[\\p{Cc}&&[^\\n\\t]]"," ").strip());return v.length()>max?v.substring(0,max):v;}
 private AuthService.Actor actor(AuthService.Session s){var a=b.auth.actor(s);if(a.supportGrantId()!=null||!a.p4Access().aiUse())throw ApiError.forbidden("当前成员没有 AI 使用权限");return a;}
 private boolean allowed(AuthService.Actor a,Task t){return switch(t.id()){
  case "NEW_LEAD"->records.allowed(a,"LEAD",true);
  case "FOLLOWUP_DRAFT"->records.allowed(a,"CUSTOMER",true)&&a.businessAccess().activityWrite();
  case "ANOMALY_EXPLAIN"->a.deliveryAccess().reports();
  case "CAMPAIGN_ADVICE"->a.p3Access().campaigns().read();
  case "APPROVAL_ASSIST"->a.commerceAccess().approve()&&records.allowed(a,"QUOTE",false);
  default->records.allowed(a,t.entityType(),false);
 };}
 public Object catalog(AuthService.Session s){return b.tx.execute(st->{var a=actor(s);return map("items",TASKS.stream().filter(t->allowed(a,t)).toList(),"memberId",a.tenant().memberId());});}
 @SuppressWarnings("unchecked") public Object options(AuthService.Session s,String type,int page,int size,String q){var t=task(type);return b.tx.execute(st->{var a=actor(s);if(!allowed(a,t))throw ApiError.forbidden("当前成员没有此 AI 场景权限");if(t.entityType().equals("WORKSPACE"))return map("items",List.of(map("id",a.tenant().memberId(),"name","当前授权工作区")),"total",1);
  if(type.equals("CAMPAIGN_ADVICE")){var raw=records.object(campaigns.list(s,page,size,q,""));return map("items",((List<?>)raw.get("items")).stream().map(records::object).map(r->map("id",r.get("id"),"name",r.getOrDefault("name",r.getOrDefault("title","营销活动")))).toList(),"total",raw.get("total"));}
  var raw=records.object(records.list(s,t.entityType(),page,size,q,LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear()));
  var items=new ArrayList<Map<String,Object>>();for(Object row:(List<?>)raw.get("items")){var r=records.object(row);if(type.equals("APPROVAL_ASSIST")){var quote=records.object(records.get(s,"QUOTE",(UUID)r.get("id")));if(!Boolean.TRUE.equals(quote.get("canApprove")))continue;}items.add(map("id",r.get("id"),"name",r.getOrDefault("recordTitle",r.getOrDefault("title","业务记录"))));}return map("items",items,"total",raw.get("total"));});}
 public Map<String,Object> context(AuthService.Session s,String type,UUID id){var t=task(type);return b.tx.execute(st->{var a=actor(s);if(!allowed(a,t))throw ApiError.forbidden("当前成员没有此 AI 场景权限");var out=new LinkedHashMap<String,Object>();
  if(t.entityType().equals("WORKSPACE")){if(!a.tenant().memberId().equals(id))throw ApiError.missing();out.put("scope","当前成员可见数据");if(type.equals("NEW_LEAD")){out.put("owner","当前成员");return out;}LocalDate end=LocalDate.now(ZoneId.of("Asia/Shanghai"));var report=records.object(analytics.dashboard(s,new AnalyticsService.Filter(end.minusDays(29),end,null,null,"","")));out.put("from",report.get("from"));out.put("to",report.get("to"));out.put("panels",report.get("panels"));return out;}
  if(t.entityType().equals("CUSTOMER")){records.get(s,"CUSTOMER",id);var c=customers.detail(s,id);out.put("name",clean(c.name(),200));out.put("industry",clean(c.industry(),80));out.put("level",c.level());out.put("source",clean(c.source(),80));out.put("region",clean(c.region(),100));out.put("owner",clean(c.ownerName(),100));out.put("version",c.version());if(c.descriptionReadable())out.put("description",clean(c.description(),2000));if(a.businessAccess().activityRead()){var recent=records.object(records.sales.activities(s,1,3,"",id,null));out.put("recentActivities",((List<?>)recent.get("items")).stream().map(records::object).map(row->map("type",row.get("activityType"),"occurredAt",row.get("occurredAt"),"content",clean(row.get("content"),500))).toList());}return out;}
  Map<String,Object> source=t.entityType().equals("CAMPAIGN")?records.object(campaigns.get(s,id)):records.object(records.get(s,t.entityType(),id));
  if(type.equals("APPROVAL_ASSIST")&&!Boolean.TRUE.equals(source.get("canApprove")))throw ApiError.forbidden("仅当前有审批权限的指定主管可获取审批辅助");
  List<String> fields=switch(t.entityType()){
   case "LEAD"->List.of("name","companyName","industry","source","region","jobTitle","description","status","ownerName","version");
   case "OPPORTUNITY"->List.of("customerName","title","ownerName","stage","amountCents","actualAmountCents","closeDate","nextAction","description","version");
   case "TICKET"->List.of("ticketNumber","title","description","typeCode","priority","status","resolution","customerName","assigneeName","version");
   case "CAMPAIGN"->List.of("name","title","typeCode","objective","goal","budgetCents","status","plannedStart","plannedEnd","taskDueDate","channel","sourceChannel","subject","body","description","version");
   case "WEEKLY"->List.of("memberName","weekStart","progress","achievements","risks","nextActions","confirmedCents","status","version");
   case "QUOTE"->List.of("title","quoteNumber","customerName","totalCents","validUntil","status","notes","version");
   default->throw ApiError.bad("AI 来源无效");};
  for(String field:fields)if(source.get(field)!=null)out.put(field,clean(source.get(field),List.of("description","progress","achievements","risks","nextActions","notes").contains(field)?2500:400));return out;
 });}
 public String sourceHash(AuthService.Session s,String type,UUID id){return Crypto.hash(b.json(context(s,type,id)));}
}
