package org.bsl.sales.repository;

import org.bsl.sales.model.Loss;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface LossRepository extends MongoRepository<Loss, String> {

    Optional<Loss> findByMaterialGroupKey(String materialGroupKey);

    boolean existsByMaterialGroupKey(String materialGroupKey);


    Optional<Loss> findByMasterKey(String masterKey);

    boolean existsByMasterKey(String masterKey);
}
