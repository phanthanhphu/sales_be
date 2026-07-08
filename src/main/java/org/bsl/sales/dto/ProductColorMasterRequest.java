package org.bsl.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProductColorMasterRequest(
        @Size(max = 100, message = "Buyer must not exceed 100 characters") String buyer,
        @NotBlank(message = "Season is required") @Size(max = 50, message = "Season must not exceed 50 characters") String season,
        @NotBlank(message = "Pattern Number is required") @Size(max = 100, message = "Pattern Number must not exceed 100 characters") String patternNumber,
        @Size(max = 100, message = "Style Number must not exceed 100 characters") String styleNumber,
        @Size(max = 200, message = "Style Name must not exceed 200 characters") String styleName,
        @NotBlank(message = "Product Color is required") @Size(max = 100, message = "Product Color must not exceed 100 characters") String productColor,
        Boolean active,
        List<@Valid ProductColorAttributeRequest> childColors
) { }
