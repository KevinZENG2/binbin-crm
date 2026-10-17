package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class CustomerCollaborationController {
    private final AuthService auth;private final CustomerCollaborationService service;
    public CustomerCollaborationController(AuthService auth,CustomerCollaborationService service){this.auth=auth;this.service=service;}
    private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!"GET".equals(r.getMethod()))auth.csrf(s,r);return s;}
    @GetMapping("/customers/{id}/profile") public Object profile(@PathVariable UUID id,HttpServletRequest r){return service.profile(session(r),id);}
    @GetMapping("/customers/{id}/duplicates") public Object duplicates(@PathVariable UUID id,HttpServletRequest r){return service.duplicates(session(r),id);}
    @PatchMapping("/customers/{id}/lifecycle") public Object stage(@PathVariable UUID id,@Valid @RequestBody CustomerCollaborationService.StageInput input,HttpServletRequest r){return service.stage(session(r),id,input,RequestSafety.trace(r));}
    @PostMapping("/customers/{id}/merge") public Object merge(@PathVariable UUID id,@Valid @RequestBody CustomerCollaborationService.MergeInput input,HttpServletRequest r){return service.merge(session(r),id,input,RequestSafety.trace(r));}
    @GetMapping("/followups/{type:LEAD|CUSTOMER|OPPORTUNITY}/{id}") public Object followups(@PathVariable String type,@PathVariable UUID id,HttpServletRequest r){return service.followups(session(r),type,id);}
    @PostMapping("/followups") public Object create(@Valid @RequestBody CustomerCollaborationService.FollowupInput input,HttpServletRequest r){return service.createFollowup(session(r),input,RequestSafety.trace(r));}
    @PostMapping("/followups/plans/{id}/complete") public Object complete(@PathVariable UUID id,@Valid @RequestBody CustomerCollaborationService.CompleteInput input,HttpServletRequest r){return service.complete(session(r),id,input,RequestSafety.trace(r));}
}
