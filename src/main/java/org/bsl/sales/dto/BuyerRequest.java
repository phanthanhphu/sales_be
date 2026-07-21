package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BuyerRequest(
        @NotBlank(message = "Buyer Key is required")
        @Pattern(regexp = "^[A-Za-z0-9_-]{2,40}$", message = "Buyer Key may contain only letters, numbers, underscore and hyphen")
        String buyerKey,
        @NotBlank(message = "Buyer Name is required")
        @Size(max = 120, message = "Buyer Name must not exceed 120 characters")
        String buyerName,
        Boolean active,
        Integer sequence,
        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description
) { }
