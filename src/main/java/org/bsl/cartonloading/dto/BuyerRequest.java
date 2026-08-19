package org.bsl.cartonloading.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BuyerRequest(
        @NotBlank @Size(max = 60) String buyerKey,
        @NotBlank @Size(max = 160) String buyerName,
        Boolean active,
        Integer sequence,
        @Size(max = 1000) String description
) {
}
