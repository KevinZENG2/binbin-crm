package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1")
public class DeliveryController {
    public record RecoveryRequest(@NotBlank @Size(max=128) String username) {}
    public record RecoveryComplete(@NotBlank @Size(max=43) String token,@NotBlank @Size(max=72) String password) {}
    public record RecoveryEmail(@NotBlank @Email @Size(max=254) String email,@Min(0) long expectedAuthVersion,@NotBlank @Size(max=300) String reason) {}
    private final AuthService auth;private final DataService data;private final AttachmentService files;private final NotificationService notifications;private final OperationsService ops;private final RecoveryService recovery;
    public DeliveryController(AuthService auth,DataService data,AttachmentService files,NotificationService notifications,OperationsService ops,RecoveryService recovery){this.auth=auth;this.data=data;this.files=files;this.notifications=notifications;this.ops=ops;this.recovery=recovery;}
    private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
    private static ResponseEntity<byte[]> csv(String name,byte[] data){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(data);}
    @GetMapping("/reports/sales") public Object reports(@RequestParam LocalDate from,@RequestParam LocalDate to,HttpServletRequest r){return data.reports(session(r),from,to);}
    @GetMapping("/exports/{module}") public Object export(@PathVariable String module,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID customerId,HttpServletRequest r){return csv("crm-"+module+".csv",data.export(session(r),module,q,status,customerId,RequestSafety.trace(r)));}
    @GetMapping("/imports/customers/template") public Object template(HttpServletRequest r){return csv("crm-customer-template.csv",data.template(session(r)));}
    @PostMapping("/imports/customers/preview") public Object preview(@Valid @RequestBody DataService.PreviewInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return data.preview(session(r),input,key,RequestSafety.trace(r));}
    @GetMapping("/imports/customers") public Object batches(HttpServletRequest r){return data.batches(session(r));}
    @GetMapping("/imports/customers/{id}") public Object batch(@PathVariable UUID id,HttpServletRequest r){return data.batch(session(r),id);}
    @PostMapping("/imports/customers/{id}/commit") public Object commit(@PathVariable UUID id,@Valid @RequestBody DataService.CommitInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return data.commit(session(r),id,input,key,RequestSafety.trace(r));}
    @GetMapping("/attachments") public Object attachments(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID customerId,@RequestParam(required=false) UUID contractId,@RequestParam(required=false) UUID receiptId,@RequestParam(required=false) UUID ticketId,HttpServletRequest r){return files.files(session(r),page,pageSize,q,customerId,contractId,receiptId,ticketId);}
    @PostMapping(value="/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Object upload(@RequestParam UUID customerId,@RequestParam(required=false) UUID opportunityId,@RequestParam(required=false) UUID contractId,@RequestParam(required=false) UUID receiptId,@RequestParam(required=false) UUID ticketId,@RequestPart MultipartFile file,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r)throws java.io.IOException{return files.upload(session(r),customerId,opportunityId,contractId,receiptId,ticketId,file.getOriginalFilename(),file.getBytes(),key,RequestSafety.trace(r));}
    @GetMapping("/attachments/{id}/download") public ResponseEntity<byte[]> download(@PathVariable UUID id,HttpServletRequest r){var f=files.download(session(r),id,RequestSafety.trace(r));return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(f.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(f.bytes());}
    @DeleteMapping("/attachments/{id}") public Object remove(@PathVariable UUID id,@RequestParam long expectedVersion,HttpServletRequest r){files.delete(session(r),id,expectedVersion,RequestSafety.trace(r));return ResponseEntity.noContent().build();}
    @GetMapping("/notifications") public Object notifications(HttpServletRequest r){return notifications.list(session(r));}
    @PostMapping("/notifications/{id}/read") public Object read(@PathVariable UUID id,HttpServletRequest r){notifications.read(session(r),id);return ResponseEntity.noContent().build();}
    @GetMapping("/platform/operations") public Object operations(HttpServletRequest r){return ops.status(session(r));}
    @PostMapping("/platform/jobs/reminders/run") public Object jobs(@RequestParam(required=false) UUID tenantId,HttpServletRequest r){return notifications.run(session(r),tenantId);}
    @GetMapping("/auth/recovery/status") public Object recoveryStatus(){return Map.of("enabled",recovery.enabled());}
    @PostMapping("/auth/recovery/request") public Object recoveryRequest(@Valid @RequestBody RecoveryRequest input,HttpServletRequest r){recovery.request(input.username(),RequestSafety.trace(r));return ResponseEntity.accepted().body(Map.of("message","如账号已绑定核验邮箱且邮件服务可用，将发送找回链接；也可联系管理员协助。"));}
    @PostMapping("/auth/recovery/complete") public Object recoveryComplete(@Valid @RequestBody RecoveryComplete input,HttpServletRequest r){recovery.complete(input.token(),input.password(),RequestSafety.trace(r));return Map.of("message","密码已重置，请重新登录");}
    @PostMapping("/platform/accounts/{id}/recovery-email") public Object recoveryEmail(@PathVariable UUID id,@Valid @RequestBody RecoveryEmail input,HttpServletRequest r){recovery.bind(session(r),id,input.email(),input.expectedAuthVersion(),input.reason(),RequestSafety.trace(r));return ResponseEntity.noContent().build();}
}
