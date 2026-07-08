package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class VendorCodeRequest {

    @NotBlank(message = "Short name supplier is required")
    @Size(max = 100, message = "Short name supplier must not exceed 100 characters")
    private String shortNameSupplier;

    @Size(max = 100, message = "Vendor code must not exceed 100 characters")
    private String vendorCode;

    @Size(max = 200, message = "Vendor name must not exceed 200 characters")
    private String vendorName;

    @Size(max = 100, message = "MAT CHARGER must not exceed 100 characters")
    private String matCharger;

    @Size(max = 1000, message = "Remark must not exceed 1000 characters")
    private String remark;

    public String getShortNameSupplier() {
        return shortNameSupplier;
    }

    public void setShortNameSupplier(String shortNameSupplier) {
        this.shortNameSupplier = shortNameSupplier;
    }

    public String getVendorCode() {
        return vendorCode;
    }

    public void setVendorCode(String vendorCode) {
        this.vendorCode = vendorCode;
    }

    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    public String getMatCharger() {
        return matCharger;
    }

    public void setMatCharger(String matCharger) {
        this.matCharger = matCharger;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
