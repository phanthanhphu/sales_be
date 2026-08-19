package org.bsl.cartonloading.service;

import jakarta.annotation.PostConstruct;
import org.bsl.cartonloading.dto.BuyerRequest;
import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BuyerService {
    private static final Map<String, String> DEFAULT_NAMES = new LinkedHashMap<>();
    static {
        DEFAULT_NAMES.put(BuyerAccess.LL_BEAN, "L.L.BEAN");
        DEFAULT_NAMES.put(BuyerAccess.TNF, "TNF");
        DEFAULT_NAMES.put(BuyerAccess.PATAGONA, "PATAGONA");
        DEFAULT_NAMES.put(BuyerAccess.LULULEMON, "LULULEMON");
        DEFAULT_NAMES.put(BuyerAccess.FILSON, "FILSON");
        DEFAULT_NAMES.put(BuyerAccess.ENGELBERT_STRAUSS, "ENGELBERT STRAUSS");
    }

    private final BuyerRepository buyerRepository;
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;

    public BuyerService(BuyerRepository buyerRepository, UserRepository userRepository, MongoTemplate mongoTemplate) {
        this.buyerRepository = buyerRepository;
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void seedDefaults() {
        if (buyerRepository.count() > 0) return;
        int sequence = 10;
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, String> entry : DEFAULT_NAMES.entrySet()) {
            Buyer buyer = new Buyer();
            buyer.setBuyerKey(entry.getKey());
            buyer.setBuyerName(entry.getValue());
            buyer.setSlug(slugify(entry.getValue()));
            buyer.setActive(true);
            buyer.setSequence(sequence);
            buyer.setDescription("Default Buyer workspace");
            buyer.setCreatedAt(now);
            buyer.setUpdatedAt(now);
            buyer.setCreatedBy("SYSTEM");
            buyer.setUpdatedBy("SYSTEM");
            buyerRepository.save(buyer);
            sequence += 10;
        }
    }

    public List<Buyer> loginOptions() {
        return buyerRepository.findByActiveTrueOrderBySequenceAscBuyerNameAsc();
    }

    public List<Buyer> accessible() {
        User user = currentUser();
        List<Buyer> active = loginOptions();
        if (user.isAdminRole()) return active;
        Set<String> allowed = Set.copyOf(user.getBuyerPermissions());
        return active.stream().filter(item -> allowed.contains(item.getBuyerKey())).toList();
    }

    public Page<Buyer> list(String keyword, Boolean active, boolean paged, int page, int size) {
        String search = clean(keyword);
        List<Buyer> rows = buyerRepository.findAllByOrderBySequenceAscBuyerNameAsc().stream()
                .filter(item -> active == null || item.isActive() == active)
                .filter(item -> search == null
                        || contains(item.getBuyerKey(), search)
                        || contains(item.getBuyerName(), search)
                        || contains(item.getDescription(), search))
                .sorted(Comparator.comparingInt(Buyer::getSequence)
                        .thenComparing(Buyer::getBuyerName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
        if (!paged) return new PageImpl<>(rows);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(200, size)));
        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public Buyer get(String id) {
        return buyerRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Buyer not found"));
    }

    public Buyer create(BuyerRequest request) {
        String key = requireKey(request.buyerKey());
        if (buyerRepository.findByBuyerKeyIgnoreCase(key).isPresent()) {
            throw new IllegalArgumentException("Buyer Key already exists: " + key);
        }
        Buyer buyer = new Buyer();
        buyer.setBuyerKey(key);
        apply(buyer, request, false);
        LocalDateTime now = LocalDateTime.now();
        buyer.setCreatedAt(now);
        buyer.setUpdatedAt(now);
        buyer.setCreatedBy(RequestActor.current());
        buyer.setUpdatedBy(RequestActor.current());
        return buyerRepository.save(buyer);
    }

    public Buyer update(String id, BuyerRequest request) {
        Buyer buyer = get(id);
        String requestedKey = requireKey(request.buyerKey());
        if (!buyer.getBuyerKey().equals(requestedKey)) {
            throw new IllegalArgumentException("Buyer Key cannot be changed after creation");
        }
        apply(buyer, request, true);
        buyer.setUpdatedAt(LocalDateTime.now());
        buyer.setUpdatedBy(RequestActor.current());
        return buyerRepository.save(buyer);
    }

    public void delete(String id) {
        Buyer buyer = get(id);
        String key = buyer.getBuyerKey();
        boolean assigned = userRepository.findAll().stream()
                .filter(user -> !user.isAdminRole())
                .anyMatch(user -> user.getBuyerPermissions().contains(key));
        if (assigned) {
            throw new IllegalArgumentException("Buyer is assigned to one or more Users. Remove the permission or set Buyer to Inactive.");
        }
        if (hasBuyerData(key)) {
            throw new IllegalArgumentException("Buyer already has operational data. Set it to Inactive instead of deleting it.");
        }
        buyerRepository.delete(buyer);
    }

    public boolean existsActive(String buyerKey) {
        String key = BuyerAccess.normalize(buyerKey);
        return !key.isEmpty() && buyerRepository.findByBuyerKeyIgnoreCase(key).map(Buyer::isActive).orElse(false);
    }

    public List<String> validateAssignedKeys(List<String> values) {
        List<String> normalized = BuyerAccess.normalizeAll(values, false);
        if (normalized.isEmpty()) return normalized;
        Map<String, Buyer> byKey = buyerRepository.findAll().stream()
                .collect(Collectors.toMap(Buyer::getBuyerKey, value -> value, (a, b) -> a));
        List<String> invalid = normalized.stream()
                .filter(key -> !byKey.containsKey(key) || !byKey.get(key).isActive())
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Unknown or inactive Buyer permission: " + String.join(", ", invalid));
        }
        return normalized;
    }

    private void apply(Buyer buyer, BuyerRequest request, boolean updating) {
        String name = clean(request.buyerName());
        if (name == null) throw new IllegalArgumentException("Buyer Name is required");
        String slug = uniqueSlug(slugify(name), updating ? buyer.getId() : null);
        buyer.setBuyerName(name);
        buyer.setSlug(slug);
        buyer.setActive(request.active() == null || request.active());
        buyer.setSequence(request.sequence() == null ? 0 : Math.max(0, request.sequence()));
        buyer.setDescription(clean(request.description()));
    }

    private String uniqueSlug(String base, String currentId) {
        String candidate = base;
        int suffix = 2;
        while (true) {
            var existing = buyerRepository.findBySlugIgnoreCase(candidate);
            if (existing.isEmpty() || existing.get().getId().equals(currentId)) return candidate;
            candidate = base + "-" + suffix++;
        }
    }

    private boolean hasBuyerData(String key) {
        for (String collection : List.of(
                "packing_orders", "packing_allocation_lines", "packing_list_lines",
                "carton_scan_transactions", "mat_info"
        )) {
            if (mongoTemplate.exists(Query.query(Criteria.where("buyerCode").is(key)), collection)) return true;
        }
        return false;
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new IllegalArgumentException("Authentication is required");
        }
        return userRepository.findByEmail(authentication.getName())
                .filter(User::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("Current User is not available"));
    }

    private String requireKey(String value) {
        String key = BuyerAccess.normalize(value);
        if (key.isEmpty()) throw new IllegalArgumentException("Buyer Key is required and may contain letters, numbers and underscore only");
        return key;
    }

    private String slugify(String value) {
        String slug = clean(value);
        if (slug == null) return "buyer";
        slug = slug.toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "buyer" : slug;
    }

    private String clean(String value) {
        if (value == null) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.isEmpty() ? null : clean;
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(keyword.toUpperCase(Locale.ROOT));
    }
}
