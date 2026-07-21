package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "vendor_codes")
public class VendorCode {

    @Id
    private String id;

    @JsonIgnore
    @Indexed(unique = true)
    private String shortNameSupplierKey;

    @Indexed(unique = true, sparse = true)
    private String masterKey;

    private String shortNameSupplier;
    private String vendorCode;
    private String vendorName;
    private String matCharger;
    private String remark;

    /** True when the vendor was created with only a supplier name and still needs master-data completion. */
    private boolean pendingCompletion;

    /** Identifies vendors created automatically while importing or saving MAT_INFO. */
    private boolean autoCreatedFromMatInfo;

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

    public boolean isPendingCompletion() {
        return pendingCompletion;
    }

    public void setPendingCompletion(boolean pendingCompletion) {
        this.pendingCompletion = pendingCompletion;
    }

    public boolean isAutoCreatedFromMatInfo() {
        return autoCreatedFromMatInfo;
    }

    public void setAutoCreatedFromMatInfo(boolean autoCreatedFromMatInfo) {
        this.autoCreatedFromMatInfo = autoCreatedFromMatInfo;
    }

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
