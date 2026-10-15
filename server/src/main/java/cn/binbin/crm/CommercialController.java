package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class CommercialController {
 private final AuthService auth;private final CommercialService commercial;
 public CommercialController(AuthService auth,CommercialService commercial){this.auth=auth;this.commercial=commercial;}
 private AuthService.Session platform(HttpServletRequest r,boolean write){var s=auth.authenticate(r);auth.platform(s);if(write)auth.csrf(s,r);return s;}
 @GetMapping("/subscription/entitlements") Object entitlements(HttpServletRequest r){return commercial.entitlements(auth.workspace(r));}
 @GetMapping("/platform/commercial/overview") Object overview(HttpServletRequest r){return commercial.overview(platform(r,false));}
 @GetMapping("/platform/commercial/tenants") Object tenants(HttpServletRequest r){return commercial.tenants(platform(r,false));}
 @GetMapping("/platform/commercial/orders") Object orders(HttpServletRequest r){return commercial.orders(platform(r,false));}
 @PatchMapping("/platform/commercial/plans/{id}/entitlements") Object entitlements(@PathVariable UUID id,@Valid @RequestBody CommercialService.EntitlementInput input,HttpServletRequest r){return commercial.entitlements(platform(r,true),id,input,RequestSafety.trace(r));}
 @PostMapping("/platform/commercial/orders") Object create(@Valid @RequestBody CommercialService.OrderInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return commercial.create(platform(r,true),input,key,RequestSafety.trace(r));}
 @PostMapping("/platform/commercial/orders/{id}/mark-paid") Object paid(@PathVariable UUID id,@Valid @RequestBody CommercialService.PaidInput input,HttpServletRequest r){return commercial.paid(platform(r,true),id,input,RequestSafety.trace(r));}
 @PostMapping("/platform/commercial/orders/{id}/cancel") Object cancel(@PathVariable UUID id,@Valid @RequestBody CommercialService.CancelInput input,HttpServletRequest r){return commercial.cancel(platform(r,true),id,input,RequestSafety.trace(r));}
}
