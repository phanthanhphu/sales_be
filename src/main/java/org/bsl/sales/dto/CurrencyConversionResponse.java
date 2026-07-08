package org.bsl.sales.dto;

import java.math.BigDecimal;

/** Response for the pricing formula: amount × current/as-of rate = VND amount. */
public class CurrencyConversionResponse extends CurrencyRateResolutionResponse {

    private BigDecimal amount;
    private BigDecimal amountInVnd;

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getAmountInVnd() {
        return amountInVnd;
    }

    public void setAmountInVnd(BigDecimal amountInVnd) {
        this.amountInVnd = amountInVnd;
    }
}
