package org.bsl.sales.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

final class ApiAccess {
    private ApiAccess() {
    }

    static void requireAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator permission is required");
        }
    }

    static void requireSelfOrAdmin(Authentication authentication, String email) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        boolean isSelf = authentication.getName().equalsIgnoreCase(email);
        if (!isAdmin && !isSelf) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to access this user");
        }
    }
}
