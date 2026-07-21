package org.bsl.sales.repository;

import org.bsl.sales.model.ShipTo;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShipToRepository extends MongoRepository<ShipTo, String> {
    Optional<ShipTo> findByShipToNameKey(String shipToNameKey);
    Optional<ShipTo> findByShipToCodeKey(String shipToCodeKey);
    Optional<ShipTo> findByMasterKey(String masterKey);
    List<ShipTo> findAllByMasterKeyIn(Collection<String> masterKeys);
    List<ShipTo> findAllByShipToNameKeyIn(Collection<String> nameKeys);
    List<ShipTo> findAllByShipToCodeKeyIn(Collection<String> codeKeys);
    List<ShipTo> findByActiveTrueOrderByShipToNameAsc();
}
