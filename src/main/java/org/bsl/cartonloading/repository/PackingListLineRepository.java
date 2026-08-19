package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.PackingListLine;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PackingListLineRepository extends MongoRepository<PackingListLine, String> {
    List<PackingListLine> findByOrderIdAndBuyerCode(String orderId, String buyerCode);
    Optional<PackingListLine> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    long countByOrderIdAndBuyerCode(String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
