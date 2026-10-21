package cn.binbin.crm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class AiWorkspaceController {
 final AuthService auth;final AiService ai;final AiQualityService quality;final AiProposalService proposals;
 public AiWorkspaceController(AuthService auth,AiService ai,AiQualityService quality,AiProposalService proposals){this.auth=auth;this.ai=ai;this.quality=quality;this.proposals=proposals;}
 private AuthService.Session session(HttpServletRequest r,boolean write){var s=auth.workspace(r);if(write)auth.csrf(s,r);return s;}
 @GetMapping("/ai/tasks") Object tasks(HttpServletRequest r){return ai.catalog(session(r,false));}
 @GetMapping("/ai/tasks/{task}/sources") Object sources(@PathVariable String task,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r){return ai.options(session(r,false),task,page,pageSize,q);}
 @GetMapping("/ai/runs/{id}/evaluations") Object evaluations(@PathVariable UUID id,HttpServletRequest r){return ai.evaluations(session(r,false),id);}
 @PostMapping("/ai/runs/{id}/review") Object review(@PathVariable UUID id,@Valid @RequestBody AiQualityService.ReviewInput i,HttpServletRequest r){return ai.review(session(r,true),id,i,RequestSafety.trace(r));}
 @PostMapping("/ai/runs/{id}/proposals") Object proposal(@PathVariable UUID id,@Valid @RequestBody AiProposalService.DraftInput i,HttpServletRequest r){return proposals.prepare(session(r,true),id,i,RequestSafety.trace(r));}
 @PostMapping("/ai/proposals/{id}/confirm") Object confirm(@PathVariable UUID id,@Valid @RequestBody AiProposalService.ConfirmInput i,HttpServletRequest r){return proposals.confirm(session(r,true),id,i,RequestSafety.trace(r));}
 @PostMapping("/ai/proposals/{id}/cancel") Object cancel(@PathVariable UUID id,@RequestParam long expectedVersion,HttpServletRequest r){return proposals.cancel(session(r,true),id,expectedVersion,RequestSafety.trace(r));}
 @GetMapping("/ai/quality-settings") Object settings(HttpServletRequest r){return quality.settings(session(r,false));}
 @PutMapping("/ai/quality-settings") Object policy(@Valid @RequestBody AiQualityService.PolicyInput i,HttpServletRequest r){return quality.savePolicy(session(r,true),i,RequestSafety.trace(r));}
 @GetMapping("/platform/ai/prompts") Object prompts(HttpServletRequest r){return quality.platform(session(r,false));}
 @PostMapping("/platform/ai/prompts") Object prompt(@Valid @RequestBody AiQualityService.PromptInput i,HttpServletRequest r){return quality.createPrompt(session(r,true),i,RequestSafety.trace(r));}
 @PutMapping("/platform/ai/releases/{task}") Object release(@PathVariable String task,@Valid @RequestBody AiQualityService.ReleaseInput i,HttpServletRequest r){return quality.release(session(r,true),task,i,false,RequestSafety.trace(r));}
 @PostMapping("/platform/ai/releases/{task}/rollback") Object rollback(@PathVariable String task,@Valid @RequestBody AiQualityService.ReleaseInput i,HttpServletRequest r){return quality.release(session(r,true),task,i,true,RequestSafety.trace(r));}
 @PostMapping("/platform/ai/rule-test") Object ruleTest(@Valid @RequestBody AiQualityService.RuleTestInput i,HttpServletRequest r){return quality.ruleTest(session(r,true),i);}
}
