package org.bsl.cartonloading.dto.carton;

import java.util.List;

public record CartonItemLookupResponse(
        boolean matched,
        String barcode,
        String normalizedBarcode,
        String message,
        List<CartonMasterItemResponse> items
) {
}
