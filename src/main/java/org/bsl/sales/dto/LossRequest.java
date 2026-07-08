package org.bsl.sales.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class LossRequest {

    @NotBlank(message = "Material group is required")
    @Size(max = 100, message = "Material group must not exceed 100 characters")
    private String materialGroup;

    @NotNull(message = "Loss % <501 is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Loss % <501 must be between 0 and 1")
    @DecimalMax(value = "1.0", inclusive = true, message = "Loss % <501 must be between 0 and 1")
    private BigDecimal lossLt501;

    @NotNull(message = "Loss % <1501 is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Loss % <1501 must be between 0 and 1")
    @DecimalMax(value = "1.0", inclusive = true, message = "Loss % <1501 must be between 0 and 1")
    private BigDecimal lossLt1501;

    @NotNull(message = "Loss % <3001 is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Loss % <3001 must be between 0 and 1")
    @DecimalMax(value = "1.0", inclusive = true, message = "Loss % <3001 must be between 0 and 1")
    private BigDecimal lossLt3001;

    @NotNull(message = "Loss % >=3001 is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Loss % >=3001 must be between 0 and 1")
    @DecimalMax(value = "1.0", inclusive = true, message = "Loss % >=3001 must be between 0 and 1")
    private BigDecimal lossGte3001;

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
}
