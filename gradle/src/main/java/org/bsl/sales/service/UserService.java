package org.bsl.sales.service;

import org.bsl.sales.model.User;
import org.bsl.sales.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;

    public UserService(UserRepository userRepository, MongoTemplate mongoTemplate) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public boolean hasUsers() {
        return userRepository.count() > 0;
    }

    public User create(User user) {
        if (user.getId() == null) {
            user.setId(UUID.randomUUID().toString());
        }
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setEmail(normalizeEmail(user.getEmail()));
        user.setRole(normalizeRole(user.getRole()));
        user.setTokenVersion(Math.max(1L, user.getTokenVersion()));
        try {
            return userRepository.save(user);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("User with this email already exists");
        }
    }

    public User save(User user) {
        user.setUpdatedAt(LocalDateTime.now());
        user.setEmail(normalizeEmail(user.getEmail()));
        user.setRole(normalizeRole(user.getRole()));
        try {
            return userRepository.save(user);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("User with this email already exists");
        }
    }

    public Optional<User> findById(String id) {
        return StringUtils.hasText(id) ? userRepository.findById(id.trim()) : Optional.empty();
    }

    public Optional<User> findByEmail(String email) {
        return StringUtils.hasText(email)
                ? userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                : Optional.empty();
    }

    public boolean emailBelongsToAnotherUser(String email, String userId) {
        return findByEmail(email).map(user -> !user.getId().equals(userId)).orElse(false);
    }

    public long countByDepartmentId(String departmentId) {
        return userRepository.countByDepartmentId(departmentId);
    }

    public Page<User> filterUsers(
            String username,
            String address,
            String phone,
            String email,
            String role,
            Pageable pageable
    ) {
        List<Criteria> criteria = new ArrayList<>();
        addContains(criteria, "username", username);
        addContains(criteria, "address", address);
        addContains(criteria, "phone", phone);
        addContains(criteria, "email", email);

        if (StringUtils.hasText(role) && !"ALL".equalsIgnoreCase(role.trim())) {
            criteria.add(Criteria.where("role").regex("^" + Pattern.quote(normalizeRole(role)) + "$", "i"));
        }

        Query query = new Query();
        if (!criteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        }

        long total = mongoTemplate.count(query, User.class);
        List<User> users = mongoTemplate.find(query.with(pageable), User.class);
        return new PageImpl<>(users, pageable, total);
    }

    public void delete(User user) {
        userRepository.delete(user);
    }

    public static String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            return "USER";
        }
        String normalized = role.trim().toUpperCase();
        return ("ADMIN".equals(normalized) || "ROLE_ADMIN".equals(normalized)) ? "ADMIN" : "USER";
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private void addContains(List<Criteria> criteria, String field, String value) {
        if (StringUtils.hasText(value)) {
            criteria.add(Criteria.where(field).regex(Pattern.quote(value.trim()), "i"));
        }
    }
}
