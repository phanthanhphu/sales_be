package org.bsl.sales.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * MAT_INFO input matching the current MAT_INFO workbook.
 * Checking, DEV and Additional Note are no longer part of the business form.
 */
public class MatInfoRequest {

    @Size(max = 40, message = "Buyer Key must not exceed 40 characters")
    private String buyerKey;

    @Size(max = 100, message = "Flex ID must not exceed 100 characters")
    private String flexId;

    @NotBlank(message = "Material type is required")
    @Size(max = 100, message = "Material type must not exceed 100 characters")
    private String materialType;

    @NotBlank(message = "MAT FULL DESCRIPTION is required")
    @Size(max = 1500, message = "MAT FULL DESCRIPTION must not exceed 1500 characters")
    private String matFullDescription;

    @NotBlank(message = "MAT COLOR is required")
    @Size(max = 500, message = "MAT COLOR must not exceed 500 characters")
    private String matColor;

    @NotBlank(message = "MAT UNIT is required")
    @Pattern(regexp = "(?i)^[A-Z0-9._/\\-]{1,20}$", message = "MAT UNIT contains invalid characters")
    private String matUnit;

    /** Any three-letter code is accepted here; Currency Master existence is checked by the service. */
    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "(?i)^[A-Z]{3}$", message = "Currency must be a 3-letter code")
    private String currency;

    @DecimalMin(value = "0.0", inclusive = true, message = "MAT PRICE (W/O TAX) must not be negative")
    private BigDecimal matPriceWithoutTax;

    @NotBlank(message = "Short name supplier is required")
    @Size(max = 100, message = "Short name supplier must not exceed 100 characters")
    private String shortNameSupplier;

    @Size(max = 2000, message = "Remark must not exceed 2000 characters")
    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate updatedDate;

    @NotBlank(message = "Updated PIC is required")
    @Size(max = 100, message = "Updated PIC must not exceed 100 characters")
    private String updatedPic;

    @Size(max = 500, message = "Style Desc must not exceed 500 characters")
    private String styleDesc;

    public String getBuyerKey() { return buyerKey; }
    public void setBuyerKey(String buyerKey) { this.buyerKey = buyerKey; }

    public String getFlexId() {
        return flexId;
    }

    public void setFlexId(String flexId) {
        this.flexId = flexId;
    }

    public String getMaterialType() {
        return materialType;
    }

    public void setMaterialType(String materialType) {
        this.materialType = materialType;
    }

    public String getMatFullDescription() {
        return matFullDescription;
    }

    public void setMatFullDescription(String matFullDescription) {
        this.matFullDescription = matFullDescription;
    }

    public String getMatColor() {
        return matColor;
    }

    public void setMatColor(String matColor) {
        this.matColor = matColor;
    }

    public String getMatUnit() {
        return matUnit;
    }

    public void setMatUnit(String matUnit) {
        this.matUnit = matUnit;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getMatPriceWithoutTax() {
        return matPriceWithoutTax;
    }

    public void setMatPriceWithoutTax(BigDecimal matPriceWithoutTax) {
        this.matPriceWithoutTax = matPriceWithoutTax;
    }

    public String getShortNameSupplier() {
        return shortNameSupplier;
    }

    public void setShortNameSupplier(String shortNameSupplier) {
        this.shortNameSupplier = shortNameSupplier;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDate getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(LocalDate updatedDate) {
        this.updatedDate = updatedDate;
    }

    public String getUpdatedPic() {
        return updatedPic;
    }

    public void setUpdatedPic(String updatedPic) {
        this.updatedPic = updatedPic;
    }

    public String getStyleDesc() {
        return styleDesc;
    }

    public void setStyleDesc(String styleDesc) {
        this.styleDesc = styleDesc;
    }
}
