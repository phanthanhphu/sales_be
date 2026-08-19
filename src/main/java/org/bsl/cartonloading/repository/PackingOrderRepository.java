package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.PackingOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PackingOrderRepository extends MongoRepository<PackingOrder, String> {
    List<PackingOrder> findByBuyerCode(String buyerCode);
    Optional<PackingOrder> findByIdAndBuyerCode(String id, String buyerCode);
}
