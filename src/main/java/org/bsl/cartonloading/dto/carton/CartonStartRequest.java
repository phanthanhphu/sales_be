package org.bsl.cartonloading.dto.carton;

public record CartonStartRequest(
        String stationCode,
        String barcode,
        String packingLineId,
        String palletCode
) {
}
