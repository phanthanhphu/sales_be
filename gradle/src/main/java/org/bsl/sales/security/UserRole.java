package org.bsl.sales.security;

public final class UserRole {
    private UserRole() {
    }

    public static String normalize(String role) {
        if (role == null || role.isBlank()) {
            return "USER";
        }
        String normalized = role.trim().toUpperCase();
        return ("ADMIN".equals(normalized) || "ROLE_ADMIN".equals(normalized)) ? "ADMIN" : "USER";
    }
}
