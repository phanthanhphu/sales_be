package org.bsl.cartonloading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PackingListLineRequest(
        @DecimalMin(value = "0", inclusive = true) BigDecimal cartonFrom,
        @DecimalMin(value = "0", inclusive = true) BigDecimal cartonTo,
        @DecimalMin(value = "0", inclusive = true) BigDecimal cartonsQty,
        @Size(max = 80) String poNumber,
        @Size(max = 80) String styleNumber,
        @Size(max = 500) String style,
        @Size(max = 80) String articleNumber,
        @Size(max = 160) String color,
        @Size(max = 80) String size,
        @DecimalMin(value = "0", inclusive = true) BigDecimal qtyPerCarton,
        @DecimalMin(value = "0", inclusive = true) BigDecimal totalPcs,
        @Size(max = 100) String cartonMeasurement,
        @DecimalMin(value = "0", inclusive = true) BigDecimal cbm,
        @DecimalMin(value = "0", inclusive = true) BigDecimal grossWeightKg,
        @DecimalMin(value = "0", inclusive = true) BigDecimal netWeightKg,
        @DecimalMin(value = "0", inclusive = true) BigDecimal actualWeightKg,
        @Size(max = 1000) String remarks
) {
}
