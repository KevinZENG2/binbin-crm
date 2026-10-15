package cn.binbin.crm;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DeliveryGuard {
    private final NamedParameterJdbcTemplate db;private final TransactionTemplate tx,snapshot;private final AuthService auth;
    public DeliveryGuard(NamedParameterJdbcTemplate db,TransactionTemplate tx,AuthService auth){this.db=db;this.tx=tx;this.auth=auth;this.snapshot=new TransactionTemplate(tx.getTransactionManager());this.snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public <T> T scoped(AuthService.Session s,String module,boolean write,Function<AuthService.Actor,T> fn){return (module.equals("export")||module.equals("reports")?snapshot:tx).execute(status->{
        if(write&&s.tenantId()!=null)db.queryForObject("SELECT id FROM sys_tenant WHERE id=:tenant FOR UPDATE",Map.of("tenant",s.tenantId()),java.util.UUID.class);
        var a=auth.actor(s);if(a.supportGrantId()!=null)throw ApiError.forbidden("当前支持授权不包含报表、导入导出、附件和提醒");var p=a.deliveryAccess();
        boolean allowed=switch(module){case "reports"->p.reports();case "export"->p.export();case "import"->p.customerImport()&&a.canReadCustomers();case "attachment"->p.attachmentRead()&&a.canReadCustomers()&&(!write||p.attachmentWrite());case "notification"->a.businessAccess().tasks().read();default->false;};
        if(!allowed)throw ApiError.forbidden("当前角色没有此项功能权限");
        if(write&&!a.tenant().subscriptionEnd().isAfter(Instant.now()))throw ApiError.forbidden("订阅已到期，当前仅允许查看");
        if(write&&module.equals("import"))a.requireWrite();
        db.queryForObject("SELECT set_config('crm.tenant_id',:tenant,true)",Map.of("tenant",a.tenant().id().toString()),String.class);return fn.apply(a);
    });}
}
