package org.bsl.cartonloading.dto.barcode;

import org.bsl.cartonloading.model.FactoryBarcode;

import java.util.List;

public record FactoryBarcodeGenerateResponse(
        String batchId,
        Integer year,
        String factoryCode,
        Long firstRunningNumber,
        Long lastRunningNumber,
        Integer quantity,
        List<FactoryBarcode> barcodes
) { }
