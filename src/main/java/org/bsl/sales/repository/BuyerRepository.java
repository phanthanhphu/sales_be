package org.bsl.sales.repository;

import org.bsl.sales.model.Buyer;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BuyerRepository extends MongoRepository<Buyer, String> {
    Optional<Buyer> findByBuyerKey(String buyerKey);
    boolean existsByBuyerKey(String buyerKey);
    List<Buyer> findByActiveTrueOrderBySequenceAscBuyerNameAsc();
}
