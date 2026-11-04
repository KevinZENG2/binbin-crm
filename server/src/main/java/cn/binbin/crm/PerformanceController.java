package cn.binbin.crm;
import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import java.time.LocalDate;import java.util.UUID;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/performance")
public class PerformanceController {
 private final AuthService auth;private final PerformanceService p;
 public PerformanceController(AuthService auth,PerformanceService p){this.auth=auth;this.p=p;}
 private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
 @GetMapping("/options") public Object options(HttpServletRequest r){return p.options(session(r));}
 @GetMapping("/targets") public Object targets(@RequestParam int year,HttpServletRequest r){return p.targets(session(r),year);}
 @PostMapping("/targets") public Object target(@Valid @RequestBody PerformanceService.TargetInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return p.saveTarget(session(r),null,i,key,RequestSafety.trace(r));}
 @PutMapping("/targets/{id}") public Object targetUpdate(@PathVariable UUID id,@Valid @RequestBody PerformanceService.TargetInput i,HttpServletRequest r){return p.saveTarget(session(r),id,i,null,RequestSafety.trace(r));}
 @GetMapping("/rules") public Object rules(HttpServletRequest r){return p.rules(session(r));}
 @PostMapping("/rules") public Object rule(@Valid @RequestBody PerformanceService.RuleInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return p.rule(session(r),null,i,key,RequestSafety.trace(r));}
 @PutMapping("/rules/{id}") public Object ruleUpdate(@PathVariable UUID id,@Valid @RequestBody PerformanceService.RuleInput i,HttpServletRequest r){return p.rule(session(r),id,i,null,RequestSafety.trace(r));}
 @GetMapping("/commissions") public Object runs(@RequestParam LocalDate period,HttpServletRequest r){return p.runs(session(r),period);}
 @PostMapping("/commissions/calculate") public Object calculate(@Valid @RequestBody PerformanceService.Calculate i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return p.calculate(session(r),i,key,RequestSafety.trace(r));}
 @PostMapping("/commissions/{id}/{action:review|lock|adjust}") public Object runAction(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody PerformanceService.Decision i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return p.runAction(session(r),id,action,i,key,RequestSafety.trace(r));}
 @GetMapping("/weeklies") public Object weeklies(@RequestParam LocalDate weekStart,HttpServletRequest r){return p.weeklies(session(r),weekStart);}
 @PostMapping("/weeklies") public Object weekly(@Valid @RequestBody PerformanceService.WeeklyInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return p.weeklySave(session(r),null,i,key,RequestSafety.trace(r));}
 @PutMapping("/weeklies/{id}") public Object weeklyUpdate(@PathVariable UUID id,@Valid @RequestBody PerformanceService.WeeklyInput i,HttpServletRequest r){return p.weeklySave(session(r),id,i,null,RequestSafety.trace(r));}
 @PostMapping("/weeklies/{id}/{action:submit|approve|reject}") public Object weeklyAction(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody PerformanceService.Decision i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return p.weeklyAction(session(r),id,action,i,key,RequestSafety.trace(r));}
 @GetMapping("/history/{type}/{id}") public Object history(@PathVariable String type,@PathVariable UUID id,HttpServletRequest r){return p.history(session(r),type,id);}
}
