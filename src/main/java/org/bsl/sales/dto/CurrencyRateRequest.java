package org.bsl.sales.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Adds one new historical rate. Existing history is never overwritten by this endpoint. */
public class CurrencyRateRequest {

    @NotNull(message = "Rate to VND is required")
    @DecimalMin(value = "0.00000001", inclusive = true, message = "Rate to VND must be greater than 0")
    private BigDecimal rateToVnd;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime effectiveAt;

    @Size(max = 1000, message = "Remark must not exceed 1000 characters")
    private String remark;

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
}
