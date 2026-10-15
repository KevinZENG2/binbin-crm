package cn.binbin.crm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class TradeController {
 private final AuthService auth;private final TradeService trade;private final FinanceService finance;
 public TradeController(AuthService auth,TradeService trade,FinanceService finance){this.auth=auth;this.trade=trade;this.finance=finance;}
 private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
 private Object csv(String name,byte[] data){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"").body(data);}
 @GetMapping("/contracts") public Object contracts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID customerId,@RequestParam(defaultValue="false") boolean collectable,HttpServletRequest r){return trade.contracts(session(r),page,pageSize,q,status,customerId,collectable);}
 @GetMapping("/contracts/quote-options") public Object options(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return trade.quoteOptions(session(r),page,pageSize,q,customerId);}
 @GetMapping("/contracts/quote-basis/{id}") public Object basis(@PathVariable UUID id,HttpServletRequest r){return trade.basis(session(r),id);}
 @GetMapping("/contracts/export") public Object exportContracts(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return csv("crm-contracts.csv",trade.export(session(r),q,status,customerId,RequestSafety.trace(r)));}
 @GetMapping("/contracts/{id}") public Object contract(@PathVariable UUID id,HttpServletRequest r){return trade.contract(session(r),id);}
 @PostMapping("/contracts") public Object create(@Valid @RequestBody TradeService.ContractInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return trade.create(session(r),i,key,RequestSafety.trace(r));}
 @PutMapping("/contracts/{id}") public Object edit(@PathVariable UUID id,@Valid @RequestBody TradeService.EditInput i,HttpServletRequest r){return trade.edit(session(r),id,i,RequestSafety.trace(r));}
 @PostMapping("/contracts/{id}/{action:sign|activate|terminate|void}") public Object action(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody TradeService.ActionInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return trade.action(session(r),id,action,i,key,RequestSafety.trace(r));}
 @PostMapping("/contracts/{id}/amend") public Object amend(@PathVariable UUID id,@Valid @RequestBody TradeService.AmendInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return trade.amend(session(r),id,i,key,RequestSafety.trace(r));}
 @GetMapping("/contracts/{id}/history") public Object history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return trade.history(session(r),id,page,pageSize);}
 @GetMapping("/contracts/{id}/versions/{version}") public Object version(@PathVariable UUID id,@PathVariable long version,HttpServletRequest r){return trade.revision(session(r),id,version);}
 @GetMapping("/receipts") public Object receipts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID contractId,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,HttpServletRequest r){return finance.receipts(session(r),page,pageSize,q,status,contractId,from,to);}
 @GetMapping("/receipts/{id}") public Object receipt(@PathVariable UUID id,HttpServletRequest r){return finance.receipt(session(r),id);}
 @PostMapping("/receipts") public Object register(@Valid @RequestBody FinanceService.ReceiptInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return finance.create(session(r),i,key,RequestSafety.trace(r));}
 @PutMapping("/receipts/{id}") public Object editReceipt(@PathVariable UUID id,@Valid @RequestBody FinanceService.ReceiptInput i,HttpServletRequest r){return finance.edit(session(r),id,i,RequestSafety.trace(r));}
 @PostMapping("/receipts/{id}/confirm") public Object confirm(@PathVariable UUID id,@Valid @RequestBody FinanceService.DecisionInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return finance.confirm(session(r),id,i,key,RequestSafety.trace(r));}
 @PostMapping("/receipts/{id}/void") public Object cancel(@PathVariable UUID id,@Valid @RequestBody FinanceService.DecisionInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return finance.cancel(session(r),id,i,key,RequestSafety.trace(r));}
 @PostMapping("/receipts/{id}/reverse") public Object reverse(@PathVariable UUID id,@Valid @RequestBody FinanceService.ReverseInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return finance.reverse(session(r),id,i,key,RequestSafety.trace(r));}
 @GetMapping("/receipts/{id}/history") public Object receiptHistory(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return finance.history(session(r),id,page,pageSize);}
 @GetMapping("/receipts/{id}/versions/{version}") public Object receiptVersion(@PathVariable UUID id,@PathVariable long version,HttpServletRequest r){return finance.revision(session(r),id,version);}
 @GetMapping("/trade/reconciliation") public Object reconcile(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return finance.reconciliation(session(r),page,pageSize,from,to,q,status,customerId);}
 @GetMapping("/trade/reconciliation/export") public Object export(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return csv("crm-reconciliation.csv",finance.export(session(r),from,to,q,status,customerId,RequestSafety.trace(r)));}
}
