package org.bsl.sales.repository;

import org.bsl.sales.model.SalesOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SalesOrderRepository extends MongoRepository<SalesOrder, String> {
    Optional<SalesOrder> findByBuyerKeyAndOrderNoKey(String buyerKey, String orderNoKey);
    boolean existsByBuyerKeyAndOrderNoKey(String buyerKey, String orderNoKey);
    List<SalesOrder> findByBuyerKey(String buyerKey);
}
