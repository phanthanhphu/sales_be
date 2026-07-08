package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipToRequest(
        @NotBlank(message = "Ship To name is required")
        @Size(max = 200, message = "Ship To name must not exceed 200 characters")
        String shipToName,
        @Size(max = 100, message = "Ship To code must not exceed 100 characters")
        String shipToCode,
        Boolean active,
        @Size(max = 1000, message = "Remark must not exceed 1000 characters")
        String remark
) { }
