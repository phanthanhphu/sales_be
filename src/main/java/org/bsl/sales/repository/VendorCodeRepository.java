package org.bsl.sales.repository;

import org.bsl.sales.model.VendorCode;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VendorCodeRepository extends MongoRepository<VendorCode, String> {

    Optional<VendorCode> findByShortNameSupplierKey(String shortNameSupplierKey);

    boolean existsByShortNameSupplierKey(String shortNameSupplierKey);


    Optional<VendorCode> findByMasterKey(String masterKey);

    boolean existsByMasterKey(String masterKey);

    List<VendorCode> findAllByMasterKeyIn(Collection<String> masterKeys);

    List<VendorCode> findAllByShortNameSupplierKeyIn(Collection<String> supplierKeys);
}
