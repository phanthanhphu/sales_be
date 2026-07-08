package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record MprSelectionRequest(
        @NotBlank(message = "BOM id is required") String bomId,
        List<String> colors,
        List<String> packingIds,
        Map<String, BigDecimal> poQtyByColor,
        /** One or more Ship To master ids for each selected Product Color. */
        Map<String, List<String>> shipToIdsByColor
) { }
