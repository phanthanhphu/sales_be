package org.bsl.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.bsl.sales.model.BomHeader;

public record BomCreateRequest(
        @NotBlank(message = "BOM No is required")
        @Size(max = 100, message = "BOM No must not exceed 100 characters")
        String bomNo,
        @NotBlank(message = "BOM Name is required")
        @Size(max = 200, message = "BOM Name must not exceed 200 characters")
        String bomName,
        @Valid BomHeader header
) { }
