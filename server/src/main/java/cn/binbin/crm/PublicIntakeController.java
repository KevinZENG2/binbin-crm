package cn.binbin.crm;
import java.util.*;import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class PublicIntakeController {
 private final AuthService auth;private final PublicIntakeService p;public PublicIntakeController(AuthService a,PublicIntakeService p){auth=a;this.p=p;}
 private AuthService.Session write(HttpServletRequest r){var s=auth.workspace(r);auth.csrf(s,r);return s;}
 @GetMapping("/workspace/public-forms") public Object list(HttpServletRequest r){return p.list(auth.workspace(r));}
 @PostMapping("/workspace/public-forms") public Object create(@Valid @RequestBody PublicIntakeService.Create i,HttpServletRequest r){return p.create(write(r),i,RequestSafety.trace(r));}
 @DeleteMapping("/workspace/public-forms/{id}") public Object revoke(@PathVariable UUID id,@RequestParam long expectedVersion,HttpServletRequest r){return p.revoke(write(r),id,expectedVersion,RequestSafety.trace(r));}
 @GetMapping("/public-intake/{token}") public Object describe(@PathVariable String token){return p.describe(token);}
 @PostMapping("/public-intake/{token}") public Object submit(@PathVariable String token,@Valid @RequestBody PublicIntakeService.Submission i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return p.submit(token,i,key,r.getRemoteAddr(),RequestSafety.trace(r));}
}
