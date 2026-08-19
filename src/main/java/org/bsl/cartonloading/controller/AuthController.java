package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.security.JwtUtil;
import org.bsl.cartonloading.service.BuyerService;
import org.bsl.cartonloading.service.UserService;
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
    private final BuyerService buyerService;

    public AuthController(UserService userService, JwtUtil jwtUtil, PasswordEncoder passwordEncoder, BuyerService buyerService) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.buyerService = buyerService;
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

        String selectedBuyer = BuyerAccess.normalize(loginRequest.getBuyerCode());
        if (selectedBuyer.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Please select a valid Buyer"));
        }
        if (!buyerService.existsActive(selectedBuyer)) {
            return ResponseEntity.status(403).body(Map.of("message", "The selected Buyer is inactive or no longer exists"));
        }
        if (!user.canAccessBuyer(selectedBuyer)) {
            return ResponseEntity.status(403)
                    .body(Map.of("message", "Your account does not have permission to access the selected Buyer"));
        }

        String token = jwtUtil.generateToken(
                user.getEmail(),
                user.getRole(),
                user.getTokenVersion(),
                user.getAccessPermissions(),
                user.getBuyerPermissions()
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("user", userService.toUserDTO(user));
        response.put("selectedBuyer", selectedBuyer);
        return ResponseEntity.ok(response);
    }

    public static class LoginRequest {
        private String email;
        private String password;
        private String buyerCode;
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getBuyerCode() { return buyerCode; }
        public void setBuyerCode(String buyerCode) { this.buyerCode = buyerCode; }
    }
}
