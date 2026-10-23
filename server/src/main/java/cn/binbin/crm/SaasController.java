package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class SaasController {
    public record PasswordInput(@NotBlank @Size(max=72) String oldPassword,@NotBlank @Size(max=72) String newPassword) {}
    public record ResetInput(@NotBlank @Size(max=72) String newPassword) {}
    private final AuthService auth;private final SaasService service;private final SalesFoundationService foundation;
    public SaasController(AuthService auth,SaasService service,SalesFoundationService foundation){this.auth=auth;this.service=service;this.foundation=foundation;}
    private AuthService.Session platform(HttpServletRequest r){var s=auth.authenticate(r);auth.platform(s);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
    private AuthService.Session workspace(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
    @PostMapping("/auth/password") public ResponseEntity<?> password(@Valid @RequestBody PasswordInput input,HttpServletRequest r,HttpServletResponse response){var s=auth.authenticate(r);auth.csrf(s,r);auth.changePassword(s,input.oldPassword(),input.newPassword(),RequestSafety.trace(r));response.addHeader("Set-Cookie",ResponseCookie.from("CRM_SESSION","").path("/api/v1").httpOnly(true).secure(true).sameSite("Lax").maxAge(0).build().toString());return ResponseEntity.noContent().build();}
    @GetMapping("/platform/plans") public Object plans(HttpServletRequest r){return service.plans(platform(r));}
    @PostMapping("/platform/plans") public Object newPlan(@Valid @RequestBody SaasService.PlanInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.savePlan(platform(r),null,input,key,RequestSafety.trace(r));}
    @PatchMapping("/platform/plans/{id}") public Object plan(@PathVariable UUID id,@Valid @RequestBody SaasService.PlanInput input,HttpServletRequest r){return service.savePlan(platform(r),id,input,"",RequestSafety.trace(r));}
    @GetMapping("/platform/tenants") public Object tenants(HttpServletRequest r){return service.tenants(platform(r));}
    @PostMapping("/platform/tenants") public Object newTenant(@Valid @RequestBody SaasService.TenantInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.createTenant(platform(r),input,key,RequestSafety.trace(r));}
    @PatchMapping("/platform/tenants/{id}") public Object tenant(@PathVariable UUID id,@Valid @RequestBody SaasService.TenantEdit input,HttpServletRequest r){return service.editTenant(platform(r),id,input,RequestSafety.trace(r));}
    @GetMapping("/platform/renewals") public Object platformRenewals(HttpServletRequest r){return service.renewals(platform(r),true);}
    @PostMapping("/platform/renewals/{id}/decision") public Object decision(@PathVariable UUID id,@Valid @RequestBody SaasService.Decision input,HttpServletRequest r){return service.decideRenewal(platform(r),id,input,RequestSafety.trace(r));}
    @GetMapping("/platform/accounts") public Object accounts(HttpServletRequest r){return service.accounts(platform(r));}
    @PostMapping("/platform/accounts/{id}/password-reset") public ResponseEntity<?> reset(@PathVariable UUID id,@Valid @RequestBody ResetInput input,HttpServletRequest r){service.resetPassword(platform(r),id,input.newPassword(),RequestSafety.trace(r));return ResponseEntity.noContent().build();}
    @GetMapping("/platform/audits") public Object platformAudits(HttpServletRequest r){return service.audits(platform(r),true);}
    @GetMapping("/platform/support/grants") public Object platformSupport(HttpServletRequest r){return service.supportGrants(platform(r),true);}
    @PostMapping("/platform/support/{id}/enter") public Object enterSupport(@PathVariable UUID id,HttpServletRequest r){return auth.view(service.enterSupport(platform(r),id,RequestSafety.trace(r)));}
    @PostMapping("/platform/support/exit") public Object exitSupport(HttpServletRequest r){return auth.view(service.exitSupport(platform(r),RequestSafety.trace(r)));}
    @GetMapping("/workspace/summary") public Object summary(HttpServletRequest r){return service.summary(workspace(r));}
    @GetMapping("/workspace/departments") public Object departments(HttpServletRequest r){return service.departments(workspace(r));}
    @PostMapping("/workspace/departments") public Object newDepartment(@Valid @RequestBody SaasService.DepartmentInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.saveDepartment(workspace(r),null,input,key,RequestSafety.trace(r));}
    @PatchMapping("/workspace/departments/{id}") public Object department(@PathVariable UUID id,@Valid @RequestBody SaasService.DepartmentInput input,HttpServletRequest r){return service.saveDepartment(workspace(r),id,input,"",RequestSafety.trace(r));}
    @GetMapping("/workspace/members") public Object members(HttpServletRequest r){return service.members(workspace(r));}
    @PostMapping("/workspace/members") public Object newMember(@Valid @RequestBody SaasService.MemberInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.createMember(workspace(r),input,key,RequestSafety.trace(r));}
    @PatchMapping("/workspace/members/{id}") public Object member(@PathVariable UUID id,@Valid @RequestBody SaasService.MemberEdit input,HttpServletRequest r){return service.editMember(workspace(r),id,input,RequestSafety.trace(r));}
    @GetMapping("/workspace/role-policies") public Object policies(HttpServletRequest r){return service.policies(workspace(r));}
    @PatchMapping("/workspace/role-policies/{role}") public Object policy(@PathVariable String role,@Valid @RequestBody SaasService.PolicyInput input,HttpServletRequest r){return service.policy(workspace(r),role,input,RequestSafety.trace(r));}
    @GetMapping("/workspace/renewals") public Object renewals(HttpServletRequest r){return service.renewals(workspace(r),false);}
    @PostMapping("/workspace/renewals") public Object newRenewal(@Valid @RequestBody SaasService.RenewalInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.renewal(workspace(r),input,key,RequestSafety.trace(r));}
    @PostMapping("/workspace/renewals/{id}/cancel") public Object cancelRenewal(@PathVariable UUID id,HttpServletRequest r){return service.cancelRenewal(workspace(r),id,RequestSafety.trace(r));}
    @GetMapping("/workspace/support/operators") public Object operators(HttpServletRequest r){return service.operators(workspace(r));}
    @GetMapping("/workspace/support/grants") public Object grants(HttpServletRequest r){return service.supportGrants(workspace(r),false);}
    @PostMapping("/workspace/support/grants") public Object grantSupport(@Valid @RequestBody SaasService.SupportInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return service.grantSupport(workspace(r),input,key,RequestSafety.trace(r));}
    @PostMapping("/workspace/support/grants/{id}/revoke") public ResponseEntity<?> revokeSupport(@PathVariable UUID id,HttpServletRequest r){service.revokeSupport(workspace(r),id,RequestSafety.trace(r));return ResponseEntity.noContent().build();}
    @GetMapping("/workspace/support/grants/{id}/access") public Object supportAccess(@PathVariable UUID id,HttpServletRequest r){return service.supportAccess(workspace(r),id);}
    @GetMapping("/workspace/audits") public Object audits(HttpServletRequest r){return service.audits(workspace(r),false);}
    @GetMapping("/workspace/sales-resource-policies") public Object resourcePolicies(HttpServletRequest r){return foundation.policies(workspace(r));}
    @PutMapping("/workspace/sales-resource-policies/{member}") public Object resourcePolicy(@PathVariable UUID member,@Valid @RequestBody SalesFoundationService.PolicyInput input,HttpServletRequest r){return foundation.save(workspace(r),member,input,RequestSafety.trace(r));}
    @GetMapping("/workspace/resource-alerts") public Object resourceAlerts(HttpServletRequest r){return foundation.alerts(workspace(r));}
    @PostMapping("/workspace/resource-alerts/{id}/read") public ResponseEntity<?> readResourceAlert(@PathVariable UUID id,HttpServletRequest r){foundation.readAlert(workspace(r),id);return ResponseEntity.noContent().build();}
}
