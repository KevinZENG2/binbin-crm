package cn.binbin.crm;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component @Order(2) @ConditionalOnProperty(name="crm.seed.enabled",havingValue="true")
public class DevelopmentSeed implements ApplicationRunner {
    private final Flyway flyway;private final String password;private final String stage;
    public DevelopmentSeed(Flyway flyway,@Value("${crm.seed.password}") String password,@Value("${crm.stage}") String stage){this.flyway=flyway;this.password=password;this.stage=stage;}
    public static UUID id(int n) { return UUID.fromString("00000000-0000-4000-8000-"+String.format("%012d",n)); }
    public void run(ApplicationArguments args) {
        if(!java.util.Set.of("development","test").contains(stage) || password.length()<14 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new IllegalStateException("Test seed requires test/development stage and an explicit 14-72 byte password");
        var data=flyway.getConfiguration().getDataSource();var db=new NamedParameterJdbcTemplate(data);var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        String hash=new BCryptPasswordEncoder(12).encode(password);
        tx.executeWithoutResult(status -> {
            String[] names={"owner","alice","bob","viewer","tech-admin","platform-operator"};String[] display={"示例企业管理员","销售小林","销售小周","只读分析员","企业系统管理员","平台运营人员"};
            for(int i=0;i<names.length;i++)db.update("INSERT INTO iam_user(id,username,display_name,password_hash,is_platform_operator) VALUES(:id,:name,:display,:hash,:operator) ON CONFLICT DO NOTHING",Map.of("id",id(100+i),"name",names[i],"display",display[i],"hash",hash,"operator",i==5));
            for(int i=1;i<=3;i++) {
                db.update("INSERT INTO sys_tenant(id,code,name,status,subscription_end,plan_id) VALUES(:id,:code,:name,'ACTIVE',:end,:plan) ON CONFLICT DO NOTHING",Map.of("id",id(i),"code","test-"+i,"name",i==1?"星澜科技 · 测试企业":i==2?"森屿制造 · 测试企业":"到期企业 · 权限验证","end",Timestamp.from(Instant.now().plusSeconds(i==3?-86400:90*86400L)),"plan",id(501)));
                db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",id(i).toString()),String.class);
                for(String role:SaasService.ROLES) {
                    var access=CustomerAccess.defaults(java.util.Set.of(role));var delivery=DeliveryAccess.defaults(java.util.Set.of(role));var commerce=CommerceAccess.defaults(java.util.Set.of(role));var trade=TradeAccess.defaults(java.util.Set.of(role));
                    db.update("INSERT INTO sys_role_policy(tenant_id,role_code,can_read,can_write,record_scope,credit_access,description_access,can_share,contact_can_read,contact_can_write,lead_can_read,lead_can_write,opportunity_can_read,opportunity_can_write,task_can_read,task_can_write,activity_can_read,activity_can_write,report_can_read,export_can_read,import_can_write,attachment_can_read,attachment_can_write,product_can_read,product_can_write,quote_can_read,quote_can_write,quote_pdf_can_export,quote_can_approve,contract_can_read,contract_can_write,contract_can_amend,receipt_can_read,receipt_can_write,receipt_can_confirm,receipt_can_reverse,trade_can_export) VALUES(:tenant,:role,:read,:write,:scope,:credit,:description,:share,:read,:write,:read,:write,:read,:write,:read,:write,:read,:write,:report,:export,:import,:fileRead,:fileWrite,:productRead,:productWrite,:quoteRead,:quoteWrite,:quotePdf,:quoteApprove,:contractRead,:contractWrite,:contractAmend,:receiptRead,:receiptWrite,:receiptConfirm,:receiptReverse,:tradeExport) ON CONFLICT DO NOTHING",SaasService.parameters("tenant",id(i),"role",role,"read",access.read(),"write",access.write(),"scope",access.readAll()?"ALL":"OWN_SHARED","credit",access.creditWrite()?"WRITE":access.creditRead()?"READ":"NONE","description",access.descriptionWrite()?"WRITE":access.descriptionRead()?"READ":"NONE","share",access.share(),"report",delivery.reports(),"export",delivery.export(),"import",delivery.customerImport(),"fileRead",delivery.attachmentRead(),"fileWrite",delivery.attachmentWrite(),"productRead",commerce.productRead(),"productWrite",commerce.productWrite(),"quoteRead",commerce.quotes().read(),"quoteWrite",commerce.quotes().write(),"quotePdf",commerce.pdfExport(),"quoteApprove",commerce.approve(),"contractRead",trade.contracts().read(),"contractWrite",trade.contracts().write(),"contractAmend",trade.amend(),"receiptRead",trade.receiptRead(),"receiptWrite",trade.receiptWrite(),"receiptConfirm",trade.confirm(),"receiptReverse",trade.reverse(),"tradeExport",trade.export()));
                }
            }
            for(int tenant=1;tenant<=3;tenant++){db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",id(tenant).toString()),String.class);P3Access.initialize(db,id(tenant));P4Access.initialize(db,id(tenant));P4Defaults.initialize(db,id(tenant),tenant==1?"星澜科技":tenant==2?"森屿制造":"到期企业");}
            for(int i=0;i<5;i++)member(db,1,200+i,100+i,display[i],i==0?new String[]{"TENANT_ADMIN","SALES_MANAGER"}:i==3?new String[]{"VIEWER"}:i==4?new String[]{"TENANT_ADMIN"}:new String[]{"SALES"});
            member(db,2,210,100,display[0],new String[]{"TENANT_ADMIN","SALES_MANAGER"});member(db,3,220,100,display[0],new String[]{"SALES_MANAGER"});
            for(int tenant=1;tenant<=3;tenant++) {
                db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",id(tenant).toString()),String.class);
                if(tenant==1) { customer(db,1,301,201,"华熙装备（虚构测试客户）");customer(db,1,302,202,"北辰能源（虚构测试客户）");customer(db,1,303,200,"云桥电子（共享只读示例）");db.update("INSERT INTO crm_customer_share(tenant_id,customer_id,member_id,can_write) VALUES(:tenant,:customer,:member,false) ON CONFLICT DO NOTHING",Map.of("tenant",id(1),"customer",id(303),"member",id(201))); }
                else customer(db,tenant,300+tenant+2,tenant==2?210:220,tenant==2?"森屿企业专属客户（虚构）":"到期后的可查看客户（虚构）");
            }
            db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",id(1).toString()),String.class);db.update("UPDATE sys_tenant_profile SET portal_enabled=true,support_email='support@example.test',updated_at=now() WHERE tenant_id=:tenant",Map.of("tenant",id(1)));db.update("INSERT INTO portal_account(id,tenant_id,customer_id,email,email_norm,display_name,password_hash,status,must_change_password,created_by_member_id) VALUES(:id,:tenant,:customer,'contact@huaxi.example.test','contact@huaxi.example.test','华熙装备联系人',:hash,'ACTIVE',false,:member) ON CONFLICT DO NOTHING",Map.of("id",id(800),"tenant",id(1),"customer",id(301),"hash",hash,"member",id(200)));
        });
    }
    private void member(NamedParameterJdbcTemplate db,int tenant,int member,int user,String name,String[] roles) {
        db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",id(tenant).toString()),String.class);
        String title=java.util.Arrays.asList(roles).contains("TENANT_ADMIN")?"企业管理员":java.util.Arrays.asList(roles).contains("SALES_MANAGER")?"销售经理":java.util.Arrays.asList(roles).contains("SALES")?"客户经理":"分析员";
        db.update("INSERT INTO sys_tenant_member(id,tenant_id,user_id,display_name,employee_code,job_title,sales_region,service_status) VALUES(:id,:tenant,:user,:name,:code,:title,:region,'AVAILABLE') ON CONFLICT DO NOTHING",Map.of("id",id(member),"tenant",id(tenant),"user",id(user),"name",name,"code","EMP-"+member,"title",title,"region",tenant==1?"华东区":"全国"));
        db.update("INSERT INTO crm_sales_resource_policy(id,tenant_id,member_id) VALUES(:id,:tenant,:member) ON CONFLICT DO NOTHING",Map.of("id",UUID.randomUUID(),"tenant",id(tenant),"member",id(member)));
        for(String role:roles)db.update("INSERT INTO sys_member_role(tenant_id,member_id,role_code) VALUES(:tenant,:member,:role) ON CONFLICT DO NOTHING",Map.of("tenant",id(tenant),"member",id(member),"role",role));
    }
    private void customer(NamedParameterJdbcTemplate db,int tenant,int customer,int owner,String name) { db.update("INSERT INTO crm_customer(id,tenant_id,customer_name,industry,level_code,source_code,region,owner_member_id,description) VALUES(:id,:tenant,:name,'制造业','B','测试初始化','上海',:owner,'仅供开发验收，不代表真实企业') ON CONFLICT DO NOTHING",Map.of("id",id(customer),"tenant",id(tenant),"owner",id(owner),"name",name)); }
}
