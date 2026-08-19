package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.ScaleStation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ScaleStationRepository extends MongoRepository<ScaleStation, String> {
    Optional<ScaleStation> findByStationCode(String stationCode);
    List<ScaleStation> findByActiveTrueOrderByStationCodeAsc();
    List<ScaleStation> findAllByOrderByStationCodeAsc();
    boolean existsByStationCode(String stationCode);
}
