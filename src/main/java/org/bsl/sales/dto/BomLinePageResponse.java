package org.bsl.sales.dto;

import org.bsl.sales.model.BomLine;

import java.util.List;

/**
 * Paginated response used by the BOM detail screen when loading Core/Packing
 * material lines incrementally.
 */
public record BomLinePageResponse(
        List<BomLine> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public BomLinePageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
