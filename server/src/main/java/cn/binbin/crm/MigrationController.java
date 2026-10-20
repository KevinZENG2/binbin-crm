package cn.binbin.crm;
import java.util.*;import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class MigrationController {
 private final AuthService auth;private final MigrationService m;public MigrationController(AuthService a,MigrationService m){auth=a;this.m=m;}
 private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
 @GetMapping("/workspace/draft-templates") public Object draftTemplates(HttpServletRequest r){return m.draftTemplates(session(r));}
 @GetMapping("/migrations/templates") public Object templates(HttpServletRequest r){return m.templates(session(r));}
 @GetMapping("/migrations/templates/{type}") public Object template(@PathVariable String type,HttpServletRequest r){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(m.template(session(r),type));}
 @GetMapping("/migrations") public Object batches(HttpServletRequest r){return m.batches(session(r));}
 @GetMapping("/migrations/{id}") public Object batch(@PathVariable UUID id,HttpServletRequest r){return m.get(session(r),id);}
 @PostMapping("/migrations/preview") public Object preview(@Valid @RequestBody MigrationService.Preview i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return m.preview(session(r),i,key,RequestSafety.trace(r));}
 @PostMapping("/migrations/{id}/commit") public Object commit(@PathVariable UUID id,@Valid @RequestBody MigrationService.Commit i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return m.commit(session(r),id,i,key,RequestSafety.trace(r));}
 @GetMapping("/migrations/{id}/errors") public Object errors(@PathVariable UUID id,HttpServletRequest r){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(m.errors(session(r),id));}
 @PostMapping("/workspace/objects/{type}") public Object draft(@PathVariable String type,@Valid @RequestBody MigrationService.Draft i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return m.draft(session(r),type,i,key,RequestSafety.trace(r));}
}
