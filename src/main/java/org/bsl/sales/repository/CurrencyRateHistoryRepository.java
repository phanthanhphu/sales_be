package org.bsl.sales.repository;

import org.bsl.sales.model.CurrencyRateHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CurrencyRateHistoryRepository extends MongoRepository<CurrencyRateHistory, String> {

    Optional<CurrencyRateHistory> findFirstByCurrencyCodeKeyOrderByEffectiveAtDescCreatedAtDesc(String currencyCodeKey);

    List<CurrencyRateHistory> findByCurrencyIdOrderByEffectiveAtDescCreatedAtDesc(String currencyId);

    Optional<CurrencyRateHistory> findByCurrencyCodeKeyAndEffectiveAt(String currencyCodeKey, LocalDateTime effectiveAt);

    void deleteByCurrencyId(String currencyId);
}
