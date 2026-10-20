package cn.binbin.crm;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only, repeatable-read analytics. Every source retains its original record scope. */
@Service
public class AnalyticsService {
    public record Filter(LocalDate from, LocalDate to, UUID owner, UUID department, String region, String industry) {
        public Filter {
            TradeService.date(from); TradeService.date(to);
            if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 366) throw ApiError.bad("统计日期最多跨366天");
            region=TradeService.text(region); industry=TradeService.text(industry);
            if(region.length()>100 || industry.length()>80) throw ApiError.bad("地区或行业条件过长");
        }
    }
    private final DeliveryGuard guard;
    private final TradeService t;
    private final CommerceService commerce;
    private final P3Base p3;
    public AnalyticsService(DeliveryGuard guard, TradeService t, CommerceService commerce, P3Base p3) {this.guard=guard;this.t=t;this.commerce=commerce;this.p3=p3;}
    private Map<String,Object> params(AuthService.Actor a, Filter f) {
        var p=t.params(a);p.putAll(commerce.params(a));p.putAll(p3.params(a));
        p.putAll(P3Base.map("fromDate",f.from(),"toDate",f.to(),"ownerFilter",f.owner(),"departmentFilter",f.department(),"regionFilter",f.region(),"industryFilter",f.industry()));return p;
    }
    private LinkedHashMap<String,String> modules(AuthService.Actor a) {
        var m=new LinkedHashMap<String,String>();boolean c=a.canReadCustomers(), k=c&&a.tradeAccess().contracts().read();
        if(c)m.put("customers","新增客户");if(a.businessAccess().leads().read())m.put("leads","新建线索");
        if(c&&a.businessAccess().opportunities().read()){m.put("opportunities","商机管道");m.put("wins","成交商机");}
        if(c&&a.commerceAccess().quotes().read())m.put("quotes","报价分析");
        if(k){m.put("orders","确认订单");if(a.commerceAccess().productRead())m.put("products","产品销售");}
        if(k&&a.tradeAccess().receiptRead()){m.put("cash","净回款");m.put("rfm","客户 RFM");}
        if(a.p3Access().tickets().read())m.put("service","售后分析");if(k)m.put("commissions","激励分析");return m;
    }
    private boolean cost(AuthService.Actor a,String module){return a.commerceAccess().productWrite()&&Set.of("orders","products").contains(module);}
    private boolean exportable(AuthService.Actor a,String module){return a.deliveryAccess().export()&&(!Set.of("orders","products","cash","rfm","commissions").contains(module)||a.tradeAccess().export())&&(!module.equals("service")||a.p3Access().export());}
    private static String day(String field){return "("+field+" AT TIME ZONE 'Asia/Shanghai')::date";}
    private static String row(String id,String title,String customer,String customerName,String owner,String ownerName,String dept,String region,String industry,String date,String state,String amount,String aux,String cost,String quantity,String score,String recency,String frequency,String category){
        return "SELECT "+id+" AS id,"+title+" AS title,"+customer+" AS customer_id,"+customerName+" AS customer_name,"+owner+" AS owner_id,"+ownerName+" AS owner_name,"+dept+" AS department_id,"+region+" AS region,"+industry+" AS industry,"+date+" AS event_date,"+state+" AS state,("+amount+")::numeric AS amount_cents,("+aux+")::numeric AS aux_cents,("+cost+")::numeric AS cost_cents,("+quantity+")::numeric AS quantity,("+score+")::numeric AS score,("+recency+")::int AS recency_days,("+frequency+")::bigint AS frequency,"+category+" AS category";
    }
    private String customerRow(String id,String title,String owner,String date,String state,String amount,String aux,String cost,String quantity,String score,String recency,String frequency,String category){return row(id,title,"c.id","c.customer_name",owner,"m.display_name","m.department_id","c.region","c.industry",date,state,amount,aux,cost,quantity,score,recency,frequency,category);}
    private String source(AuthService.Actor a,String module){
        String zero="0",nil="NULL",cost=cost(a,module)?"k.estimated_cost_cents":"NULL";
        switch(module){
            case "customers":return customerRow("c.id","c.customer_name","c.owner_member_id",day("c.created_at"),"c.level_code",zero,zero,nil,zero,nil,nil,zero,"''")+" FROM crm_customer c JOIN sys_tenant_member m ON m.tenant_id=c.tenant_id AND m.id=c.owner_member_id WHERE "+CustomerService.READ;
            case "leads":return row("l.id","l.company_name","NULL::uuid","''","l.owner_member_id","m.display_name","m.department_id","l.region","l.industry",day("l.created_at"),"l.status",zero,zero,nil,zero,nil,nil,zero,"l.source_code")+" FROM crm_lead l JOIN sys_tenant_member m ON m.tenant_id=l.tenant_id AND m.id=l.owner_member_id WHERE "+BusinessService.LEAD_READ;
            case "opportunities":case "wins":return customerRow("o.id","o.title","o.owner_member_id",day(module.equals("wins")?"o.closed_at":"o.created_at"),"o.stage",module.equals("wins")?"o.actual_amount_cents":"o.amount_cents",zero,nil,zero,nil,nil,zero,"''")+" FROM crm_opportunity o JOIN crm_customer c ON c.tenant_id=o.tenant_id AND c.id=o.customer_id JOIN sys_tenant_member m ON m.tenant_id=o.tenant_id AND m.id=o.owner_member_id WHERE "+SalesService.OP_READ+(module.equals("wins")?" AND o.stage='WON'":"");
            case "quotes":return customerRow("q.id","q.quote_number||' · '||q.title","q.owner_member_id",day("q.created_at"),"q.status","q.total_cents",zero,nil,zero,nil,nil,zero,"''")+CommerceService.FROM+" WHERE "+CommerceService.READ;
            case "orders":return customerRow("k.id","k.order_number||' · '||k.title","k.owner_member_id",day("k.confirmed_at"),"k.status","k.total_cents",a.tradeAccess().receiptRead()?"coalesce(ck.received,0)":"NULL",cost,zero,nil,nil,zero,"''")+SalesOrderService.FROM+(a.tradeAccess().receiptRead()?" LEFT JOIN (SELECT k.order_id,"+TradeService.CASH+" AS received"+TradeService.FROM+" WHERE "+TradeService.READ+") ck ON ck.order_id=k.id ":"")+" WHERE "+SalesOrderService.READ+" AND k.status IN('CONFIRMED','IN_DELIVERY','DELIVERED')";
            case "products":return customerRow("l.id","l.name_snapshot","k.owner_member_id",day("k.confirmed_at"),"k.status","l.amount_cents",zero,cost(a,module)?"round(l.cost_cents*l.quantity)":"NULL","l.quantity",nil,nil,zero,"l.product_id::text")+SalesOrderService.FROM+" JOIN crm_sales_order_line l ON l.tenant_id=k.tenant_id AND l.order_id=k.id WHERE "+SalesOrderService.READ+" AND k.status IN('CONFIRMED','IN_DELIVERY','DELIVERED')";
            case "cash":return cashSource();
            case "rfm":return rfmSource();
            case "service":return row("t.id","t.ticket_number||' · '||t.title","c.id","c.customer_name","t.assignee_member_id","am.display_name","am.department_id","c.region","c.industry",day("t.created_at"),"t.status",zero,zero,nil,zero,"f.score",nil,zero,"t.type_code")+ServiceDeskService.FROM+" LEFT JOIN crm_service_feedback f ON f.tenant_id=t.tenant_id AND f.ticket_id=t.id AND f.cycle_no=t.cycle_no AND f.submitted_at IS NOT NULL AND f.revoked_at IS NULL WHERE "+ServiceDeskService.READ;
            case "commissions":return row("x.id","m.display_name||' · '||x.period_start::text","NULL::uuid","''","x.member_id","m.display_name","m.department_id","''","''","x.period_start","x.status","x.commission_cents","coalesce((SELECT sum(z.delta_cents) FROM crm_commission_adjustment z WHERE z.tenant_id=x.tenant_id AND z.run_id=x.id),0)",nil,zero,nil,nil,zero,"''")+" FROM crm_commission_run x JOIN sys_tenant_member m ON m.tenant_id=x.tenant_id AND m.id=x.member_id WHERE x.tenant_id=:tenant AND (x.member_id=:member OR (:commissionManager AND (:contractReadAll OR (:contractReadDepartment AND m.department_id=:department))))";
            default:throw ApiError.bad("分析主题无效");
        }
    }
    private String cashSource(){
        return customerRow("r.id","r.receipt_number","k.owner_member_id","r.received_date","'RECEIPT'","r.amount_cents","0","NULL","0","NULL","NULL","0","''")+TradeService.FROM+" JOIN crm_receipt r ON r.tenant_id=k.tenant_id AND r.contract_id=k.id WHERE "+TradeService.READ+" AND r.status IN('CONFIRMED','REVERSED') UNION ALL "+customerRow("v.id","v.reversal_number","k.owner_member_id","v.reversal_date","'REVERSAL'","-v.amount_cents","0","NULL","0","NULL","NULL","0","''")+TradeService.FROM+" JOIN crm_receipt_reversal v ON v.tenant_id=k.tenant_id AND v.contract_id=k.id WHERE "+TradeService.READ;
    }
    private String rfmSource(){
        String segment="CASE WHEN coalesce(z.amount,0)<=0 THEN 'DEVELOPING' WHEN (cast(:toDate AS date)-z.recent)<=30 AND z.frequency>=2 THEN 'CORE' WHEN (cast(:toDate AS date)-z.recent)<=90 THEN 'ACTIVE' ELSE 'REACTIVATE' END";
        return customerRow("c.id","c.customer_name","c.owner_member_id","cast(:toDate AS date)",segment,"coalesce(z.amount,0)","0","NULL","0","NULL","cast(:toDate AS date)-z.recent","coalesce(z.frequency,0)","''")+" FROM crm_customer c JOIN sys_tenant_member m ON m.tenant_id=c.tenant_id AND m.id=c.owner_member_id LEFT JOIN (SELECT customer_id,sum(amount_cents) AS amount,max(event_date) FILTER(WHERE state='RECEIPT') AS recent,count(*) FILTER(WHERE state='RECEIPT') AS frequency FROM ("+cashSource()+") cash WHERE event_date BETWEEN :fromDate AND :toDate GROUP BY customer_id) z ON z.customer_id=c.id WHERE "+CustomerService.READ+" AND "+day("c.created_at")+"<=:toDate";
    }
    private String cte(AuthService.Actor a,String module){return "WITH raw AS ("+source(a,module)+"), data AS (SELECT * FROM raw WHERE event_date BETWEEN :fromDate AND :toDate AND (cast(:ownerFilter AS uuid) IS NULL OR owner_id=:ownerFilter) AND (cast(:departmentFilter AS uuid) IS NULL OR department_id=:departmentFilter) AND (:regionFilter='' OR region=:regionFilter) AND (:industryFilter='' OR industry=:industryFilter)) ";}
    private Map<String,Object> queryParams(AuthService.Actor a,Filter f){var p=params(a,f);p.put("commissionManager",a.tenant().roles().contains("SALES_MANAGER")&&a.tradeAccess().contracts().write());return p;}
    public Object dashboard(AuthService.Session s,Filter f){return guard.scoped(s,"reports",false,a->{
        var p=queryParams(a,f);var panels=new ArrayList<Map<String,Object>>();var owners=new LinkedHashMap<Object,Object>();var departments=new LinkedHashSet<Object>();var regions=new TreeSet<String>();var industries=new TreeSet<String>();
        for(var module:modules(a).entrySet()){
            String key=module.getKey(),ct=cte(a,key);
            var panel=one(ct+"SELECT count(*) AS count,coalesce(sum(amount_cents),0) AS amount_cents,sum(aux_cents) AS aux_cents,sum(cost_cents) AS cost_cents,sum(quantity) AS quantity,avg(score) AS satisfaction,count(score) AS feedback_count FROM data",p);
            panel.put("id",key);panel.put("name",module.getValue());panel.put("costVisible",cost(a,key));panel.put("canExport",exportable(a,key));
            panel.put("states",rows(ct+"SELECT state AS key,count(*) AS count,coalesce(sum(amount_cents),0) AS amount_cents FROM data GROUP BY state ORDER BY count(*) DESC,state",p));
            panel.put("trend",rows(ct+"SELECT date_trunc('month',event_date)::date AS month,count(*) AS count,coalesce(sum(amount_cents),0) AS amount_cents FROM data GROUP BY 1 ORDER BY 1",p));
            if(key.equals("customers")){panel.put("regions",groups(ct,"region",p));panel.put("industries",groups(ct,"industry",p));}
            if(key.equals("products"))panel.put("products",rows(ct+"SELECT category AS key,min(title) AS name,count(*) AS count,sum(quantity) AS quantity,sum(amount_cents) AS amount_cents,sum(cost_cents) AS cost_cents FROM data GROUP BY category ORDER BY sum(amount_cents) DESC,category LIMIT 20",p));
            if(key.equals("service"))panel.put("types",groups(ct,"category",p));
            panels.add(panel);
            // Choices derive only from already-authorized, date-filtered rows. No directory-wide membership leak.
            for(var r:rows(ct+"SELECT DISTINCT owner_id,owner_name,department_id,region,industry FROM data LIMIT 5001",p)){
                if(r.get("ownerId")!=null)owners.put(r.get("ownerId"),r.get("ownerName"));if(r.get("departmentId")!=null)departments.add(r.get("departmentId"));
                if(r.get("region") instanceof String v&&!v.isEmpty())regions.add(v);if(r.get("industry") instanceof String v&&!v.isEmpty())industries.add(v);
            }
        }
        var departmentOptions=new ArrayList<Map<String,Object>>();if(!departments.isEmpty()){var dp=new HashMap<>(p);dp.put("ids",departments);departmentOptions.addAll(rows("SELECT id,name FROM org_department WHERE tenant_id=:tenant AND id IN(:ids) ORDER BY name,id",dp));}
        return TradeService.map("from",f.from(),"to",f.to(),"asOf",Instant.now(),"timezone","Asia/Shanghai","panels",panels,"owners",owners.entrySet().stream().map(e->TradeService.map("id",e.getKey(),"name",e.getValue())).toList(),"departments",departmentOptions,"regions",regions,"industries",industries);
    });}
    private List<Map<String,Object>> rows(String sql,Map<String,?> p){return t.rows(sql,p).stream().map(r->{r.replaceAll((key,v)->key.endsWith("Cents")&&v instanceof Number?new java.math.BigDecimal(v.toString()).toBigIntegerExact().toString():v);return r;}).toList();}
    private Map<String,Object> one(String sql,Map<String,?> p){return rows(sql,p).getFirst();}
    private List<Map<String,Object>> groups(String ct,String field,Map<String,Object> p){return rows(ct+"SELECT "+field+" AS key,count(*) AS count,coalesce(sum(amount_cents),0) AS amount_cents FROM data GROUP BY "+field+" ORDER BY count(*) DESC,"+field+" LIMIT 20",p);}
    private String dimension(String dimension){return switch(dimension){case "state"->"state";case "region"->"region";case "industry"->"industry";case "product","type"->"category";case "month"->"to_char(event_date,'YYYY-MM')";case ""->null;default->throw ApiError.bad("下钻维度无效");};}
    private String selection(Map<String,Object> p,String dimension,String value){String field=dimension(dimension);if(value.length()>120)throw ApiError.bad("下钻条件过长");p.put("bucket",value);return field==null?"":" WHERE "+field+"=:bucket";}
    private void allow(AuthService.Actor a,String module){if(!modules(a).containsKey(module))throw ApiError.forbidden("当前角色没有此分析主题权限");}
    public Object detail(AuthService.Session s,Filter f,String module,String dimension,String value,int page,int size){TradeService.page(page,size,"");return guard.scoped(s,"reports",false,a->{allow(a,module);var p=queryParams(a,f);p.put("limit",size);p.put("offset",((long)page-1)*size);String ct=cte(a,module),where=selection(p,dimension,value);return new BusinessService.Page<>(rows(ct+"SELECT * FROM data"+where+" ORDER BY event_date DESC,id LIMIT :limit OFFSET :offset",p),page,size,t.db.queryForObject(ct+"SELECT count(*) FROM data"+where,p,Long.class));});}
    public byte[] export(AuthService.Session s,Filter f,String module,String dimension,String value,UUID trace){return guard.scoped(s,"reports",false,a->{allow(a,module);if(!exportable(a,module))throw ApiError.forbidden("当前角色没有此主题导出权限");var p=queryParams(a,f);String ct=cte(a,module),where=selection(p,dimension,value);long n=t.db.queryForObject(ct+"SELECT count(*) FROM data"+where,p,Long.class);if(n>5000)throw ApiError.conflict("导出最多5000行，请缩小筛选范围");var rows=rows(ct+"SELECT * FROM data"+where+" ORDER BY event_date DESC,id LIMIT 5000",p);var headers=new ArrayList<>(List.of("业务名称","客户","负责人","业务日期","状态","地区","行业","金额（分）"));if(cost(a,module))headers.add("预估成本（分）");if(module.equals("orders")&&a.tradeAccess().receiptRead())headers.add("关联可见合同累计净回款（分）");if(module.equals("commissions"))headers.add("累计提成调整（分）");if(module.equals("rfm"))headers.addAll(List.of("最近到账距期末天数","期内到账笔数"));if(module.equals("products"))headers.add("数量");if(module.equals("service"))headers.add("当前服务周期评分");
        var csv=new ArrayList<List<Object>>();for(var r:rows){var line=new ArrayList<Object>();for(String k:List.of("title","customerName","ownerName","eventDate","state","region","industry","amountCents"))line.add(k.equals("state")?AnalyticsLabels.state(String.valueOf(r.get(k))):r.get(k));if(cost(a,module))line.add(r.get("costCents"));if(module.equals("orders")&&a.tradeAccess().receiptRead()||module.equals("commissions"))line.add(r.get("auxCents"));if(module.equals("rfm")){line.add(r.get("recencyDays"));line.add(r.get("frequency"));}if(module.equals("products"))line.add(r.get("quantity"));if(module.equals("service"))line.add(r.get("score"));csv.add(line);}
        t.audit(a,"ANALYTICS_EXPORTED","ANALYTICS_"+module.toUpperCase(Locale.ROOT),null,n,trace);return CsvSupport.write(headers,csv);
    });}
}
