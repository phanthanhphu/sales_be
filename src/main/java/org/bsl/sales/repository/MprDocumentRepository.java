package org.bsl.sales.repository;

import org.bsl.sales.model.MprDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MprDocumentRepository extends MongoRepository<MprDocument, String> {
    Optional<MprDocument> findByOrderId(String orderId);
    boolean existsByOrderId(String orderId);
}
