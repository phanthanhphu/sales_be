package org.bsl.sales.dto;

import org.springframework.web.multipart.MultipartFile;

public class UserRequest {
    private String username;
    private String email;
    private String password;
    private String address;
    private String phone;
    private String role;
    private Boolean enabled;
    private String departmentId;
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
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getIsEnabled() { return enabled; }
    public void setIsEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }
    public MultipartFile getProfileImage() { return profileImage; }
    public void setProfileImage(MultipartFile profileImage) { this.profileImage = profileImage; }
}
