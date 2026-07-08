package org.bsl.sales.request;

import org.bsl.sales.model.User;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UserRequest {
    private String username;
    private String email;
    private String password;
    private String address;
    private String phone;
    private String role;
    private Boolean isEnabled;
    private String departmentId;

    /** Multipart-friendly value: BOM,SALES or VIEW_SYSTEM. */
    private String accessPermissions;
    private MultipartFile profileImage;

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
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Boolean getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Boolean isEnabled) { this.isEnabled = isEnabled; }
    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }
    public String getAccessPermissions() { return accessPermissions; }
    public void setAccessPermissions(String accessPermissions) { this.accessPermissions = accessPermissions; }
    public MultipartFile getProfileImage() { return profileImage; }
    public void setProfileImage(MultipartFile profileImage) { this.profileImage = profileImage; }

    public List<String> getAccessPermissionList() {
        if (accessPermissions == null || accessPermissions.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String raw = accessPermissions.trim().replace("[", "").replace("]", "").replace("\"", "");
        return User.normalizeAccessPermissions(Arrays.asList(raw.split("[,;|]")), User.ROLE_ADMIN.equals(User.normalizeRole(role)));
    }
}
