package cn.binbin.crm;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/portal")
public class PortalController {
 private final PortalAuthService auth;private final PortalService portal;private final boolean secure;private final long hours;
 public PortalController(PortalAuthService auth,PortalService portal,@Value("${crm.cookie-secure}") boolean secure,@Value("${crm.portal-session-hours:12}") long hours){this.auth=auth;this.portal=portal;this.secure=secure;this.hours=hours;}
 private void cookie(HttpServletResponse r,String token,long seconds){r.addHeader("Set-Cookie",ResponseCookie.from("CRM_PORTAL_SESSION",token).path("/api/v1/portal").httpOnly(true).secure(secure).sameSite("Lax").maxAge(Duration.ofSeconds(seconds)).build().toString());}
 private PortalAuthService.Context read(HttpServletRequest r){return auth.authenticate(r);}private PortalAuthService.Context write(HttpServletRequest r){var c=auth.authenticate(r);auth.csrf(c,r);return c;}
 @PostMapping("/auth/login") Object login(@Valid @RequestBody PortalAuthService.LoginInput i,HttpServletRequest r,HttpServletResponse response){var result=auth.login(i,RequestSafety.trace(r));cookie(response,result.token(),hours*3600);return auth.view(result.context());}
 @GetMapping("/auth/session") Object session(HttpServletRequest r){return auth.view(read(r));}
 @PostMapping("/auth/logout") ResponseEntity<?> logout(HttpServletRequest r,HttpServletResponse response){var c=write(r);auth.logout(c,RequestSafety.trace(r));cookie(response,"",0);return ResponseEntity.noContent().build();}
 @PostMapping("/auth/password") ResponseEntity<?> password(@Valid @RequestBody PortalAuthService.PasswordInput i,HttpServletRequest r,HttpServletResponse response){var c=write(r);auth.password(c,i,RequestSafety.trace(r));cookie(response,"",0);return ResponseEntity.noContent().build();}
 @GetMapping("/summary") Object summary(HttpServletRequest r){return portal.summary(read(r));}
 @GetMapping("/quotes") Object quotes(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r){return portal.quotes(read(r),page,pageSize,q);}
 @GetMapping("/quotes/{id}") Object quote(@PathVariable UUID id,HttpServletRequest r){return portal.quote(read(r),id);}
 @GetMapping("/quotes/{id}/pdf") ResponseEntity<byte[]> pdf(@PathVariable UUID id,HttpServletRequest r){byte[] data=portal.quotePdf(read(r),id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=quote.pdf").body(data);}
 @GetMapping("/contracts") Object contracts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r){return portal.contracts(read(r),page,pageSize,q);}
 @GetMapping("/contracts/{id}") Object contract(@PathVariable UUID id,HttpServletRequest r){return portal.contract(read(r),id);}
 @GetMapping("/receipts") Object receipts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r){return portal.receipts(read(r),page,pageSize,q);}
 @GetMapping("/tickets") Object tickets(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r){return portal.tickets(read(r),page,pageSize,q);}
 @GetMapping("/tickets/{id}") Object ticket(@PathVariable UUID id,HttpServletRequest r){return portal.ticket(read(r),id,true);}
 @PostMapping("/tickets") Object createTicket(@Valid @RequestBody PortalService.TicketInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return portal.createTicket(write(r),i,key,RequestSafety.trace(r));}
 @PostMapping("/tickets/{id}/comments") Object comment(@PathVariable UUID id,@Valid @RequestBody PortalService.CommentInput i,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return portal.comment(write(r),id,i,key,RequestSafety.trace(r));}
 @PostMapping(value="/tickets/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) Object upload(@PathVariable UUID id,@RequestPart("file") MultipartFile file,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r)throws java.io.IOException{return portal.upload(write(r),id,file.getOriginalFilename(),file.getBytes(),key,RequestSafety.trace(r));}
 @GetMapping("/attachments/{id}") ResponseEntity<byte[]> attachment(@PathVariable UUID id,HttpServletRequest r){var f=portal.download(read(r),id,RequestSafety.trace(r));return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(f.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(f.bytes());}
 @GetMapping("/dictionaries") Object dictionaries(HttpServletRequest r){return portal.dictionaries(read(r));}
 @GetMapping("/knowledge") Object knowledge(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,HttpServletRequest r){return portal.articles(read(r),page,pageSize,q);}
 @GetMapping("/knowledge/{id}") Object article(@PathVariable UUID id,HttpServletRequest r){return portal.article(read(r),id);}
}
