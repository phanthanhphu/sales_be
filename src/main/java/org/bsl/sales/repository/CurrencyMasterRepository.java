package org.bsl.sales.repository;

import org.bsl.sales.model.CurrencyMaster;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CurrencyMasterRepository extends MongoRepository<CurrencyMaster, String> {

    List<CurrencyMaster> findByCurrencyCodeKeyOrderByCreatedAtDescUpdatedAtDesc(String currencyCodeKey);

    Optional<CurrencyMaster> findFirstByCurrencyCodeKeyOrderByCreatedAtDescUpdatedAtDesc(String currencyCodeKey);

    boolean existsByCurrencyCodeKey(String currencyCodeKey);

    boolean existsByCurrencyCodeKeyAndRateToVnd(String currencyCodeKey, BigDecimal rateToVnd);

    Optional<CurrencyMaster> findByCurrencyCodeKeyAndRateToVnd(String currencyCodeKey, BigDecimal rateToVnd);
}
