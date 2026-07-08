package org.bsl.sales.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Document(collection = "users")
public class User {
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public static final String ACCESS_BOM = "BOM";
    public static final String ACCESS_SALES = "SALES";
    public static final String ACCESS_VIEW_SYSTEM = "VIEW_SYSTEM";

    @Id
    private String id;
    private String username;
    private String email;
    private String password;
    private String address;
    private String phone;
    private String role = ROLE_USER;
    private LocalDateTime createdAt;
    private String profileImageUrl;
    private boolean isEnabled;
    private long tokenVersion;
    private String departmentId;

    /**
     * User module access. ADMIN is always treated as full BOM + SALES access.
     * VIEW_SYSTEM is read-only and intentionally exclusive.
     */
    private List<String> accessPermissions = new ArrayList<>();

    public static String normalizeRole(String value) {
        if (value == null || value.trim().isEmpty()) {
            return ROLE_USER;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ROLE_ADMIN".equals(normalized) || ROLE_ADMIN.equals(normalized) || "ADMINISTRATOR".equals(normalized)) {
            return ROLE_ADMIN;
        }

        // Legacy LEADER accounts are intentionally migrated at read/write time to USER.
        return ROLE_USER;
    }

    public static List<String> normalizeAccessPermissions(Collection<String> values, boolean admin) {
        if (admin) {
            return List.of(ACCESS_BOM, ACCESS_SALES);
        }

        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String permission = value.trim().toUpperCase(Locale.ROOT);
                if (ACCESS_BOM.equals(permission) || ACCESS_SALES.equals(permission) || ACCESS_VIEW_SYSTEM.equals(permission)) {
                    normalized.add(permission);
                }
            }
        }

        // Read-only access is never combined with a write permission.
        if (normalized.contains(ACCESS_VIEW_SYSTEM)) {
            return List.of(ACCESS_VIEW_SYSTEM);
        }

        // Existing users without an access list safely become view-only.
        if (normalized.isEmpty()) {
            return List.of(ACCESS_VIEW_SYSTEM);
        }

        return new ArrayList<>(normalized);
    }

    public boolean isAdminRole() {
        return ROLE_ADMIN.equals(normalizeRole(role));
    }

    public boolean canManageBom() {
        return isAdminRole() || getAccessPermissions().contains(ACCESS_BOM);
    }

    public boolean canManageSales() {
        return isAdminRole() || getAccessPermissions().contains(ACCESS_SALES);
    }

    public boolean isViewOnly() {
        return !isAdminRole()
                && getAccessPermissions().size() == 1
                && getAccessPermissions().contains(ACCESS_VIEW_SYSTEM);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getRole() { return normalizeRole(role); }
    public void setRole(String role) { this.role = normalizeRole(role); }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getProfileImageUrl() { return profileImageUrl; }
    public void setProfileImageUrl(String profileImageUrl) { this.profileImageUrl = profileImageUrl; }

    public boolean isEnabled() { return isEnabled; }
    public void setEnabled(boolean enabled) { isEnabled = enabled; }

    public long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(long tokenVersion) { this.tokenVersion = tokenVersion; }

    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }

    public List<String> getAccessPermissions() {
        return normalizeAccessPermissions(accessPermissions, isAdminRole());
    }

    public void setAccessPermissions(List<String> accessPermissions) {
        this.accessPermissions = new ArrayList<>(normalizeAccessPermissions(accessPermissions, isAdminRole()));
    }
}
