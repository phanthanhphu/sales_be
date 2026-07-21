package org.bsl.sales.repository;

import org.bsl.sales.model.BomLineDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BomLineDocumentRepository extends MongoRepository<BomLineDocument, String> {
    List<BomLineDocument> findByBomIdOrderBySortOrderAsc(String bomId);
    Page<BomLineDocument> findByBomIdAndPackingIdOrderBySortOrderAsc(String bomId, String packingId, Pageable pageable);
    Page<BomLineDocument> findByBomIdAndPackingIdIsNullOrderBySortOrderAsc(String bomId, Pageable pageable);
    Optional<BomLineDocument> findByBomIdAndId(String bomId, String id);
    long countByBomId(String bomId);
    long countByBomIdAndPackingId(String bomId, String packingId);
    long countByBomIdAndPackingIdIsNull(String bomId);
    void deleteByBomId(String bomId);
    void deleteByBomIdAndPackingId(String bomId, String packingId);
}
