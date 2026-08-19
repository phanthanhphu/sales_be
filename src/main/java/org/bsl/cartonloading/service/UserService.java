package org.bsl.cartonloading.service;

import org.bsl.cartonloading.dto.UserDTO;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final BuyerRepository buyerRepository;

    public UserService(UserRepository userRepository, MongoTemplate mongoTemplate, BuyerRepository buyerRepository) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.buyerRepository = buyerRepository;
    }

    public User saveUser(User user) {
        if (user.getId() == null || user.getId().trim().isEmpty()) {
            user.setCreatedAt(LocalDateTime.now());
        }
        user.setRole(user.getRole());
        user.setAccessPermissions(user.getAccessPermissions());
        user.setBuyerPermissions(validateBuyerPermissions(user));
        return userRepository.save(user);
    }

    public Optional<User> findById(String id) { return userRepository.findById(id); }
    public Optional<User> findByEmail(String email) { return userRepository.findByEmail(email); }
    public void deleteUser(String id) { userRepository.deleteById(id); }

    public User updateUser(String id, User data) {
        Optional<User> optional = userRepository.findById(id);
        if (optional.isEmpty()) return null;

        User existing = optional.get();
        existing.setUsername(data.getUsername());
        existing.setEmail(data.getEmail());
        existing.setAddress(data.getAddress());
        existing.setPhone(data.getPhone());
        existing.setRole(data.getRole());
        existing.setAccessPermissions(data.getAccessPermissions());
        existing.setBuyerPermissions(validateBuyerPermissions(data));
        existing.setDepartmentId(data.getDepartmentId());
        existing.setEnabled(data.isEnabled());
        existing.setTokenVersion(data.getTokenVersion() > 0 ? data.getTokenVersion() : existing.getTokenVersion());
        existing.setProfileImageUrl(data.getProfileImageUrl());
        if (existing.getCreatedAt() == null) {
            existing.setCreatedAt(data.getCreatedAt() != null ? data.getCreatedAt() : LocalDateTime.now());
        }
        return userRepository.save(existing);
    }

    public Page<UserDTO> filterUsers(
            String username,
            String address,
            String phone,
            String email,
            String role,
            String accessPermission,
            Pageable pageable
    ) {
        Query query = new Query();
        List<Criteria> criteria = new ArrayList<>();

        addRegex(criteria, "username", username);
        addRegex(criteria, "address", address);
        addRegex(criteria, "phone", phone);
        addRegex(criteria, "email", email);

        if (StringUtils.hasText(role)) {
            criteria.add(Criteria.where("role").regex("^" + java.util.regex.Pattern.quote(User.normalizeRole(role)) + "$", "i"));
        }

        String access = normalizeAccessFilter(accessPermission);
        if (StringUtils.hasText(access)) {
            if (User.ACCESS_VIEW_SYSTEM.equals(access)) {
                criteria.add(new Criteria().orOperator(
                        Criteria.where("accessPermissions").is(User.ACCESS_VIEW_SYSTEM),
                        Criteria.where("accessPermissions").exists(false),
                        Criteria.where("accessPermissions").is(null),
                        Criteria.where("accessPermissions").size(0)
                ));
            } else {
                criteria.add(Criteria.where("accessPermissions").is(access));
            }
        }

        if (!criteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        }

        long total = mongoTemplate.count(query, User.class);
        List<UserDTO> content = mongoTemplate.find(query.with(pageable), User.class)
                .stream()
                .map(this::toUserDTO)
                .collect(Collectors.toList());
        return new PageImpl<>(content, pageable, total);
    }

    private void addRegex(List<Criteria> criteria, String field, String value) {
        if (StringUtils.hasText(value)) {
            criteria.add(Criteria.where(field).regex(java.util.regex.Pattern.quote(value.trim()), "i"));
        }
    }

    private String normalizeAccessFilter(String value) {
        if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value.trim())) return "";
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case User.ACCESS_BOM, User.ACCESS_SALES, User.ACCESS_VIEW_SYSTEM -> normalized;
            default -> "";
        };
    }

    public UserDTO toUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setAddress(user.getAddress());
        dto.setPhone(user.getPhone());
        dto.setRole(user.getRole());
        dto.setProfileImageUrl(user.getProfileImageUrl());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setEnabled(user.isEnabled());
        dto.setDepartmentId(user.getDepartmentId());
        dto.setAccessPermissions(user.getAccessPermissions());
        dto.setCanManageBom(user.canManageBom());
        dto.setCanManageSales(user.canManageSales());
        dto.setViewOnly(user.isViewOnly());
        dto.setBuyerPermissions(user.getBuyerPermissions());
        return dto;
    }

    private List<String> validateBuyerPermissions(User user) {
        if (user != null && user.isAdminRole()) return user.getBuyerPermissions();
        List<String> values = user == null ? List.of() : user.getBuyerPermissions();
        if (values.isEmpty()) {
            throw new IllegalArgumentException("At least one active Buyer permission is required for a non-admin User");
        }
        var activeKeys = buyerRepository.findAll().stream()
                .filter(org.bsl.cartonloading.model.Buyer::isActive)
                .map(org.bsl.cartonloading.model.Buyer::getBuyerKey)
                .collect(java.util.stream.Collectors.toSet());
        List<String> invalid = values.stream().filter(key -> !activeKeys.contains(key)).toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Unknown or inactive Buyer permission: " + String.join(", ", invalid));
        }
        return values;
    }

    public boolean isAdmin(User user) { return user != null && user.isAdminRole(); }
}
