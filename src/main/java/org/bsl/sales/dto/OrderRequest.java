package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record OrderRequest(
        @Size(max = 40, message = "Buyer Key must not exceed 40 characters")
        String buyerKey,
        @NotBlank(message = "Order Name is required")
        @Size(max = 200, message = "Order Name must not exceed 200 characters")
        String orderName,
        @NotNull(message = "End Date is required")
        LocalDate endDate
) { }
