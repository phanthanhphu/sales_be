package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One immutable-rate Currency Master row.
 *
 * Multiple rows with the same Currency Code are allowed only when Rate To VND
 * is different. The newest row is used by MAT_INFO and MPR. Once a row is
 * referenced by MAT_INFO or an MPR snapshot it is locked from edit/delete.
 */
@Document(collection = "currency_master")
public class CurrencyMaster {

    @Id
    private String id;

    @JsonIgnore
    private String currencyCodeKey;

    private String currencyCode;
    private String currencyName;
    /** 1 unit of Currency Code equals Rate To VND Vietnamese Dong. */
    private BigDecimal rateToVnd;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /** Returned only for UI action locking; never stored in MongoDB. */
    @Transient
    private boolean locked;

    @Transient
    private String lockMessage;

    /* Legacy fields retained only so old Mongo documents can still load. */
    @JsonIgnore private BigDecimal exchangeRateToUsd;
    @JsonIgnore private BigDecimal currentRateToVnd;
    @JsonIgnore private LocalDateTime currentRateEffectiveAt;
    @JsonIgnore private String currentRateId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCurrencyCodeKey() { return currencyCodeKey; }
    public void setCurrencyCodeKey(String currencyCodeKey) { this.currencyCodeKey = currencyCodeKey; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public String getCurrencyName() { return currencyName; }
    public void setCurrencyName(String currencyName) { this.currencyName = currencyName; }
    public BigDecimal getRateToVnd() { return rateToVnd; }
    public void setRateToVnd(BigDecimal rateToVnd) { this.rateToVnd = rateToVnd; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public String getLockMessage() { return lockMessage; }
    public void setLockMessage(String lockMessage) { this.lockMessage = lockMessage; }
    public BigDecimal getExchangeRateToUsd() { return exchangeRateToUsd; }
    public void setExchangeRateToUsd(BigDecimal exchangeRateToUsd) { this.exchangeRateToUsd = exchangeRateToUsd; }
    public BigDecimal getCurrentRateToVnd() { return currentRateToVnd; }
    public void setCurrentRateToVnd(BigDecimal currentRateToVnd) { this.currentRateToVnd = currentRateToVnd; }
    public LocalDateTime getCurrentRateEffectiveAt() { return currentRateEffectiveAt; }
    public void setCurrentRateEffectiveAt(LocalDateTime currentRateEffectiveAt) { this.currentRateEffectiveAt = currentRateEffectiveAt; }
    public String getCurrentRateId() { return currentRateId; }
    public void setCurrentRateId(String currentRateId) { this.currentRateId = currentRateId; }
}
