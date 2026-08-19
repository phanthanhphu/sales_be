package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.Buyer;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BuyerRepository extends MongoRepository<Buyer, String> {
    Optional<Buyer> findByBuyerKeyIgnoreCase(String buyerKey);
    Optional<Buyer> findBySlugIgnoreCase(String slug);
    List<Buyer> findByActiveTrueOrderBySequenceAscBuyerNameAsc();
    List<Buyer> findAllByOrderBySequenceAscBuyerNameAsc();
}
