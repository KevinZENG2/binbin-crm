package cn.binbin.crm;
import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import java.util.UUID;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/finance")
public class FinanceWorkspaceController {
 private final AuthService auth;private final FinanceWorkspaceService f;
 public FinanceWorkspaceController(AuthService auth,FinanceWorkspaceService f){this.auth=auth;this.f=f;}
 private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
 @GetMapping("/account-options") public Object options(HttpServletRequest r){return f.accountOptions(session(r));}
 @GetMapping("/accounts") public Object accounts(HttpServletRequest r){return f.accounts(session(r));}
 @PostMapping("/accounts") public Object createAccount(@Valid @RequestBody FinanceWorkspaceService.AccountInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return f.createAccount(session(r),i,key,RequestSafety.trace(r));}
 @PutMapping("/accounts/{id}") public Object state(@PathVariable UUID id,@Valid @RequestBody FinanceWorkspaceService.AccountState i,HttpServletRequest r){return f.accountState(session(r),id,i,RequestSafety.trace(r));}
 @GetMapping("/accounts/{id}/entries") public Object entries(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return f.ledger(session(r),id,page,pageSize);}
 @GetMapping("/invoices") public Object invoices(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID contractId,HttpServletRequest r){return f.invoices(session(r),page,pageSize,q,contractId);}
 @GetMapping("/invoices/{id}") public Object invoice(@PathVariable UUID id,HttpServletRequest r){return f.invoice(session(r),id);}
 @PostMapping("/invoices") public Object createInvoice(@Valid @RequestBody FinanceWorkspaceService.InvoiceInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return f.invoiceCreate(session(r),i,key,RequestSafety.trace(r));}
 @PostMapping("/invoices/{id}/{action:submit|approve|reject|issue|void|red}") public Object invoiceAction(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody FinanceWorkspaceService.InvoiceAction i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return f.invoiceAction(session(r),id,action,i,key,RequestSafety.trace(r));}
 @GetMapping("/expenses") public Object expenses(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return f.expenses(session(r),page,pageSize);}
 @PostMapping("/expenses") public Object expense(@Valid @RequestBody FinanceWorkspaceService.ExpenseInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return f.expenseCreate(session(r),i,key,RequestSafety.trace(r));}
 @PostMapping("/expenses/{id}/{action:confirm|void|reverse}") public Object expenseAction(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody FinanceWorkspaceService.InvoiceAction i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return f.expenseAction(session(r),id,action,i,key,RequestSafety.trace(r));}
 @GetMapping("/orders/{id}/balances") public Object balance(@PathVariable UUID id,HttpServletRequest r){return f.orderBalances(session(r),id);}
 @GetMapping("/history/{type}/{id}") public Object history(@PathVariable String type,@PathVariable UUID id,HttpServletRequest r){return f.history(session(r),type,id);}
}
