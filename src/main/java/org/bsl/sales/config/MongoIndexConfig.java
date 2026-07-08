// src/main/java/org/bsl/pricecomparison/config/MongoIndexConfig.java
package org.bsl.sales.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import jakarta.annotation.PostConstruct;

@Configuration
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void createIndexes() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("requisition_monthly");

        // Drop old index if exists
        try {
            collection.dropIndex("departmentRequisitions.name_1");
            System.out.println("Dropped legacy index: departmentRequisitions.name_1");
        } catch (Exception e) {
            // Ignore if index doesn't exist
        }

        // Create essential single-field indexes
        collection.createIndex(Indexes.ascending("groupId"));
        collection.createIndex(Indexes.ascending("type"));
        collection.createIndex(Indexes.descending("updatedDate"));
        collection.createIndex(Indexes.descending("createdDate"));
        collection.createIndex(Indexes.ascending("oldSAPCode"));
        collection.createIndex(Indexes.ascending("productType1Name"));
        collection.createIndex(Indexes.ascending("productType2Name"));

        // Create partial index on nested field with explicit name
        collection.createIndex(
                Indexes.ascending("departmentRequisitions.name"),
                new IndexOptions()
                        .name("idx_department_name")
                        .partialFilterExpression(new Document("departmentRequisitions.name", new Document("$exists", true)))
        );

        System.out.println("All indexes created successfully using Indexes class 100% operational!");
    }
}