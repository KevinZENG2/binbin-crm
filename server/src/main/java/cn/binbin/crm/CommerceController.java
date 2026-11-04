package cn.binbin.crm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class CommerceController {
    private final AuthService auth;private final CommerceService service;
    public CommerceController(AuthService auth,CommerceService service){this.auth=auth;this.service=service;}
    private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
    @GetMapping("/products") public Object products(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,HttpServletRequest r){return service.products(session(r),page,pageSize,q,status);}
    @GetMapping("/products/{id}") public Object product(@PathVariable UUID id,HttpServletRequest r){return service.product(session(r),id);}
    @PostMapping("/products") public Object createProduct(@Valid @RequestBody CommerceService.ProductInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.createProduct(session(r),input,key,RequestSafety.trace(r));}
    @PutMapping("/products/{id}") public Object editProduct(@PathVariable UUID id,@Valid @RequestBody CommerceService.ProductInput input,HttpServletRequest r){return service.editProduct(session(r),id,input,RequestSafety.trace(r));}
    @GetMapping("/pricing-policy") public Object pricing(HttpServletRequest r){return service.pricing(session(r));}
    @PutMapping("/pricing-policy") public Object updatePricing(@Valid @RequestBody CommerceService.PricingInput input,HttpServletRequest r){return service.updatePricing(session(r),input,RequestSafety.trace(r));}
    @GetMapping("/quotes") public Object quotes(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID customerId,@RequestParam(defaultValue="false") boolean mine,HttpServletRequest r){return service.quotes(session(r),page,pageSize,q,status,customerId,mine);}
    @GetMapping("/quotes/{id}") public Object quote(@PathVariable UUID id,HttpServletRequest r){return service.quote(session(r),id);}
    @PostMapping("/quotes") public Object createQuote(@Valid @RequestBody CommerceService.QuoteInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.createQuote(session(r),input,key,RequestSafety.trace(r));}
    @PutMapping("/quotes/{id}") public Object editQuote(@PathVariable UUID id,@Valid @RequestBody CommerceService.QuoteInput input,HttpServletRequest r){return service.editQuote(session(r),id,input,RequestSafety.trace(r));}
    @PostMapping("/quotes/{id}/{action:submit|withdraw|void|revise}") public Object transition(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody CommerceService.Transition input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.transition(session(r),id,action,input,key,RequestSafety.trace(r));}
    @GetMapping("/quotes/{id}/approvers") public Object approvers(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r){return service.approvers(session(r),id,page,pageSize,q);}
    @GetMapping("/quotes/{id}/approvals") public Object approvals(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return service.approvals(session(r),id,page,pageSize);}
    @GetMapping("/quotes/{id}/approvals/{approval}") public Object approval(@PathVariable UUID id,@PathVariable UUID approval,HttpServletRequest r){return service.approval(session(r),id,approval);}
    @PostMapping("/quotes/{id}/approval/{action:approve|reject}") public Object decide(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody CommerceService.Decision input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.decide(session(r),id,action,input,key,RequestSafety.trace(r));}
    @GetMapping("/quotes/{id}/history") public Object history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return service.history(session(r),id,page,pageSize);}
    @GetMapping("/quotes/{id}/versions/{version}") public Object revision(@PathVariable UUID id,@PathVariable long version,HttpServletRequest r){return service.revision(session(r),id,version);}
    @GetMapping("/quotes/{id}/pdf") public Object pdf(@PathVariable UUID id,@RequestParam long version,HttpServletRequest r){return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"quote-"+id+"-v"+version+".pdf\"").body(service.pdf(session(r),id,version,RequestSafety.trace(r)));}
}
