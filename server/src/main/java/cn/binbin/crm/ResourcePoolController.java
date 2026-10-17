package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/resource-pool")
public class ResourcePoolController {
    private final AuthService auth;private final ResourcePoolService service;
    public ResourcePoolController(AuthService auth,ResourcePoolService service){this.auth=auth;this.service=service;}
    private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!"GET".equals(r.getMethod()))auth.csrf(s,r);return s;}
    @GetMapping("/{type:leads|customers}") public Object list(@PathVariable String type,@RequestParam(defaultValue="pool") String mode,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r){return service.list(session(r),type,mode,page,pageSize,q);}
    @GetMapping("/{type:leads|customers}/recipients") public Object recipients(@PathVariable String type,HttpServletRequest r){return service.recipients(session(r),type);}
    @GetMapping("/{type:leads|customers}/{id}/history") public Object history(@PathVariable String type,@PathVariable UUID id,HttpServletRequest r){return service.history(session(r),type,id);}
    @PostMapping("/{type:leads|customers}/{id}/claim") public ResponseEntity<?> claim(@PathVariable String type,@PathVariable UUID id,@Valid @RequestBody ResourcePoolService.Version input,HttpServletRequest r){service.claim(session(r),type,id,input,RequestSafety.trace(r));return ResponseEntity.noContent().build();}
    @PostMapping("/{type:leads|customers}/{id}/return") public ResponseEntity<?> release(@PathVariable String type,@PathVariable UUID id,@Valid @RequestBody ResourcePoolService.Version input,HttpServletRequest r){service.release(session(r),type,id,input,RequestSafety.trace(r));return ResponseEntity.noContent().build();}
    @PostMapping("/{type:leads|customers}/{id}/assign") public ResponseEntity<?> assign(@PathVariable String type,@PathVariable UUID id,@Valid @RequestBody ResourcePoolService.Assignment input,HttpServletRequest r){service.assign(session(r),type,id,input,RequestSafety.trace(r));return ResponseEntity.noContent().build();}
    @PostMapping("/{type:leads|customers}/assign-batch") public Object batch(@PathVariable String type,@Valid @RequestBody ResourcePoolService.Batch input,HttpServletRequest r){return java.util.Map.of("assigned",service.batch(session(r),type,input,RequestSafety.trace(r)));}
    @PostMapping("/recover") public Object recover(HttpServletRequest r){return java.util.Map.of("recycled",service.recover(session(r)));}
}
