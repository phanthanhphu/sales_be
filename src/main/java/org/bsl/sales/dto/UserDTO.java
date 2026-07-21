package org.bsl.sales.dto;

import org.bsl.sales.model.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class UserDTO {
    private String id;
    private String username;
    private String email;
    private String address;
    private String phone;
    private String role;
    private String profileImageUrl;
    private LocalDateTime createdAt;
    private boolean enabled;
    private String departmentId;
    private String departmentName;
    private String division;
    private List<String> accessPermissions = new ArrayList<>();
    private List<String> buyerKeys = new ArrayList<>();
    private boolean canManageBom;
    private boolean canManageSales;
    private boolean viewOnly;

    private String clean(String value) { return value == null ? "" : value.trim(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = clean(id); }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = clean(username); }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = clean(email); }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = clean(address); }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = clean(phone); }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = User.normalizeRole(role); }
    public String getProfileImageUrl() { return profileImageUrl; }
    public void setProfileImageUrl(String profileImageUrl) { this.profileImageUrl = clean(profileImageUrl); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public boolean isEnabled() { return enabled; }
    public boolean getEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = clean(departmentId); }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = clean(departmentName); }
    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = clean(division); }
    public List<String> getAccessPermissions() { return List.copyOf(accessPermissions); }
    public void setAccessPermissions(List<String> accessPermissions) { this.accessPermissions = new ArrayList<>(User.normalizeAccessPermissions(accessPermissions, User.ROLE_ADMIN.equals(getRole()))); }
    public List<String> getBuyerKeys() { return List.copyOf(buyerKeys); }
    public void setBuyerKeys(List<String> buyerKeys) { this.buyerKeys = new ArrayList<>(User.normalizeBuyerKeys(buyerKeys, User.ROLE_ADMIN.equals(getRole()))); }
    public boolean isCanManageBom() { return canManageBom; }
    public boolean getCanManageBom() { return canManageBom; }
    public void setCanManageBom(boolean canManageBom) { this.canManageBom = canManageBom; }
    public boolean isCanManageSales() { return canManageSales; }
    public boolean getCanManageSales() { return canManageSales; }
    public void setCanManageSales(boolean canManageSales) { this.canManageSales = canManageSales; }
    public boolean isViewOnly() { return viewOnly; }
    public boolean getViewOnly() { return viewOnly; }
    public void setViewOnly(boolean viewOnly) { this.viewOnly = viewOnly; }
}
