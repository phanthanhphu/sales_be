package org.bsl.cartonloading.dto.barcode;

public record FactoryBarcodeGenerateRequest(
        Integer year,
        String factoryCode,
        Integer quantity
) { }
