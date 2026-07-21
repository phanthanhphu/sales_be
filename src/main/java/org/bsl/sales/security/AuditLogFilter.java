package org.bsl.sales.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bsl.sales.model.AuditLog;
import org.bsl.sales.service.AuditLogService;
import org.bsl.sales.support.AuditActionResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class AuditLogFilter extends OncePerRequestFilter {
    public static final String ATTR_USER_ID = "AUDIT_USER_ID";
    public static final String ATTR_USERNAME = "AUDIT_USERNAME";
    public static final String ATTR_USER_EMAIL = "AUDIT_USER_EMAIL";
    public static final String ATTR_USER_ROLE = "AUDIT_USER_ROLE";

    private static final Set<String> SENSITIVE_QUERY_KEYS = Set.of(
            "password", "token", "access_token", "refresh_token", "authorization", "secret"
    );

    private final AuditLogService auditLogService;

    public AuditLogFilter(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
                .filter(StringUtils::hasText)
                .orElseGet(() -> UUID.randomUUID().toString());
        response.setHeader("X-Request-Id", requestId);

        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } catch (Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                AuditLog log = buildLog(request, response, requestId, startedAt, failure);
                auditLogService.saveAsync(log);
            } catch (Exception ignored) {
                // Audit logging must never affect the original business response.
            }
        }
    }

    private AuditLog buildLog(
            HttpServletRequest request,
            HttpServletResponse response,
            String requestId,
            long startedAt,
            Throwable failure
    ) {
        String endpoint = request.getRequestURI();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String action = AuditActionResolver.resolveAction(method, endpoint, request.getQueryString());
        if (!AuditActionResolver.isSupportedAction(action)) return null;

        int httpStatus = failure != null && response.getStatus() < 400 ? 500 : response.getStatus();
        boolean success = failure == null && httpStatus >= 200 && httpStatus < 400;
        String module = AuditActionResolver.resolveModule(endpoint);
        String resourceId = AuditActionResolver.resolveResourceId(endpoint);
        AuditIdentity identity = resolveIdentity(request);

        AuditLog log = new AuditLog();
        log.setUserId(identity.userId());
        log.setUsername(identity.username());
        log.setUserEmail(identity.email());
        log.setRole(identity.role());
        log.setAction(action);
        log.setModule(module);
        log.setResourceType(AuditActionResolver.resolveResourceType(endpoint));
        log.setResourceId(resourceId);
        log.setDescription(AuditActionResolver.description(action, module, resourceId, success));
        log.setHttpMethod(method);
        log.setEndpoint(endpoint);
        log.setQueryString(sanitizeQuery(request.getQueryString()));
        log.setStatus(success ? "SUCCESS" : "FAILED");
        log.setHttpStatus(httpStatus);
        log.setSuccess(success);
        log.setDurationMs(Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L));
        log.setRequestId(requestId);
        log.setIpAddress(resolveIpAddress(request));
        log.setFileName(decodeFileName(request.getHeader("X-File-Name")));
        log.setFileSize(parseLong(request.getHeader("X-File-Size")));
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    private AuditIdentity resolveIdentity(HttpServletRequest request) {
        String userId = stringAttribute(request, ATTR_USER_ID);
        String username = stringAttribute(request, ATTR_USERNAME);
        String email = stringAttribute(request, ATTR_USER_EMAIL);
        String role = stringAttribute(request, ATTR_USER_ROLE);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!StringUtils.hasText(email) && authentication != null && authentication.isAuthenticated()) {
            email = authentication.getName();
        }

        if (!StringUtils.hasText(username)) username = StringUtils.hasText(email) ? email : "ANONYMOUS";
        if (!StringUtils.hasText(email)) email = "ANONYMOUS";
        if (!StringUtils.hasText(role)) role = "ANONYMOUS";
        return new AuditIdentity(userId, username, email, role);
    }

    private String sanitizeQuery(String query) {
        if (!StringUtils.hasText(query)) return null;
        StringBuilder safe = new StringBuilder();
        for (String pair : query.split("&")) {
            if (safe.length() > 0) safe.append('&');
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (SENSITIVE_QUERY_KEYS.contains(key.toLowerCase(Locale.ROOT))) safe.append(key).append("=***");
            else safe.append(pair);
            if (safe.length() >= 1000) break;
        }
        return trim(safe.toString(), 1000);
    }

    private String resolveIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) return trim(forwarded.split(",")[0].trim(), 100);
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? trim(realIp, 100) : trim(request.getRemoteAddr(), 100);
    }

    private String stringAttribute(HttpServletRequest request, String key) {
        Object value = request.getAttribute(key);
        return value == null ? null : String.valueOf(value);
    }

    private String decodeFileName(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return trim(URLDecoder.decode(value, StandardCharsets.UTF_8), 300);
        } catch (Exception ignored) {
            return trim(value, 300);
        }
    }

    private Long parseLong(String value) {
        try {
            return StringUtils.hasText(value) ? Long.parseLong(value) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String trim(String value, int maxLength) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.startsWith("/api/actuator")
                || path.equals("/api/health")) {
            return true;
        }
        String action = AuditActionResolver.resolveAction(
                request.getMethod(), request.getRequestURI(), request.getQueryString()
        );
        return !AuditActionResolver.isSupportedAction(action);
    }

    private record AuditIdentity(String userId, String username, String email, String role) {}
}
