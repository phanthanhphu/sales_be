package org.bsl.sales.repository;

import org.bsl.sales.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    Page<User> filterUsers(
            String username,
            String address,
            String phone,
            String email,
            String role,
            Pageable pageable
    );
}