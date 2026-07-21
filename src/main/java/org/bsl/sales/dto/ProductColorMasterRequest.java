package org.bsl.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProductColorMasterRequest(
        @Size(max = 40, message = "Buyer Key must not exceed 40 characters")
        String buyerKey,
        @Size(max = 100, message = "Pattern Number must not exceed 100 characters")
        String patternNumber,
        @NotBlank(message = "Product Color is required")
        @Size(max = 100, message = "Product Color must not exceed 100 characters")
        String productColor,
        @Size(max = 50, message = "Season must not exceed 50 characters")
        String season,
        @Size(max = 100, message = "Style Number must not exceed 100 characters")
        String styleNumber,
        Boolean active,
        List<@Valid ProductColorAttributeRequest> childColors
) { }
