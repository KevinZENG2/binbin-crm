package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class SalesController {
    private final AuthService auth;private final SalesService sales;
    public SalesController(AuthService auth,SalesService sales){this.auth=auth;this.sales=sales;}
    private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
    @GetMapping("/sales/summary") public Object summary(HttpServletRequest r){return sales.summary(session(r));}
    @GetMapping("/opportunities") public Object opportunities(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String stage,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return sales.opportunities(session(r),page,pageSize,q,stage,customerId);}
    @GetMapping("/opportunities/board") public Object board(@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return sales.board(session(r),q,customerId);}
    @GetMapping("/opportunities/forecast") public Object forecast(@RequestParam LocalDate from,@RequestParam LocalDate to,HttpServletRequest r){return sales.forecast(session(r),from,to);}
    @GetMapping("/opportunities/owners") public Object opportunityOwners(HttpServletRequest r){return sales.owners(session(r),"opportunity");}
    @GetMapping("/opportunities/{id}") public Object opportunity(@PathVariable UUID id,HttpServletRequest r){return sales.opportunity(session(r),id);}
    @GetMapping("/opportunities/{id}/history") public Object history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return sales.history(session(r),id,page,pageSize);}
    @GetMapping("/opportunities/{id}/lines") public Object lines(@PathVariable UUID id,HttpServletRequest r){return sales.opportunityLines(session(r),id);}
    @PutMapping("/opportunities/{id}/lines") public Object saveLines(@PathVariable UUID id,@Valid @RequestBody SalesService.OpportunityLines input,HttpServletRequest r){return sales.saveOpportunityLines(session(r),id,input,RequestSafety.trace(r));}
    @PostMapping("/opportunities") public Object createOpportunity(@Valid @RequestBody SalesService.OpportunityCreate input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ResponseEntity.status(201).body(sales.createOpportunity(session(r),input,key,RequestSafety.trace(r)));}
    @PatchMapping("/opportunities/{id}") public Object editOpportunity(@PathVariable UUID id,@Valid @RequestBody SalesService.OpportunityEdit input,HttpServletRequest r){return sales.editOpportunity(session(r),id,input,RequestSafety.trace(r));}
    @PostMapping("/opportunities/{id}/stage") public Object stage(@PathVariable UUID id,@Valid @RequestBody SalesService.StageInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return sales.stage(session(r),id,input,key,RequestSafety.trace(r));}
    @GetMapping("/tasks") public Object tasks(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="PENDING") String status,@RequestParam(defaultValue="false") boolean mine,@RequestParam(required=false) UUID customerId,@RequestParam(required=false) UUID opportunityId,HttpServletRequest r){return sales.tasks(session(r),page,pageSize,q,status,mine,customerId,opportunityId);}
    @GetMapping("/tasks/owners") public Object taskOwners(HttpServletRequest r){return sales.owners(session(r),"task");}
    @GetMapping("/tasks/{id}") public Object task(@PathVariable UUID id,HttpServletRequest r){return sales.task(session(r),id);}
    @PostMapping("/tasks") public Object createTask(@Valid @RequestBody SalesService.TaskCreate input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ResponseEntity.status(201).body(sales.createTask(session(r),input,key,RequestSafety.trace(r)));}
    @PatchMapping("/tasks/{id}") public Object editTask(@PathVariable UUID id,@Valid @RequestBody SalesService.TaskEdit input,HttpServletRequest r){return sales.editTask(session(r),id,input,RequestSafety.trace(r));}
    @PostMapping("/tasks/{id}/complete") public Object complete(@PathVariable UUID id,@Valid @RequestBody SalesService.VersionInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return sales.taskState(session(r),id,input,key,true,RequestSafety.trace(r));}
    @PostMapping("/tasks/{id}/reopen") public Object reopen(@PathVariable UUID id,@Valid @RequestBody SalesService.VersionInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return sales.taskState(session(r),id,input,key,false,RequestSafety.trace(r));}
    @GetMapping("/activities") public Object activities(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID customerId,@RequestParam(required=false) UUID opportunityId,HttpServletRequest r){return sales.activities(session(r),page,pageSize,q,customerId,opportunityId);}
    @GetMapping("/activities/{id}") public Object activity(@PathVariable UUID id,HttpServletRequest r){return sales.activity(session(r),id);}
    @PostMapping("/activities") public Object createActivity(@Valid @RequestBody SalesService.ActivityCreate input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ResponseEntity.status(201).body(sales.createActivity(session(r),input,key,RequestSafety.trace(r)));}
}
