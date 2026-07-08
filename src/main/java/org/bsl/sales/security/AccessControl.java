package org.bsl.sales.security;

import org.bsl.sales.model.User;
import org.bsl.sales.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("accessControl")
public class AccessControl {
    private final UserRepository userRepository;

    public AccessControl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isAdmin() {
        return currentUser().map(user -> user.isEnabled() && user.isAdminRole()).orElse(false);
    }

    public boolean canManageBom() {
        return currentUser().map(user -> user.isEnabled() && user.canManageBom()).orElse(false);
    }

    public boolean canManageSales() {
        return currentUser().map(user -> user.isEnabled() && user.canManageSales()).orElse(false);
    }

    public boolean canAccessUser(String userId) {
        return currentUser().map(user -> user.isEnabled() && (user.isAdminRole() || user.getId().equals(userId))).orElse(false);
    }

    public boolean canChangeOwnPassword(String email) {
        return currentUser().map(user -> user.isEnabled() && user.getEmail() != null && user.getEmail().equalsIgnoreCase(email)).orElse(false);
    }

    private Optional<User> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return Optional.empty();
        }
        return userRepository.findByEmail(authentication.getName());
    }
}
