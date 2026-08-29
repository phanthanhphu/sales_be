package org.bsl.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** One Product Color maintained directly inside one BOM. */
public record BomProductColorRequest(
        @NotBlank(message = "Product Color is required")
        @Size(max = 100, message = "Product Color must not exceed 100 characters")
        String colorName,
        @NotBlank(message = "Pattern Number is required")
        @Size(max = 100, message = "Pattern Number must not exceed 100 characters")
        String patternNumber,
        @NotBlank(message = "Season is required")
        @Size(max = 50, message = "Season must not exceed 50 characters")
        String season,
        @NotBlank(message = "Style Number is required")
        @Size(max = 100, message = "Style Number must not exceed 100 characters")
        String styleNumber,
        Integer sequence,
        List<@Valid ProductColorAttributeRequest> childColors
) { }
