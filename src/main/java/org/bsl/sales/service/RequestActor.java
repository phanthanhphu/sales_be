package org.bsl.sales.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

final class RequestActor {
    private RequestActor() { }

    static String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "system";
        }
        return authentication.getName();
    }

    static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> {
                    String value = String.valueOf(authority.getAuthority()).toUpperCase();
                    return "ADMIN".equals(value) || "ROLE_ADMIN".equals(value);
                });
    }
}
