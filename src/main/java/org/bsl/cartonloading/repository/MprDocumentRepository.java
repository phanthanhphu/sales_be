package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.MprDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MprDocumentRepository extends MongoRepository<MprDocument, String> {
    Optional<MprDocument> findByOrderId(String orderId);
    boolean existsByOrderId(String orderId);
}
