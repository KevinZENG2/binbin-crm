package cn.binbin.crm;
import java.util.*;import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/automation")
public class AutomationController {
 private final AuthService auth;private final AutomationService service;public AutomationController(AuthService a,AutomationService s){auth=a;service=s;}
 private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
 @GetMapping public Object rules(HttpServletRequest r){return service.overview(session(r));}
 @PostMapping("/rules") public Object create(@Valid @RequestBody AutomationService.Input i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.save(session(r),null,i,key,RequestSafety.trace(r));}
 @PutMapping("/rules/{id}") public Object edit(@PathVariable UUID id,@Valid @RequestBody AutomationService.Input i,HttpServletRequest r){return service.save(session(r),id,i,null,RequestSafety.trace(r));}
 @PostMapping("/rules/{id}/{action:publish|pause}") public Object state(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody AutomationService.Decision i,HttpServletRequest r){return service.state(session(r),id,action,i,RequestSafety.trace(r));}
 @PostMapping("/rules/{id}/execute") public Object execute(@PathVariable UUID id,@Valid @RequestBody AutomationService.Manual i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.manual(session(r),id,i,key,RequestSafety.trace(r));}
 @GetMapping("/rules/{id}/revisions") public Object revisions(@PathVariable UUID id,HttpServletRequest r){return service.revisions(session(r),id);}
 @GetMapping("/runs") public Object runs(@RequestParam(defaultValue="1") int page,HttpServletRequest r){return service.runs(session(r),page);}
 @GetMapping("/runs/{id}") public Object run(@PathVariable UUID id,HttpServletRequest r){return service.run(session(r),id);}
 @PostMapping("/runs/{id}/retry") public Object retry(@PathVariable UUID id,HttpServletRequest r){return service.retry(session(r),id,RequestSafety.trace(r));}
 @PostMapping("/run-due") public Object pump(HttpServletRequest r){return service.pump(session(r));}
}
