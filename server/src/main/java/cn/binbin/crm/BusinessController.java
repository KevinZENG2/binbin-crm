package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class BusinessController {
    private final AuthService auth;private final BusinessService service;
    public BusinessController(AuthService auth,BusinessService service){this.auth=auth;this.service=service;}
    private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
    @GetMapping("/contacts") public Object contacts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return service.contacts(session(r),page,pageSize,q,customerId);}
    @GetMapping("/contacts/{id}") public Object contact(@PathVariable UUID id,HttpServletRequest r){return service.contact(session(r),id);}
    @PostMapping("/contacts") public Object createContact(@Valid @RequestBody BusinessService.ContactCreate input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ResponseEntity.status(201).body(service.createContact(session(r),input,key,RequestSafety.trace(r)));}
    @PatchMapping("/contacts/{id}") public Object editContact(@PathVariable UUID id,@Valid @RequestBody BusinessService.ContactEdit input,HttpServletRequest r){return service.editContact(session(r),id,input,RequestSafety.trace(r));}
    @GetMapping("/leads") public Object leads(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,HttpServletRequest r){return service.leads(session(r),page,pageSize,q,status);}
    @GetMapping("/leads/owners") public Object owners(HttpServletRequest r){return service.leadOwners(session(r));}
    @GetMapping("/leads/{id}") public Object lead(@PathVariable UUID id,HttpServletRequest r){return service.lead(session(r),id);}
    @PostMapping("/leads") public Object createLead(@Valid @RequestBody BusinessService.LeadCreate input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ResponseEntity.status(201).body(service.createLead(session(r),input,key,RequestSafety.trace(r)));}
    @PatchMapping("/leads/{id}") public Object editLead(@PathVariable UUID id,@Valid @RequestBody BusinessService.LeadEdit input,HttpServletRequest r){return service.editLead(session(r),id,input,RequestSafety.trace(r));}
    @PostMapping("/leads/{id}/convert") public Object convert(@PathVariable UUID id,@Valid @RequestBody BusinessService.Convert input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ResponseEntity.status(201).body(service.convert(session(r),id,input,key,RequestSafety.trace(r)));}
}
