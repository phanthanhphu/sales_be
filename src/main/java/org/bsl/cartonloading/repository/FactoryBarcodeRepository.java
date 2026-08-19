package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.FactoryBarcode;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface FactoryBarcodeRepository extends MongoRepository<FactoryBarcode, String> {
    Optional<FactoryBarcode> findByBarcode(String barcode);
    List<FactoryBarcode> findByBatchIdOrderByRunningNumberAsc(String batchId);
}
