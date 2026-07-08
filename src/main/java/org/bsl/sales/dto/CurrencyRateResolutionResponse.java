package org.bsl.sales.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Current/as-of rate resolution used by MAT_INFO, MPR and BOM costing. */
public class CurrencyRateResolutionResponse {

    private String currencyId;
    private String currencyCode;
    private String currencyName;
    private String rateId;
    private BigDecimal rateToVnd;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime effectiveAt;

    public String getCurrencyId() {
        return currencyId;
    }

    public void setCurrencyId(String currencyId) {
        this.currencyId = currencyId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getCurrencyName() {
        return currencyName;
    }

    public void setCurrencyName(String currencyName) {
        this.currencyName = currencyName;
    }

    public String getRateId() {
        return rateId;
    }

    public void setRateId(String rateId) {
        this.rateId = rateId;
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
}
