package org.bsl.sales.repository;

import org.bsl.sales.model.MatInfo;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MatInfoRepository extends MongoRepository<MatInfo, String> {

    Optional<MatInfo> findByCheckingKey(String checkingKey);

    boolean existsByCheckingKey(String checkingKey);

    boolean existsByShortNameSupplierKey(String shortNameSupplierKey);

    /** MAT_INFO stores the selected currency code in upper-case form. */
    boolean existsByCurrency(String currency);

    long countByMaterialTypeKey(String materialTypeKey);


    Optional<MatInfo> findByMasterKey(String masterKey);

    boolean existsByMasterKey(String masterKey);
}
