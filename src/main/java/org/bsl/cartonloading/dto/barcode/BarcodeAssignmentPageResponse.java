package org.bsl.cartonloading.dto.barcode;

import org.bsl.cartonloading.model.CartonScanTransaction;

import java.util.List;

/**
 * Server-side paginated response for the Factory Barcode Assignment screen.
 *
 * totalElements/totalPages describe the current search + assignment filter,
 * while totalCartons/assignedCount/unassignedCount always describe the whole Order.
 */
public record BarcodeAssignmentPageResponse(
        List<CartonScanTransaction> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        long totalCartons,
        long assignedCount,
        long unassignedCount
) { }
