package org.bsl.cartonloading.dto.carton;

public record CartonPlanScanRequest(
        String stationCode,
        String barcode,
        String palletCode
) {
}
