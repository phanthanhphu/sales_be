package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Document(collection = "mat_info")
@CompoundIndex(name = "uk_mat_info_buyer_identity", def = "{'buyerKey': 1, 'checkingKey': 1}", unique = true)
public class MatInfo {

    @Id
    private String id;

    /*
     * Internal composite key only. The field name is retained for MongoDB
     * compatibility with older MAT_INFO documents, but Checking is no longer
     * exposed or entered by users.
     */
    @JsonIgnore
    @Indexed
    private String checkingKey;

    @JsonIgnore
    @Indexed
    private String materialTypeKey;

    @JsonIgnore
    @Indexed
    private String shortNameSupplierKey;

    @Indexed(unique = true, sparse = true)
    private String masterKey;

    @Indexed
    private String buyerKey;

    private String flexId;
    private String materialType;
    private String matFullDescription;
    private String matColor;
    private String matUnit;
    /** Exact Currency Master rate row selected when this MAT_INFO row was saved. */
    private String currencyMasterId;
    private String currency;
    private BigDecimal matPriceWithoutTax;
    private String shortNameSupplier;
    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate updatedDate;

    private String updatedPic;
    private String styleDesc;
    private Boolean active = true;
    private LocalDateTime deletedAt;
    private String deletedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCheckingKey() {
        return checkingKey;
    }

    public void setCheckingKey(String checkingKey) {
        this.checkingKey = checkingKey;
    }

    public String getMaterialTypeKey() {
        return materialTypeKey;
    }

    public void setMaterialTypeKey(String materialTypeKey) {
        this.materialTypeKey = materialTypeKey;
    }

    public String getShortNameSupplierKey() {
        return shortNameSupplierKey;
    }

    public void setShortNameSupplierKey(String shortNameSupplierKey) {
        this.shortNameSupplierKey = shortNameSupplierKey;
    }


    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

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

    public String getCurrencyMasterId() {
        return currencyMasterId;
    }

    public void setCurrencyMasterId(String currencyMasterId) {
        this.currencyMasterId = currencyMasterId;
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

    public boolean isActive() { return !Boolean.FALSE.equals(active); }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
