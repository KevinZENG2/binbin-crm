package cn.binbin.crm;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(1)
public class RequestSafety extends OncePerRequestFilter {
    private final Set<String> allowed;
    public RequestSafety(@Value("${crm.allowed-origins}") String origins) {
        allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    public static UUID trace(HttpServletRequest r) { return (UUID)r.getAttribute("crm.trace"); }
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
        UUID trace=UUID.randomUUID(); r.setAttribute("crm.trace",trace); s.setHeader("X-Trace-Id",trace.toString());
        s.setHeader("X-Content-Type-Options","nosniff"); s.setHeader("Referrer-Policy","no-referrer");
        s.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        if (r.getRequestURI().startsWith("/api/")) {
            s.setHeader("Cache-Control","no-store");
            if (!Set.of("GET","HEAD","OPTIONS").contains(r.getMethod())) {
                String origin=r.getHeader("Origin"), fetch=r.getHeader("Sec-Fetch-Site");
                boolean valid=true;
                if (origin!=null) { try { URI u=URI.create(origin); valid=u.getRawPath().isEmpty() && allowed.contains(origin); } catch(Exception e) { valid=false; } }
                if ("cross-site".equals(fetch)) valid=false;
                if (!valid) { s.setStatus(403);s.setContentType("application/json;charset=UTF-8");s.getWriter().write("{\"code\":\"ORIGIN_REJECTED\",\"message\":\"请求来源未授权\",\"traceId\":\""+trace+"\"}");return; }
            }
        }
        chain.doFilter(r,s);
    }
}
