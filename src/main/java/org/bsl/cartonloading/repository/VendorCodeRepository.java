package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.VendorCode;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface VendorCodeRepository extends MongoRepository<VendorCode, String> {

    Optional<VendorCode> findByShortNameSupplierKey(String shortNameSupplierKey);

    boolean existsByShortNameSupplierKey(String shortNameSupplierKey);


    Optional<VendorCode> findByMasterKey(String masterKey);

    boolean existsByMasterKey(String masterKey);
}
