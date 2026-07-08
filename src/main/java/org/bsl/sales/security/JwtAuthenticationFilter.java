package org.bsl.sales.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bsl.sales.model.User;
import org.bsl.sales.service.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserService userService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            try {
                String email = jwtUtil.getEmailFromToken(token);
                Optional<User> userOpt = StringUtils.hasText(email) ? userService.findByEmail(email) : Optional.empty();

                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    if (user.isEnabled() && jwtUtil.validateToken(token, user.getEmail(), user.getTokenVersion())) {
                        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
                        for (String permission : user.getAccessPermissions()) {
                            authorities.add(new SimpleGrantedAuthority("PERM_" + permission));
                        }

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                user.getEmail(), null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else {
                        SecurityContextHolder.clearContext();
                    }
                }
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.matches("^/error.*|/actuator.*|/health$")
                || path.equals("/ws")
                || path.startsWith("/ws/")
                || path.startsWith("/assets/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.matches(".*\\.(css|js|png|jpg|jpeg|gif|webp|ico|svg|woff2?|ttf|eot|pdf|zip|gz|json|xml|txt|html)$")
                || "/favicon.ico".equals(path)
                || "/".equals(path)
                || "/index.html".equals(path);
    }
}
