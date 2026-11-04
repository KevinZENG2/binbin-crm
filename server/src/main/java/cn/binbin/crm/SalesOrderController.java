package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/sales-orders")
public class SalesOrderController {
 private final AuthService auth;private final SalesOrderService orders;
 public SalesOrderController(AuthService auth,SalesOrderService orders){this.auth=auth;this.orders=orders;}
 private AuthService.Session session(HttpServletRequest request){var s=auth.workspace(request);if(!"GET".equals(request.getMethod()))auth.csrf(s,request);return s;}
 @GetMapping public Object list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return orders.list(session(r),page,pageSize,q,status,customerId);}
 @GetMapping("/quote-options") public Object quotes(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return orders.quoteOptions(session(r),page,pageSize,q,customerId);}
 @GetMapping("/{id}") public Object one(@PathVariable UUID id,HttpServletRequest r){return orders.one(session(r),id);}
 @PostMapping public Object create(@Valid @RequestBody SalesOrderService.Create input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return orders.create(session(r),input,key,RequestSafety.trace(r));}
 @PutMapping("/{id}") public Object edit(@PathVariable UUID id,@Valid @RequestBody SalesOrderService.Edit input,HttpServletRequest r){return orders.edit(session(r),id,input,RequestSafety.trace(r));}
 @PostMapping("/{id}/{action:confirm|void}") public Object action(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody SalesOrderService.Decision input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return orders.action(session(r),id,action,input,key,RequestSafety.trace(r));}
 @PutMapping("/{id}/fulfillment") public Object fulfill(@PathVariable UUID id,@Valid @RequestBody SalesOrderService.Fulfillment input,HttpServletRequest r){return orders.fulfill(session(r),id,input,RequestSafety.trace(r));}
 @GetMapping("/{id}/history") public Object history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return orders.history(session(r),id,page,pageSize);}
}
