package org.bsl.sales.repository;

import org.bsl.sales.model.ShipTo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ShipToRepository extends MongoRepository<ShipTo, String> {
    Optional<ShipTo> findByShipToNameKey(String shipToNameKey);
    Page<ShipTo> findByShipToNameKeyContainingOrShipToCodeContainingOrShipToNameContaining(
            String shipToNameKey,
            String shipToCode,
            String shipToName,
            Pageable pageable
    );
    List<ShipTo> findByActiveTrueOrderByShipToNameAsc();
}
