package org.bsl.sales.security;

import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.Buyer;
import org.bsl.sales.model.User;
import org.bsl.sales.repository.BuyerRepository;
import org.bsl.sales.repository.UserRepository;
import org.bsl.sales.support.BuyerKeys;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class BuyerAccessService {
    private final UserRepository userRepository;
    private final BuyerRepository buyerRepository;

    public BuyerAccessService(UserRepository userRepository, BuyerRepository buyerRepository) {
        this.userRepository = userRepository;
        this.buyerRepository = buyerRepository;
    }

    public String requireBuyer(String requestedBuyerKey) {
        String buyerKey = BuyerKeys.normalize(requestedBuyerKey);
        Buyer buyer = buyerRepository.findByBuyerKey(buyerKey)
                .orElseThrow(() -> new MasterDataValidationException("Unknown Buyer: " + buyerKey));
        if (!buyer.isActive()) throw new MasterDataValidationException("Buyer is inactive: " + buyer.getBuyerName());
        requireAccess(buyerKey);
        return buyerKey;
    }

    public String legacyBuyer(String storedBuyerKey) {
        return BuyerKeys.legacyDefault(storedBuyerKey);
    }

    public void requireAccess(String buyerKey) {
        User user = currentUser().orElseThrow(() -> new AccessDeniedException("Authentication is required"));
        String normalized = BuyerKeys.normalize(buyerKey);
        if (!user.isEnabled() || (!user.isAdminRole() && !user.getBuyerKeys().contains(normalized))) {
            throw new AccessDeniedException("You do not have access to Buyer " + normalized);
        }
    }

    public void requireEntityAccess(String storedBuyerKey) {
        requireBuyer(legacyBuyer(storedBuyerKey));
    }

    public boolean canAccess(String buyerKey) {
        try {
            requireAccess(buyerKey);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public List<String> accessibleBuyerKeys() {
        User user = currentUser().orElseThrow(() -> new AccessDeniedException("Authentication is required"));
        if (user.isAdminRole()) {
            return buyerRepository.findByActiveTrueOrderBySequenceAscBuyerNameAsc().stream()
                    .map(Buyer::getBuyerKey)
                    .collect(Collectors.toList());
        }
        Set<String> active = buyerRepository.findByActiveTrueOrderBySequenceAscBuyerNameAsc().stream()
                .map(Buyer::getBuyerKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return user.getBuyerKeys().stream().filter(active::contains).collect(Collectors.toList());
    }

    public List<Buyer> accessibleBuyers() {
        Set<String> keys = new LinkedHashSet<>(accessibleBuyerKeys());
        return buyerRepository.findByActiveTrueOrderBySequenceAscBuyerNameAsc().stream()
                .filter(item -> keys.contains(item.getBuyerKey()))
                .collect(Collectors.toList());
    }

    private Optional<User> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return Optional.empty();
        }
        return userRepository.findByEmail(authentication.getName());
    }
}
