package cn.binbin.crm;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ErrorHandler.class);
    private ResponseEntity<?> body(HttpStatus status,String code,String message,HttpServletRequest r) {
        return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"traceId",RequestSafety.trace(r).toString()));
    }
    @ExceptionHandler(ApiError.class) ResponseEntity<?> api(ApiError e,HttpServletRequest r) { return body(e.status,e.code,e.getMessage(),r); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) ResponseEntity<?> route(Exception e,HttpServletRequest r) { return body(HttpStatus.NOT_FOUND,"NOT_FOUND","请求的地址不存在",r); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception e,HttpServletRequest r) { return body(HttpStatus.METHOD_NOT_ALLOWED,"METHOD_NOT_ALLOWED","请求方法不受支持",r); }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class) ResponseEntity<?> media(Exception e,HttpServletRequest r) { return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_MEDIA_TYPE","请使用 JSON 格式提交",r); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class,BindException.class,ConstraintViolationException.class,MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e,HttpServletRequest r) { return body(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","请检查字段、格式和必填信息",r); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) ResponseEntity<?> tooLarge(Exception e,HttpServletRequest r){return body(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_TOO_LARGE","文件最多5MB",r);}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> constraint(Exception e,HttpServletRequest r) { return body(HttpStatus.CONFLICT,"CONFLICT","记录重复或关联信息无效",r); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> db(Exception e,HttpServletRequest r) { LOG.warn("Database operation failed: trace={} type={}",RequestSafety.trace(r),e.getClass().getSimpleName()); return body(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_UNAVAILABLE","服务暂时不可用，请稍后重试",r); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e,HttpServletRequest r) { LOG.error("Request failed: trace={} type={}",RequestSafety.trace(r),e.getClass().getSimpleName()); return body(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","操作未完成，请提供追踪编号",r); }
}
