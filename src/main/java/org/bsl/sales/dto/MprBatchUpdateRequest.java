package org.bsl.sales.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Updates one saved MPR generation batch.
 *
 * Colors and Packing can be changed after MPR creation. PO Qty and Ship To are
 * supplied per selected Product Color. A null colors/packingIds value keeps the
 * current saved selection for backwards-compatible clients.
 */
public record MprBatchUpdateRequest(
        List<String> colors,
        List<String> packingIds,
        Map<String, BigDecimal> poQtyByColor,
        Map<String, List<String>> shipToIdsByColor,
        Map<String, Map<String, BigDecimal>> shipToQtyByColor
) { }
