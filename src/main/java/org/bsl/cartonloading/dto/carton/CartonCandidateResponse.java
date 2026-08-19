package org.bsl.cartonloading.dto.carton;

import java.math.BigDecimal;

public record CartonCandidateResponse(
        String packingLineId,
        Integer lineNo,
        String poNumber,
        String articleNumber,
        String styleNumber,
        String style,
        String color,
        String size,
        BigDecimal qtyPerCarton,
        Integer plannedCartons,
        long completedCartons,
        long waitingCartons
) {
}
