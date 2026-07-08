package org.bsl.sales.config;

import org.bsl.sales.model.Department;
import org.bsl.sales.model.User;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import jakarta.annotation.PostConstruct;

@Configuration
public class MongoIndexConfig {
    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    void createIndexes() {
        mongoTemplate.indexOps(User.class)
                .ensureIndex(new Index().on("email", Sort.Direction.ASC).unique().named("email_unique"));
        mongoTemplate.indexOps(User.class)
                .ensureIndex(new Index().on("departmentId", Sort.Direction.ASC).named("user_department_id"));
        mongoTemplate.indexOps(Department.class)
                .ensureIndex(new Index().on("division", Sort.Direction.ASC)
                        .on("departmentName", Sort.Direction.ASC)
                        .unique()
                        .named("division_department_unique"));
    }
}
