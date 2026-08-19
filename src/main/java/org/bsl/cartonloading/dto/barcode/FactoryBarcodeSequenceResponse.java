package org.bsl.cartonloading.dto.barcode;

public record FactoryBarcodeSequenceResponse(
        Integer year,
        String factoryCode,
        Long nextRunningNumber,
        String nextBarcode
) { }
