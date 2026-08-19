package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.PackingAllocationLine;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PackingAllocationLineRepository extends MongoRepository<PackingAllocationLine, String> {
    List<PackingAllocationLine> findByOrderIdAndBuyerCode(String orderId, String buyerCode);
    Optional<PackingAllocationLine> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    long countByOrderIdAndBuyerCode(String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
