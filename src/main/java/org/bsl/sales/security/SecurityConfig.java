package org.bsl.sales.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuditLogFilter auditLogFilter;
    private final RestAuthEntryPoint restAuthEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuditLogFilter auditLogFilter,
            RestAuthEntryPoint restAuthEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.auditLogFilter = auditLogFilter;
        this.restAuthEntryPoint = restAuthEntryPoint;
        this.restAccessDeniedHandler = restAccessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/users/login", "/api/auth/login", "/login").permitAll()
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**", "/css/**", "/js/**", "/images/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/swagger-resources/**", "/webjars/**").permitAll()
                        .requestMatchers("/actuator/**", "/health", "/info", "/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/files/**", "/uploads/**").permitAll()

                        // Audit history contains system-wide activity and is ADMIN-only.
                        .requestMatchers("/api/audit-logs/**").hasRole("ADMIN")

                        // Only ADMIN may manage system users and departments.
                        .requestMatchers("/api/departments/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/users/add", "/api/users/reset-password").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/users/*").hasRole("ADMIN")

                        /*
                         * BOM workspace mutations.
                         * Keep these BEFORE generic /api/orders/** patterns because BOM lives below
                         * /api/orders/{orderId}/boms and must never be accidentally treated as Sales.
                         */
                        .requestMatchers(HttpMethod.POST, "/api/boms/**", "/api/orders/*/boms", "/api/orders/*/boms/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_BOM", "PERM_SALES")
                        .requestMatchers(HttpMethod.PUT, "/api/boms/**", "/api/orders/*/boms", "/api/orders/*/boms/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_BOM", "PERM_SALES")
                        .requestMatchers(HttpMethod.PATCH, "/api/boms/**", "/api/orders/*/boms", "/api/orders/*/boms/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_BOM", "PERM_SALES")
                        .requestMatchers(HttpMethod.DELETE, "/api/boms/**", "/api/orders/*/boms", "/api/orders/*/boms/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_BOM", "PERM_SALES")

                        /*
                         * Currency has its own action permission. SALES keeps Currency access for
                         * backward compatibility, while CURRENCY can manage only Currency without
                         * receiving write access to the rest of Sales / MPR master data.
                         * These specific matchers MUST stay before the generic /api/master-data/** rules.
                         */
                        .requestMatchers(HttpMethod.POST, "/api/master-data/currencies", "/api/master-data/currencies/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_SALES", "PERM_CURRENCY")
                        .requestMatchers(HttpMethod.PUT, "/api/master-data/currencies", "/api/master-data/currencies/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_SALES", "PERM_CURRENCY")
                        .requestMatchers(HttpMethod.PATCH, "/api/master-data/currencies", "/api/master-data/currencies/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_SALES", "PERM_CURRENCY")
                        .requestMatchers(HttpMethod.DELETE, "/api/master-data/currencies", "/api/master-data/currencies/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_SALES", "PERM_CURRENCY")

                        /*
                         * Sales workspace mutations: orders, MPR, and Sales master data except the
                         * Currency-specific action rule above. BOM-only and Currency-only users may
                         * still GET/read these screens.
                         */
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/mpr", "/api/orders/*/mpr/**", "/api/orders", "/api/orders/*", "/api/master-data/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_SALES")
                        .requestMatchers(HttpMethod.PUT, "/api/orders/*/mpr", "/api/orders/*/mpr/**", "/api/orders", "/api/orders/*", "/api/master-data/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_SALES")
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/mpr", "/api/orders/*/mpr/**", "/api/orders", "/api/orders/*", "/api/master-data/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_SALES")
                        .requestMatchers(HttpMethod.DELETE, "/api/orders/*/mpr", "/api/orders/*/mpr/**", "/api/orders", "/api/orders/*", "/api/master-data/**")
                            .hasAnyAuthority("ROLE_ADMIN", "PERM_SALES")

                        // All authenticated users (including VIEW_SYSTEM) may only read/search/export.
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(auditLogFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
                "http://localhost:3001", "http://127.0.0.1:3001", "http://127.0.0.1:8081",
                "http://10.232.132.45:3001", "http://10.232.132.45:8081"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "X-Total-Count", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
    //note
}
