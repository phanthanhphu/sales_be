package org.bsl.sales.dto;

import java.math.BigDecimal;

public class LossResolutionResponse {

    private String materialType;
    private String materialGroup;
    private BigDecimal totalQuantity;
    private String tier;
    private BigDecimal lossRate;
    private BigDecimal factor;

    public String getMaterialType() {
        return materialType;
    }

    public void setMaterialType(String materialType) {
        this.materialType = materialType;
    }

    public String getMaterialGroup() {
        return materialGroup;
    }

    public void setMaterialGroup(String materialGroup) {
        this.materialGroup = materialGroup;
    }

    public BigDecimal getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(BigDecimal totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public BigDecimal getLossRate() {
        return lossRate;
    }

    public void setLossRate(BigDecimal lossRate) {
        this.lossRate = lossRate;
    }

    public BigDecimal getFactor() {
        return factor;
    }

    public void setFactor(BigDecimal factor) {
        this.factor = factor;
    }
}
