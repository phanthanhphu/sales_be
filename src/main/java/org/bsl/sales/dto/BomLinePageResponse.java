package org.bsl.sales.dto;

import org.bsl.sales.model.BomLine;

import java.util.List;

/** Page response used by the BOM table; image bytes are loaded lazily by URL. */
public record BomLinePageResponse(
        List<BomLine> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) { }
