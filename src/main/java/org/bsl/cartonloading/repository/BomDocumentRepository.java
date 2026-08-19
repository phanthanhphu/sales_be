package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.BomDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BomDocumentRepository extends MongoRepository<BomDocument, String> {
    List<BomDocument> findByOrderIdOrderByUpdatedAtDesc(String orderId);
    long countByOrderId(String orderId);
    boolean existsByProductColorsProductColorMasterId(String productColorMasterId);
}
