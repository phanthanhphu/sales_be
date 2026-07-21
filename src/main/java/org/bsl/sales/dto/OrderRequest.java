package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderRequest(
        @Size(max = 40, message = "Buyer Key must not exceed 40 characters")
        String buyerKey,
        @NotBlank(message = "Order No is required")
        @Size(max = 100, message = "Order No must not exceed 100 characters")
        String orderNo,
        @NotBlank(message = "Style is required")
        @Size(max = 160, message = "Style must not exceed 160 characters")
        String style,
        @NotBlank(message = "Customer is required")
        @Size(max = 160, message = "Customer must not exceed 160 characters")
        String customer,
        @NotBlank(message = "Season is required")
        @Size(max = 80, message = "Season must not exceed 80 characters")
        String season,
        @Size(max = 2000, message = "Comment must not exceed 2000 characters")
        String comment
) { }
