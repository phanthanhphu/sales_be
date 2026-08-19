package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.AuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AuditLogRepository extends MongoRepository<AuditLog, String> {

    List<AuditLog> findByUsername(String username);

    List<AuditLog> findByResourceType(String resourceType);

}