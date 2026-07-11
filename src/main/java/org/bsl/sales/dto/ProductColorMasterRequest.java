package org.bsl.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProductColorMasterRequest(
        @NotBlank(message = "Product Color is required")
        @Size(max = 100, message = "Product Color must not exceed 100 characters")
        String productColor,
        Boolean active,
        List<@Valid ProductColorAttributeRequest> childColors
) { }
