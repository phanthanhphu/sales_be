package org.bsl.sales.repository;

import org.bsl.sales.model.BomDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BomDocumentRepository extends MongoRepository<BomDocument, String> {
    List<BomDocument> findByOrderIdOrderByCreatedAtDescUpdatedAtDesc(String orderId);
    long countByOrderId(String orderId);
    boolean existsByProductColorsProductColorMasterId(String productColorMasterId);
    long countByProductColorsProductColorMasterId(String productColorMasterId);
    boolean existsByOrderIdAndBomNoKey(String orderId, String bomNoKey);
    boolean existsByOrderIdAndBomNoKeyAndIdNot(String orderId, String bomNoKey, String id);
}
