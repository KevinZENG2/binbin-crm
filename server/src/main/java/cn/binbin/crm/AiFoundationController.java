package cn.binbin.crm;
import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import java.util.UUID;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class AiFoundationController {
 final AuthService auth;final AiFoundationService service;
 public AiFoundationController(AuthService auth,AiFoundationService service){this.auth=auth;this.service=service;}
 private AuthService.Session session(HttpServletRequest r,boolean write){var s=auth.workspace(r);if(write)auth.csrf(s,r);return s;}
 @GetMapping("/ai/settings") public Object settings(HttpServletRequest r){return service.overview(session(r,false));}
 @PutMapping("/ai/settings") public Object save(@Valid @RequestBody AiFoundationService.PolicyInput i,HttpServletRequest r){return service.savePolicy(session(r,true),i,RequestSafety.trace(r));}
 @GetMapping("/platform/ai") public Object platform(HttpServletRequest r){return service.platform(session(r,false));}
 @PostMapping("/platform/ai/models") public Object model(@Valid @RequestBody AiFoundationService.ModelInput i,HttpServletRequest r){return service.saveModel(session(r,true),i,RequestSafety.trace(r));}
 @PutMapping("/platform/ai/plans/{id}") public Object plan(@PathVariable UUID id,@Valid @RequestBody AiFoundationService.PlanInput i,HttpServletRequest r){return service.savePlan(session(r,true),id,i,RequestSafety.trace(r));}
}
