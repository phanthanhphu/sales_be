package org.bsl.sales.repository;

import org.bsl.sales.model.Loss;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LossRepository extends MongoRepository<Loss, String> {

    Optional<Loss> findByBuyerKeyAndMaterialGroupKey(String buyerKey, String materialGroupKey);

    boolean existsByBuyerKeyAndMaterialGroupKey(String buyerKey, String materialGroupKey);

    List<Loss> findByBuyerKey(String buyerKey);

    Optional<Loss> findByMasterKey(String masterKey);

    boolean existsByMasterKey(String masterKey);
}
