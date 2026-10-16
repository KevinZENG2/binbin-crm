package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class ApiController {
    public record Login(@NotBlank @Size(max=128) String username,@NotBlank @Size(max=72) String password) {}
    public record Selection(@NotNull UUID tenantId) {}
    private final AuthService auth;private final CustomerService customers;private final JdbcTemplate db;private final boolean secure;private final long hours;private final Readiness readiness;
    public ApiController(AuthService auth,CustomerService customers,JdbcTemplate db,Readiness readiness,@Value("${crm.cookie-secure}") boolean secure,@Value("${crm.session-hours}") long hours){this.auth=auth;this.customers=customers;this.db=db;this.secure=secure;this.hours=hours;this.readiness=readiness;}
    private void cookie(HttpServletResponse response,String token,long maxAge) { response.addHeader("Set-Cookie",ResponseCookie.from("CRM_SESSION",token).path("/api/v1").httpOnly(true).secure(secure).sameSite("Lax").maxAge(Duration.ofSeconds(maxAge)).build().toString()); }
    @GetMapping("/health") public Map<String,Object> health() { if(!readiness.ready())throw new ApiError(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"NOT_READY","服务正在准备");db.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP","version","0.14.0","backend","Java 21 / Spring Boot","database","PostgreSQL"); }
    @PostMapping("/auth/login") public Map<String,Object> login(@Valid @RequestBody Login input,HttpServletRequest r,HttpServletResponse response) { var result=auth.login(input.username(),input.password(),RequestSafety.trace(r));cookie(response,result.token(),hours*3600);return auth.view(result.session()); }
    @GetMapping("/auth/session") public Map<String,Object> session(HttpServletRequest r) { return auth.view(auth.authenticate(r)); }
    @GetMapping("/tenants") public Object tenants(HttpServletRequest r) { return auth.tenants(auth.authenticate(r)); }
    @PostMapping("/auth/active-tenant") public Map<String,Object> select(@Valid @RequestBody Selection input,HttpServletRequest r) { var s=auth.authenticate(r);auth.csrf(s,r);return auth.view(auth.switchTenant(s,input.tenantId(),RequestSafety.trace(r))); }
    @PostMapping("/auth/logout") public ResponseEntity<?> logout(HttpServletRequest r,HttpServletResponse response) { var s=auth.authenticate(r);auth.csrf(s,r);auth.logout(s,RequestSafety.trace(r));cookie(response,"",0);return ResponseEntity.noContent().build(); }
    @GetMapping("/customers") public CustomerService.Page customers(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r) { var s=auth.workspace(r);var result=customers.list(s,page,pageSize,q);customers.logSupport(s,"CUSTOMER_LIST",null,RequestSafety.trace(r));return result; }
    @GetMapping("/customers/options") public Object customerOptions(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="true") boolean writable,HttpServletRequest r){var s=auth.workspace(r);var result=customers.options(s,page,pageSize,q,writable);customers.logSupport(s,"CUSTOMER_LIST",null,RequestSafety.trace(r));return result;}
    @GetMapping("/customers/owners") public Object owners(HttpServletRequest r) { return customers.owners(auth.workspace(r)); }
    @GetMapping("/customers/{id}") public CustomerService.Customer detail(@PathVariable UUID id,HttpServletRequest r) {var s=auth.workspace(r);var result=customers.detail(s,id);customers.logSupport(s,"CUSTOMER_DETAIL",id,RequestSafety.trace(r));return result; }
    @PostMapping("/customers") public ResponseEntity<?> create(@Valid @RequestBody CustomerService.Create input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r) { var s=auth.workspace(r);auth.csrf(s,r);return ResponseEntity.status(201).body(customers.create(s,input,key,RequestSafety.trace(r))); }
    @PatchMapping("/customers/{id}") public CustomerService.Customer edit(@PathVariable UUID id,@Valid @RequestBody CustomerService.Edit input,HttpServletRequest r) { var s=auth.workspace(r);auth.csrf(s,r);return customers.edit(s,id,input,RequestSafety.trace(r)); }
    @GetMapping("/customers/{id}/shares") public Object shares(@PathVariable UUID id,HttpServletRequest r){return customers.shares(auth.workspace(r),id);}
    @GetMapping("/customers/{id}/share-recipients") public Object recipients(@PathVariable UUID id,HttpServletRequest r){return customers.shareRecipients(auth.workspace(r),id);}
    @PostMapping("/customers/{id}/shares") public Object share(@PathVariable UUID id,@Valid @RequestBody CustomerService.Sharing input,HttpServletRequest r){var s=auth.workspace(r);auth.csrf(s,r);return customers.share(s,id,input,RequestSafety.trace(r));}
    @DeleteMapping("/customers/{id}/shares/{member}") public ResponseEntity<?> revokeShare(@PathVariable UUID id,@PathVariable UUID member,HttpServletRequest r){var s=auth.workspace(r);auth.csrf(s,r);customers.revokeShare(s,id,member,RequestSafety.trace(r));return ResponseEntity.noContent().build();}
    @PostMapping("/customers/{id}/transfer") public Object transfer(@PathVariable UUID id,@Valid @RequestBody CustomerService.Transfer input,HttpServletRequest r){var s=auth.workspace(r);auth.csrf(s,r);return customers.transfer(s,id,input,RequestSafety.trace(r));}
}
