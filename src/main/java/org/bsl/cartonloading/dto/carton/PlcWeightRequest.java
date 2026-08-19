package org.bsl.cartonloading.dto.carton;

import java.math.BigDecimal;

public record PlcWeightRequest(
        String stationCode,
        Long jobId,
        BigDecimal weightKg,
        Boolean stable,
        String messageId
) {
}
