package org.bsl.cartonloading.dto.carton;

import java.math.BigDecimal;

public record CartonMasterItemResponse(
        String masterLineId,
        Integer lineNo,
        String supplierNumber,
        String poNumber,
        String articleNumber,
        String styleNumber,
        String style,
        String color,
        String size,
        BigDecimal qtyPerCarton,
        int plannedCartons,
        long notScannedCartons,
        long waitingCartons,
        long completedCartons,
        BigDecimal totalWeightKg,
        String firstItemKey,
        String lastItemKey
) {
}
