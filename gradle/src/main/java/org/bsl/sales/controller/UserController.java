package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.ChangePasswordRequest;
import org.bsl.sales.dto.LoginRequest;
import org.bsl.sales.dto.ResetPasswordRequest;
import org.bsl.sales.dto.UserRequest;
import org.bsl.sales.model.Department;
import org.bsl.sales.model.User;
import org.bsl.sales.security.JwtService;
import org.bsl.sales.service.DepartmentService;
import org.bsl.sales.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private static final long MAX_PROFILE_IMAGE_SIZE = 5L * 1024 * 1024;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserService userService;
    private final DepartmentService departmentService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Path uploadDirectory;

    public UserController(
            UserService userService,
            DepartmentService departmentService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            @Value("${app.upload.dir}") String uploadDirectory
    ) {
        this.userService = userService;
        this.departmentService = departmentService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    @PostMapping(value = "/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> addUser(
            @ModelAttribute UserRequest request,
            Authentication authentication
    ) throws IOException {
        boolean creatingFirstUser = !userService.hasUsers();
        if (creatingFirstUser) {
            if (!"ADMIN".equals(UserService.normalizeRole(request.getRole()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The first user must have the ADMIN role");
            }
        } else {
            ApiAccess.requireAdmin(authentication);
        }

        validateCreateRequest(request);
        Department department = departmentService.requireById(request.getDepartmentId());

        User user = new User();
        user.setUsername(required(request.getUsername(), "Username is required"));
        user.setEmail(validEmail(request.getEmail()));
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setAddress(trim(request.getAddress()));
        user.setPhone(trim(request.getPhone()));
        user.setRole(UserService.normalizeRole(request.getRole()));
        user.setEnabled(request.getEnabled() == null || request.getEnabled());
        user.setDepartmentId(department.getId());
        user.setTokenVersion(1L);

        // The ID must be known before the image file is named.
        user.setId(UUID.randomUUID().toString());
        if (hasFile(request.getProfileImage())) {
            user.setProfileImageFileName(saveProfileImage(user.getId(), request.getProfileImage()));
        }

        User saved = userService.create(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "User created successfully",
                "data", toUserResponse(saved)
        ));
    }

    @GetMapping
    public Map<String, Object> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String role,
            Authentication authentication
    ) {
        ApiAccess.requireAdmin(authentication);
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0 and size must be between 1 and 100");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt")));
        Page<User> userPage = userService.filterUsers(username, address, phone, email, role, pageable);
        List<Map<String, Object>> users = userPage.getContent().stream().map(this::toUserResponse).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Users retrieved successfully");
        response.put("users", users);
        response.put("currentPage", userPage.getNumber());
        response.put("totalItems", userPage.getTotalElements());
        response.put("totalPages", userPage.getTotalPages());
        return response;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getUserById(@PathVariable String id, Authentication authentication) {
        User user = userService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        ApiAccess.requireSelfOrAdmin(authentication, user.getEmail());
        return Map.of("message", "User retrieved successfully", "data", toUserResponse(user));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        if (!user.isEnabled() || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        String token = jwtService.generateToken(user);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Login successful");
        response.put("token", token);
        response.put("tokenType", "Bearer");
        response.put("expiresInSeconds", jwtService.getExpirationSeconds());
        response.put("user", toUserResponse(user));
        return response;
    }

    @DeleteMapping("/logout")
    public Map<String, Object> logout(Authentication authentication) {
        User user = currentUser(authentication);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userService.save(user);
        return Map.of("message", "Logout successful. All tokens for this user are now invalid.");
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> updateUser(
            @PathVariable String id,
            @ModelAttribute UserRequest request,
            Authentication authentication
    ) throws IOException {
        ApiAccess.requireAdmin(authentication);
        User existing = userService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String username = required(request.getUsername(), "Username is required");
        String email = validEmail(request.getEmail());
        if (userService.emailBelongsToAnotherUser(email, existing.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already used by another user");
        }

        String departmentId = StringUtils.hasText(request.getDepartmentId())
                ? request.getDepartmentId().trim()
                : existing.getDepartmentId();
        Department department = departmentService.requireById(departmentId);

        String role = StringUtils.hasText(request.getRole())
                ? UserService.normalizeRole(request.getRole())
                : UserService.normalizeRole(existing.getRole());
        boolean invalidateTokens = !existing.getEmail().equalsIgnoreCase(email)
                || existing.isEnabled() != (request.getEnabled() == null ? existing.isEnabled() : request.getEnabled())
                || !UserService.normalizeRole(existing.getRole()).equals(role);

        existing.setUsername(username);
        existing.setEmail(email);
        existing.setAddress(trim(request.getAddress()));
        existing.setPhone(trim(request.getPhone()));
        existing.setRole(role);
        existing.setEnabled(request.getEnabled() == null ? existing.isEnabled() : request.getEnabled());
        existing.setDepartmentId(department.getId());

        if (hasFile(request.getProfileImage())) {
            deleteProfileImage(existing.getProfileImageFileName());
            existing.setProfileImageFileName(saveProfileImage(existing.getId(), request.getProfileImage()));
        }
        if (invalidateTokens) {
            existing.setTokenVersion(existing.getTokenVersion() + 1);
        }

        User saved = userService.save(existing);
        return Map.of("message", "User updated successfully", "data", toUserResponse(saved));
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication
    ) {
        User user = userService.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        ApiAccess.requireSelfOrAdmin(authentication, user.getEmail());

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid old password");
        }
        setNewPassword(user, request.getNewPassword(), request.getConfirmNewPassword());
        return Map.of("message", "Password changed successfully. Please login again.");
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            Authentication authentication
    ) {
        ApiAccess.requireAdmin(authentication);
        User user = userService.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        setNewPassword(user, request.getNewPassword(), request.getConfirmNewPassword());
        return Map.of("message", "Password reset successfully. Existing tokens are invalid.");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable String id, Authentication authentication) throws IOException {
        ApiAccess.requireAdmin(authentication);
        User user = userService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        deleteProfileImage(user.getProfileImageFileName());
        userService.delete(user);
        return Map.of("message", "User deleted successfully");
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> getProfileImage(@PathVariable String id, Authentication authentication) throws IOException {
        User user = userService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        ApiAccess.requireSelfOrAdmin(authentication, user.getEmail());
        if (!StringUtils.hasText(user.getProfileImageFileName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No profile image found for this user");
        }

        Path imagePath = safeUploadPath(user.getProfileImageFileName());
        if (!Files.isRegularFile(imagePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile image file was not found");
        }
        String contentType = Files.probeContentType(imagePath);
        Resource resource = new FileSystemResource(imagePath);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(imagePath.getFileName().toString()).build().toString())
                .body(resource);
    }

    private void validateCreateRequest(UserRequest request) {
        required(request.getUsername(), "Username is required");
        validEmail(request.getEmail());
        required(request.getPassword(), "Password is required");
        required(request.getDepartmentId(), "Department is required");
    }

    private void setNewPassword(User user, String newPassword, String confirmNewPassword) {
        if (!newPassword.equals(confirmNewPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password and confirmation must match");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userService.save(user);
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return userService.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user no longer exists"));
    }

    private Map<String, Object> toUserResponse(User user) {
        Department department = departmentService.getById(user.getDepartmentId());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("address", user.getAddress());
        response.put("phone", user.getPhone());
        response.put("role", UserService.normalizeRole(user.getRole()));
        response.put("enabled", user.isEnabled());
        response.put("isEnabled", user.isEnabled());
        response.put("departmentId", user.getDepartmentId());
        response.put("departmentName", department == null ? "" : department.getDepartmentName());
        response.put("division", department == null ? "" : department.getDivision());
        response.put("department", departmentToMap(department));
        response.put("profileImageUrl", StringUtils.hasText(user.getProfileImageFileName())
                ? "/api/users/" + user.getId() + "/image"
                : null);
        response.put("createdAt", user.getCreatedAt());
        response.put("updatedAt", user.getUpdatedAt());
        return response;
    }

    private Map<String, Object> departmentToMap(Department department) {
        if (department == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", department.getId());
        result.put("division", department.getDivision());
        result.put("departmentName", department.getDepartmentName());
        return result;
    }

    private String saveProfileImage(String userId, MultipartFile file) throws IOException {
        if (file.getSize() > MAX_PROFILE_IMAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile image cannot exceed 5MB");
        }
        String extension = extensionFor(file);
        Files.createDirectories(uploadDirectory);
        String filename = userId + "_" + UUID.randomUUID() + extension;
        Path destination = safeUploadPath(filename);
        file.transferTo(destination);
        return filename;
    }

    private void deleteProfileImage(String filename) throws IOException {
        if (StringUtils.hasText(filename)) {
            Files.deleteIfExists(safeUploadPath(filename));
        }
    }

    private Path safeUploadPath(String filename) {
        Path file = uploadDirectory.resolve(filename).normalize();
        if (!file.startsWith(uploadDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid profile image path");
        }
        return file;
    }

    private String extensionFor(MultipartFile file) {
        String contentType = file.getContentType();
        if (MediaType.IMAGE_JPEG_VALUE.equals(contentType)) return ".jpg";
        if (MediaType.IMAGE_PNG_VALUE.equals(contentType)) return ".png";
        if (MediaType.IMAGE_GIF_VALUE.equals(contentType)) return ".gif";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPEG, PNG, and GIF profile images are allowed");
    }

    private boolean hasFile(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    private String validEmail(String value) {
        String email = required(value, "Email is required").toLowerCase();
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email must be valid");
        }
        return email;
    }

    private String required(String value, String message) {
        String result = trim(value);
        if (!StringUtils.hasText(result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return result;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
