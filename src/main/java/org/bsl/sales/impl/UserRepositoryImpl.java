package org.bsl.sales.impl;


import org.bsl.sales.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class UserRepositoryImpl {

    private final MongoTemplate mongoTemplate;

    public UserRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Page<User> filterUsers(
            String username,
            String address,
            String phone,
            String email,
            String role,
            Pageable pageable
    ) {
        Criteria criteria = new Criteria();
        List<Criteria> criteriaList = new ArrayList<>();

        if (username != null && !username.isEmpty()) {
            criteriaList.add(Criteria.where("username").regex(username, "i"));
        }
        if (address != null && !address.isEmpty()) {
            criteriaList.add(Criteria.where("address").regex(address, "i"));
        }
        if (phone != null && !phone.isEmpty()) {
            criteriaList.add(Criteria.where("phone").regex(phone, "i"));
        }
        if (email != null && !email.isEmpty()) {
            criteriaList.add(Criteria.where("email").regex(email, "i"));
        }
        if (role != null && !role.isEmpty()) {
            criteriaList.add(Criteria.where("role").regex(role, "i"));
        }

        // If no filter parameters are provided, return all users
        if (!criteriaList.isEmpty()) {
            criteria.andOperator(criteriaList.toArray(new Criteria[0]));
        }

        Query query = new Query(criteria);
        long count = mongoTemplate.count(query, User.class);
        query.with(pageable);

        List<User> list = mongoTemplate.find(query, User.class);

        return new PageImpl<>(list, pageable, count);
    }
}