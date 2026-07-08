package org.bsl.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

public record MprGenerateRequest(
        String mprNo,
        @PositiveOrZero(message = "PO quantity must be zero or greater") BigDecimal poQuantity,
        @PositiveOrZero(message = "Sample quantity must be zero or greater") BigDecimal sampleQuantity,
        @NotEmpty(message = "Select at least one submitted BOM") List<@Valid MprSelectionRequest> selections
) { }
