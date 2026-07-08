package org.bsl.sales.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Internal audit storage. This object is intentionally NOT returned by the
 * normal Currency Master APIs. FE reads CurrencyMaster.rateToVnd only.
 */
@Document(collection = "currency_rate_history")
@CompoundIndex(
        name = "currency_rate_effective_unique",
        def = "{'currencyCodeKey': 1, 'effectiveAt': 1}",
        unique = true
)
public class CurrencyRateHistory {

    @Id
    private String id;

    @Indexed
    private String currencyId;

    @Indexed
    private String currencyCodeKey;

    private String currencyCode;
    private BigDecimal rateToVnd;

    /** Automatically set by BE whenever the current rate changes. */
    private LocalDateTime effectiveAt;

    private String remark;
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCurrencyId() {
        return currencyId;
    }

    public void setCurrencyId(String currencyId) {
        this.currencyId = currencyId;
    }

    public String getCurrencyCodeKey() {
        return currencyCodeKey;
    }

    public void setCurrencyCodeKey(String currencyCodeKey) {
        this.currencyCodeKey = currencyCodeKey;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getRateToVnd() {
        return rateToVnd;
    }

    public void setRateToVnd(BigDecimal rateToVnd) {
        this.rateToVnd = rateToVnd;
    }

    public LocalDateTime getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(LocalDateTime effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
