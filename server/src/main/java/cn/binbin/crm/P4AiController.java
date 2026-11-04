package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/ai")
public class P4AiController {
 private final AuthService auth;private final AiService ai;
 public P4AiController(AuthService auth,AiService ai){this.auth=auth;this.ai=ai;}
 private AuthService.Session session(HttpServletRequest r,boolean write){var s=auth.workspace(r);if(write)auth.csrf(s,r);return s;}
 @GetMapping("/status") Object status(HttpServletRequest r){return ai.status(session(r,false));}
 @GetMapping("/runs") Object runs(HttpServletRequest r){return ai.history(session(r,false));}
 @PostMapping("/preview") Object preview(@Valid @RequestBody AiService.GenerateInput input,HttpServletRequest r){return ai.preview(session(r,true),input);}
 @PostMapping("/runs") Object generate(@Valid @RequestBody AiService.GenerateInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ai.generate(session(r,true),input,key,RequestSafety.trace(r));}
 @PutMapping("/runs/{id}/feedback") Object feedback(@PathVariable UUID id,@Valid @RequestBody AiService.FeedbackInput input,HttpServletRequest r){return ai.feedback(session(r,true),id,input);}
}
