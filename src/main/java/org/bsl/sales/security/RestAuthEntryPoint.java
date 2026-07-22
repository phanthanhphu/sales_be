package org.bsl.sales.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RestAuthEntryPoint implements AuthenticationEntryPoint {

    public static final String ATTR_AUTH_FAILURE_CODE = "bsl.auth.failureCode";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException)
            throws IOException, ServletException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Object rawCode = request.getAttribute(ATTR_AUTH_FAILURE_CODE);
        String code = rawCode == null ? AUTH_REQUIRED : String.valueOf(rawCode);
        String message = switch (code) {
            case ACCOUNT_DISABLED -> "Your account has been disabled. Please contact the administrator.";
            case SESSION_REVOKED -> "Your session is no longer valid. Please login again.";
            case ACCOUNT_NOT_FOUND -> "Your account is no longer available. Please contact the administrator.";
            default -> "Authentication is required. Please login again.";
        };

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status", 401);
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("timestamp", System.currentTimeMillis());

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
