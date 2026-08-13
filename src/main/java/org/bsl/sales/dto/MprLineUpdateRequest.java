package org.bsl.sales.dto;

import java.math.BigDecimal;

/** Editable fields for one saved MPR item. Derived values are recalculated by the service. */
public record MprLineUpdateRequest(
        String styleDescription,
        String styleColor,
        String shipTo,
        String salesComment,
        String sapCode,
        Integer bomLineNo,
        String materialType,
        String matFullDescription,
        String matColor,
        String matUnit,
        BigDecimal yield,
        BigDecimal lossFactor,
        BigDecimal poQuantity,
        BigDecimal sampleQuantity,
        BigDecimal mcdStock,
        BigDecimal cmcdStock,
        BigDecimal nonSapStockQuantity,
        String currency,
        BigDecimal matPriceWithoutTax,
        String shortNameSupplier,
        String vendorCode,
        String vendorName,
        String matCharger
) { }
