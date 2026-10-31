package cn.binbin.crm;
import jakarta.servlet.http.*;import java.util.Set;import org.springframework.stereotype.Component;import org.springframework.web.servlet.*;import org.springframework.web.servlet.config.annotation.*;import org.springframework.transaction.support.TransactionTemplate;
@Component
public class P7ObjectInterceptor implements HandlerInterceptor,WebMvcConfigurer {
 private final AuthService auth;private final P7Permissions p;private final TransactionTemplate tx;
 public P7ObjectInterceptor(AuthService auth,P7Permissions p,TransactionTemplate tx){this.auth=auth;this.p=p;this.tx=tx;}
 public void addInterceptors(InterceptorRegistry r){r.addInterceptor(this).addPathPatterns("/api/v1/sales-orders/**","/api/v1/finance/**","/api/v1/performance/**");}
 public boolean preHandle(HttpServletRequest r,HttpServletResponse response,Object handler){String path=r.getRequestURI(),type=path.contains("/sales-orders")?"ORDER":path.contains("/invoices")?"INVOICE":path.contains("/expenses")?"EXPENSE":path.contains("/targets")?"TARGET":path.contains("/commissions")||path.contains("/rules")?"COMMISSION":path.contains("/weeklies")?"WEEKLY":path.contains("/accounts")?"FUND":null;if(path.contains("/history/")){String history=path.substring(path.indexOf("/history/")+9).split("/")[0];type=switch(history){case "INVOICE","EXPENSE","TARGET","COMMISSION","WEEKLY"->history;case "RULE"->"COMMISSION";default->type;};}final String objectType=type;if(objectType!=null)tx.executeWithoutResult(st->{var a=auth.actor(auth.workspace(r));p.check(a,objectType,!Set.of("GET","HEAD").contains(r.getMethod()));});return true;}
}
