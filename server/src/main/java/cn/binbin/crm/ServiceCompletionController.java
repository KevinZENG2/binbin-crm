package cn.binbin.crm;
import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import java.time.LocalDate;import java.util.UUID;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class ServiceCompletionController {
 private final AuthService auth;private final ServiceCompletionService f;
 public ServiceCompletionController(AuthService auth,ServiceCompletionService f){this.auth=auth;this.f=f;}
 private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
 @GetMapping("/service/tickets/{id}") public Object ticket(@PathVariable UUID id,HttpServletRequest r){return f.detail(session(r),id);}
 @PostMapping("/service/tickets/{id}/dispatch") public Object dispatch(@PathVariable UUID id,@Valid @RequestBody ServiceCompletionService.Dispatch i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return f.dispatch(session(r),id,i,key,RequestSafety.trace(r));}
 @PostMapping("/service/tickets/{id}/feedback-link") public Object link(@PathVariable UUID id,@Valid @RequestBody ServiceDeskService.Change i,HttpServletRequest r){return f.feedbackLink(session(r),id,i,RequestSafety.trace(r));}
 @GetMapping("/service/statistics") public Object report(@RequestParam LocalDate from,@RequestParam LocalDate to,HttpServletRequest r){return f.report(session(r),from,to);}
 @GetMapping("/service-feedback/{token}") public Object feedback(@PathVariable String token){return f.feedbackView(token);}
 @PostMapping("/service-feedback/{token}") public Object submit(@PathVariable String token,@Valid @RequestBody ServiceCompletionService.Feedback i){return f.submitFeedback(token,i);}
}
