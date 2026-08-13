package org.bsl.sales.repository;

import org.bsl.sales.model.MaterialShipToMapping;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MaterialShipToMappingRepository extends MongoRepository<MaterialShipToMapping, String> {
    Optional<MaterialShipToMapping> findByMasterKey(String masterKey);
    Optional<MaterialShipToMapping> findByBuyerKeyAndMaterialKey(String buyerKey, String materialKey);
    List<MaterialShipToMapping> findAllByMasterKeyIn(Collection<String> masterKeys);
    List<MaterialShipToMapping> findAllByBuyerKeyAndMaterialKeyIn(String buyerKey, Collection<String> materialKeys);
    List<MaterialShipToMapping> findByBuyerKeyAndActiveTrue(String buyerKey);
    List<MaterialShipToMapping> findByBuyerKeyOrderByUpdatedAtDesc(String buyerKey);
}
