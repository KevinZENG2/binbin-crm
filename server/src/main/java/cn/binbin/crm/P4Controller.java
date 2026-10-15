package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class P4Controller {
 private final AuthService auth;private final ConfigurationService config;private final PortalAuthService portalAuth;
 public P4Controller(AuthService auth,ConfigurationService config,PortalAuthService portalAuth){this.auth=auth;this.config=config;this.portalAuth=portalAuth;}
 private AuthService.Session write(HttpServletRequest r){var s=auth.workspace(r);auth.csrf(s,r);return s;}
 @GetMapping("/configuration") Object overview(HttpServletRequest r){return config.overview(auth.workspace(r));}
 @PatchMapping("/configuration/profile") Object profile(@Valid @RequestBody ConfigurationService.ProfileInput i,HttpServletRequest r){return config.profile(write(r),i,RequestSafety.trace(r));}
 @PostMapping("/configuration/templates/{code}/apply") Object template(@PathVariable String code,HttpServletRequest r){return config.applyTemplate(write(r),code,RequestSafety.trace(r));}
 @PostMapping("/configuration/fields") Object createField(@Valid @RequestBody ConfigurationService.FieldInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return config.field(write(r),null,i,key,RequestSafety.trace(r));}
 @PatchMapping("/configuration/fields/{id}") Object field(@PathVariable UUID id,@Valid @RequestBody ConfigurationService.FieldInput i,HttpServletRequest r){return config.field(write(r),id,i,null,RequestSafety.trace(r));}
 @PostMapping("/configuration/tags") Object createTag(@Valid @RequestBody ConfigurationService.TagInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return config.tag(write(r),null,i,key,RequestSafety.trace(r));}
 @PatchMapping("/configuration/tags/{id}") Object tag(@PathVariable UUID id,@Valid @RequestBody ConfigurationService.TagInput i,HttpServletRequest r){return config.tag(write(r),id,i,null,RequestSafety.trace(r));}
 @PatchMapping("/configuration/stages/{id}") Object stage(@PathVariable UUID id,@Valid @RequestBody ConfigurationService.StageInput i,HttpServletRequest r){return config.stage(write(r),id,i,RequestSafety.trace(r));}
 @PatchMapping("/configuration/numbers/{id}") Object number(@PathVariable UUID id,@Valid @RequestBody ConfigurationService.NumberInput i,HttpServletRequest r){return config.number(write(r),id,i,RequestSafety.trace(r));}
 @PatchMapping("/configuration/approval/{id}") Object approval(@PathVariable UUID id,@Valid @RequestBody ConfigurationService.ApprovalInput i,HttpServletRequest r){return config.approval(write(r),id,i,RequestSafety.trace(r));}
 @PostMapping("/configuration/dictionaries/{type}") Object createDictionary(@PathVariable String type,@Valid @RequestBody ConfigurationService.DictionaryInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return config.dictionary(write(r),type,null,i,key,RequestSafety.trace(r));}
 @PatchMapping("/configuration/dictionaries/{type}/{id}") Object dictionary(@PathVariable String type,@PathVariable UUID id,@Valid @RequestBody ConfigurationService.DictionaryInput i,HttpServletRequest r){return config.dictionary(write(r),type,id,i,null,RequestSafety.trace(r));}
 @GetMapping("/customers/{id}/extensions") Object extensions(@PathVariable UUID id,HttpServletRequest r){return config.extensions(auth.workspace(r),id);}
 @PutMapping("/customers/{id}/extensions") Object extensions(@PathVariable UUID id,@RequestBody ConfigurationService.CustomerExtensionInput i,HttpServletRequest r){return config.saveExtensions(write(r),id,i,RequestSafety.trace(r));}
 @GetMapping("/portal-accounts") Object portalAccounts(HttpServletRequest r){return portalAuth.accounts(auth.workspace(r));}
 @PostMapping("/portal-accounts") Object createPortal(@Valid @RequestBody PortalAuthService.AccountInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return portalAuth.saveAccount(write(r),null,i,key,RequestSafety.trace(r));}
 @PatchMapping("/portal-accounts/{id}") Object editPortal(@PathVariable UUID id,@Valid @RequestBody PortalAuthService.AccountEdit i,HttpServletRequest r){return portalAuth.editAccount(write(r),id,i,RequestSafety.trace(r));}
}
