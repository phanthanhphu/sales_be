package org.bsl.cartonloading.dto.carton;

import java.math.BigDecimal;

public record ScaleStationRequest(
        String stationCode,
        String stationName,
        String plcIp,
        String gatewayIp,
        String location,
        Boolean active,
        BigDecimal minimumWeightKg,
        BigDecimal stabilityToleranceKg
) {
}
