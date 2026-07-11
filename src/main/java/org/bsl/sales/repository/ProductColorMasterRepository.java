package org.bsl.sales.repository;

import org.bsl.sales.model.ProductColorMaster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProductColorMasterRepository extends MongoRepository<ProductColorMaster, String> {
    Optional<ProductColorMaster> findByMasterKey(String masterKey);
    Optional<ProductColorMaster> findFirstByProductColorIgnoreCase(String productColor);
    Page<ProductColorMaster> findByProductColorContainingIgnoreCase(String productColor, Pageable pageable);
}
