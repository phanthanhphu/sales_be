package org.bsl.sales.repository;

import org.bsl.sales.model.ProductColorMaster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProductColorMasterRepository extends MongoRepository<ProductColorMaster, String> {
    Optional<ProductColorMaster> findByBuyerKeyAndMasterKey(String buyerKey, String masterKey);
    Optional<ProductColorMaster> findFirstByBuyerKeyAndPatternNumberIgnoreCaseAndProductColorIgnoreCaseAndSeasonIgnoreCaseAndStyleNumberIgnoreCase(
            String buyerKey,
            String patternNumber,
            String productColor,
            String season,
            String styleNumber
    );
    Page<ProductColorMaster> findByBuyerKeyAndProductColorContainingIgnoreCase(String buyerKey, String productColor, Pageable pageable);
    Page<ProductColorMaster> findByBuyerKey(String buyerKey, Pageable pageable);
    List<ProductColorMaster> findAllByBuyerKey(String buyerKey);
}
