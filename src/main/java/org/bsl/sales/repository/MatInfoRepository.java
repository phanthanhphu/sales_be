package org.bsl.sales.repository;

import org.bsl.sales.model.MatInfo;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MatInfoRepository extends MongoRepository<MatInfo, String> {
    Optional<MatInfo> findByBuyerKeyAndCheckingKey(String buyerKey, String checkingKey);
    boolean existsByBuyerKeyAndCheckingKey(String buyerKey, String checkingKey);
    List<MatInfo> findByBuyerKey(String buyerKey);
    boolean existsByShortNameSupplierKey(String shortNameSupplierKey);
    boolean existsByCurrency(String currency);
    long countByBuyerKeyAndMaterialTypeKey(String buyerKey, String materialTypeKey);
    long countByMaterialTypeKey(String materialTypeKey);
    Optional<MatInfo> findByMasterKey(String masterKey);
    boolean existsByMasterKey(String masterKey);
    List<MatInfo> findAllByMasterKeyIn(Collection<String> masterKeys);
    List<MatInfo> findAllByBuyerKeyAndMasterKeyIn(String buyerKey, Collection<String> masterKeys);
    List<MatInfo> findAllByBuyerKeyAndCheckingKeyIn(String buyerKey, Collection<String> checkingKeys);
}
