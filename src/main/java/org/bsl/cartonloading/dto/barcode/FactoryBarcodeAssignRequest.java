package org.bsl.cartonloading.dto.barcode;

public record FactoryBarcodeAssignRequest(
        String factoryBarcode,
        String cartonId
) { }
