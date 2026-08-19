package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.SalesOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SalesOrderRepository extends MongoRepository<SalesOrder, String> {
    Optional<SalesOrder> findByOrderNoKey(String orderNoKey);
    boolean existsByOrderNoKey(String orderNoKey);
}
