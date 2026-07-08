package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Document(collection = "loss_master")
public class Loss {

    @Id
    private String id;

    @JsonIgnore
    @Indexed(unique = true)
    private String materialGroupKey;

    @Indexed(unique = true, sparse = true)
    private String masterKey;

    private String materialGroup;
    private BigDecimal lossLt501;
    private BigDecimal lossLt1501;
    private BigDecimal lossLt3001;
    private BigDecimal lossGte3001;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public BigDecimal getFactorLt501() {
        return factor(lossLt501);
    }

    public BigDecimal getFactorLt1501() {
        return factor(lossLt1501);
    }

    public BigDecimal getFactorLt3001() {
        return factor(lossLt3001);
    }

    public BigDecimal getFactorGte3001() {
        return factor(lossGte3001);
    }

    private BigDecimal factor(BigDecimal loss) {
        return loss == null ? null : BigDecimal.ONE.add(loss);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMaterialGroupKey() {
        return materialGroupKey;
    }

    public void setMaterialGroupKey(String materialGroupKey) {
        this.materialGroupKey = materialGroupKey;
    }


    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public String getMaterialGroup() {
        return materialGroup;
    }

    public void setMaterialGroup(String materialGroup) {
        this.materialGroup = materialGroup;
    }

    public BigDecimal getLossLt501() {
        return lossLt501;
    }

    public void setLossLt501(BigDecimal lossLt501) {
        this.lossLt501 = lossLt501;
    }

    public BigDecimal getLossLt1501() {
        return lossLt1501;
    }

    public void setLossLt1501(BigDecimal lossLt1501) {
        this.lossLt1501 = lossLt1501;
    }

    public BigDecimal getLossLt3001() {
        return lossLt3001;
    }

    public void setLossLt3001(BigDecimal lossLt3001) {
        this.lossLt3001 = lossLt3001;
    }

    public BigDecimal getLossGte3001() {
        return lossGte3001;
    }

    public void setLossGte3001(BigDecimal lossGte3001) {
        this.lossGte3001 = lossGte3001;
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
}
