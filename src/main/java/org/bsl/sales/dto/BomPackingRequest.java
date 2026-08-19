package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Packing-level Product Color applicability is deprecated.
 * applicableProductColorIds/applicableColors remain only for backward API compatibility and are ignored by the service.
 * Product Color assignment is managed per BOM Line through Product Color Values.
 */
public record BomPackingRequest(
        @NotBlank(message = "Packing name is required")
        @Size(max = 200, message = "Packing name must not exceed 200 characters")
        String packingName,
        Integer sequence,
        List<String> applicableProductColorIds,
        List<String> applicableColors
) { }
