package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * applicableProductColorIds is the source of truth. An empty list means the packing applies to all Product Colors.
 * applicableColors is kept only for backward compatibility with older frontend requests.
 */
public record BomPackingRequest(
        @NotBlank(message = "Packing name is required")
        @Size(max = 200, message = "Packing name must not exceed 200 characters")
        String packingName,
        Integer sequence,
        List<String> applicableProductColorIds,
        List<String> applicableColors
) { }
