package org.bsl.sales.repository;

import org.bsl.sales.model.MprDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MprDocumentRepository extends MongoRepository<MprDocument, String> {
    Optional<MprDocument> findByOrderId(String orderId);
    List<MprDocument> findByOrderIdIn(List<String> orderIds);
    boolean existsByOrderId(String orderId);
    boolean existsBySelectionsBomId(String bomId);
    boolean existsByLinesShortNameSupplierIgnoreCase(String shortNameSupplier);
    boolean existsByLinesShipToIdsContaining(String shipToId);
}
