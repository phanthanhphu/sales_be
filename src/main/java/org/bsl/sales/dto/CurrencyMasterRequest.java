package org.bsl.sales.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Currency master input. The only business values are code, name and the
 * current conversion to VND. Old rate values are kept by BE internally.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrencyMasterRequest {

    @NotBlank(message = "Currency code is required")
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency code must contain exactly 3 letters")
    private String currencyCode;

    @NotBlank(message = "Currency name is required")
    @Size(max = 100, message = "Currency name must not exceed 100 characters")
    private String currencyName;

    /** 1 unit of this currency equals this many VND. VND itself is always 1. */
    @NotNull(message = "Rate To VND is required")
    @DecimalMin(value = "0.00000001", inclusive = true, message = "Rate To VND must be greater than 0")
    @JsonAlias({"currentRateToVnd", "initialRateToVnd", "exchangeRateToVnd"})
    private BigDecimal rateToVnd;

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

    public BigDecimal getRateToVnd() {
        return rateToVnd;
    }

    public void setRateToVnd(BigDecimal rateToVnd) {
        this.rateToVnd = rateToVnd;
    }
}
