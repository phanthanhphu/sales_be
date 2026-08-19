package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.enums.CartonScanStatus;
import org.bsl.cartonloading.model.CartonScanTransaction;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CartonScanTransactionRepository extends MongoRepository<CartonScanTransaction, String> {
    Optional<CartonScanTransaction> findByJobId(Long jobId);
    Optional<CartonScanTransaction> findByScanId(String scanId);
    Optional<CartonScanTransaction> findByFactoryBarcode(String factoryBarcode);
    Optional<CartonScanTransaction> findByBuyerCodeAndOrderIdAndFactoryBarcode(String buyerCode, String orderId, String factoryBarcode);
    Optional<CartonScanTransaction> findFirstByStationCodeAndStatusOrderByScannedAtDesc(String stationCode, CartonScanStatus status);
    Optional<CartonScanTransaction> findByIdAndBuyerCode(String id, String buyerCode);
    Optional<CartonScanTransaction> findByBuyerCodeAndOrderIdAndItemKey(String buyerCode, String orderId, String itemKey);
    Optional<CartonScanTransaction> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    long countByPackingLineIdAndStatusIn(String packingLineId, Collection<CartonScanStatus> statuses);
    long countByOrderIdAndBuyerCodeAndStatusIn(String orderId, String buyerCode, Collection<CartonScanStatus> statuses);
    long countByOrderIdAndBuyerCode(String orderId, String buyerCode);
    List<CartonScanTransaction> findTop20ByOrderIdAndBuyerCodeOrderByScannedAtDesc(String orderId, String buyerCode);
    List<CartonScanTransaction> findByOrderIdAndBuyerCode(String orderId, String buyerCode);
    List<CartonScanTransaction> findByOrderIdAndBuyerCodeOrderByOrderCartonSequenceAsc(String orderId, String buyerCode);
    List<CartonScanTransaction> findByOrderIdAndBuyerCodeAndMasterLineIdOrderByCartonSequenceAsc(String orderId, String buyerCode, String masterLineId);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
