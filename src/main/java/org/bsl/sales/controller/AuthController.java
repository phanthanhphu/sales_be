package org.bsl.sales.controller;

import org.bsl.sales.model.User;
import org.bsl.sales.security.JwtUtil;
import org.bsl.sales.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserService userService, JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        Optional<User> userOpt = userService.findByEmail(loginRequest.getEmail());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", "Email or password is incorrect"));
        }

        User user = userOpt.get();
        if (!user.isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("message", "Your account has been disabled. Please contact the administrator."));
        }
        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("message", "Email or password is incorrect"));
        }

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole(), user.getTokenVersion(), user.getAccessPermissions(), user.getBuyerKeys());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("user", userService.toUserDTO(user));
        return ResponseEntity.ok(response);
    }

    public static class LoginRequest {
        private String email;
        private String password;
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
