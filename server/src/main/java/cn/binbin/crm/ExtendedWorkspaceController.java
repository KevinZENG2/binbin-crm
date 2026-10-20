package cn.binbin.crm;
import java.util.*;import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/api/v1/workspace")
public class ExtendedWorkspaceController {
 private final AuthService auth;private final P7Records records;private final ExtendedWorkspaceService w;
 public ExtendedWorkspaceController(AuthService a,P7Records r,ExtendedWorkspaceService w){auth=a;records=r;this.w=w;}
 private AuthService.Session session(HttpServletRequest r){var s=auth.workspace(r);if(!r.getMethod().equals("GET"))auth.csrf(s,r);return s;}
 @GetMapping("/catalog") public Object catalog(HttpServletRequest r){return records.catalog(session(r));}
 @GetMapping("/objects/{type}") public Object list(@PathVariable String type,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String q,@RequestParam(required=false) Integer year,HttpServletRequest r){return records.list(session(r),type,page,pageSize,q,year==null?TradeService.today().getYear():year);}
 @GetMapping("/objects/{type}/{id}") public Object one(@PathVariable String type,@PathVariable UUID id,HttpServletRequest r){return records.get(session(r),type,id);}
 @GetMapping("/objects/{type}/export") public Object export(@PathVariable String type,@RequestParam(required=false) Integer year,HttpServletRequest r){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(records.export(session(r),type,year==null?TradeService.today().getYear():year,RequestSafety.trace(r)));}
 @GetMapping("/objects/{type}/{id}/fields") public Object fields(@PathVariable String type,@PathVariable UUID id,HttpServletRequest r){return w.extensions(session(r),type,id);}
 @PutMapping("/objects/{type}/{id}/fields") public Object saveFields(@PathVariable String type,@PathVariable UUID id,@Valid @RequestBody ExtendedWorkspaceService.Values i,HttpServletRequest r){return w.save(session(r),type,id,i,RequestSafety.trace(r));}
 @GetMapping("/object-policies") public Object policies(HttpServletRequest r){return w.policies(session(r));}
 @PutMapping("/object-policies/{type}") public Object policy(@PathVariable String type,@Valid @RequestBody ExtendedWorkspaceService.Policy i,HttpServletRequest r){return w.policy(session(r),type,i,RequestSafety.trace(r));}
 @GetMapping("/notices") public Object notices(HttpServletRequest r){return w.notices(session(r));}
 @PostMapping("/notices/{id}/read") public Object read(@PathVariable UUID id,HttpServletRequest r){return w.noticeRead(session(r),id);}
 @GetMapping("/objects/{type}/{id}/attachments") public Object files(@PathVariable String type,@PathVariable UUID id,HttpServletRequest r){return w.files(session(r),type,id);}
 @PostMapping(value="/objects/{type}/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Object upload(@PathVariable String type,@PathVariable UUID id,@RequestPart MultipartFile file,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r)throws java.io.IOException{return w.upload(session(r),type,id,file.getOriginalFilename(),file.getBytes(),key,RequestSafety.trace(r));}
 @GetMapping("/attachments/{id}/download") public Object download(@PathVariable UUID id,HttpServletRequest r){var f=w.download(session(r),id,RequestSafety.trace(r));return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(f.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(f.bytes());}
 @DeleteMapping("/attachments/{id}") public Object remove(@PathVariable UUID id,@RequestParam long expectedVersion,HttpServletRequest r){return w.remove(session(r),id,expectedVersion,RequestSafety.trace(r));}
}
