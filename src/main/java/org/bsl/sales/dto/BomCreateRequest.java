package org.bsl.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.bsl.sales.model.BomHeader;

public record BomCreateRequest(
        // Retained for backward-compatible JSON. The service always generates
        // and preserves BOM No; any client-provided value is ignored.
        String bomNo,
        @NotBlank(message = "BOM Name is required")
        @Size(max = 200, message = "BOM Name must not exceed 200 characters")
        String bomName,
        @Valid BomHeader header
) { }
