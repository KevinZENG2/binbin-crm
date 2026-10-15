package cn.binbin.crm;

import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiError(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ApiError unauthorized() { return new ApiError(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","请登录后继续"); }
    public static ApiError forbidden(String message) { return new ApiError(HttpStatus.FORBIDDEN,"FORBIDDEN",message); }
    public static ApiError bad(String message) { return new ApiError(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message); }
    public static ApiError missing() { return new ApiError(HttpStatus.NOT_FOUND,"NOT_FOUND","记录不存在或无权访问"); }
    public static ApiError conflict(String message) { return new ApiError(HttpStatus.CONFLICT,"CONFLICT",message); }
}
