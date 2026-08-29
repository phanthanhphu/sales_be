package org.bsl.sales.repository;

import org.bsl.sales.model.MaterialShipToMapping;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

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
    boolean existsByBuyerKey(String buyerKey);

    @Query(value = "{'buyerKey': ?0, 'active': true, 'shipToIds': ?1}", exists = true)
    boolean existsActiveListReference(String buyerKey, String shipToId);

    @Query(value = "{'buyerKey': ?0, 'active': true, 'shipToId': ?1}", exists = true)
    boolean existsActiveLegacyReference(String buyerKey, String shipToId);

    @Query(value = "{'buyerKey': ?0, 'shipToIds': ?1}", exists = true)
    boolean existsListReference(String buyerKey, String shipToId);

    @Query(value = "{'buyerKey': ?0, 'shipToId': ?1}", exists = true)
    boolean existsLegacyReference(String buyerKey, String shipToId);
}
