package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MprReopenRequest(
        @NotBlank(message = "Reopen reason is required")
        @Size(max = 1000, message = "Reopen reason must not exceed 1000 characters")
        String reason
) { }
