package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Updates only currency metadata. A new exchange rate must use /rates. */
public class CurrencyMasterUpdateRequest {

    @NotBlank(message = "Currency name is required")
    @Size(max = 100, message = "Currency name must not exceed 100 characters")
    private String currencyName;

    private Boolean active = Boolean.TRUE;

    @Size(max = 1000, message = "Remark must not exceed 1000 characters")
    private String remark;

    public String getCurrencyName() {
        return currencyName;
    }

    public void setCurrencyName(String currencyName) {
        this.currencyName = currencyName;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
