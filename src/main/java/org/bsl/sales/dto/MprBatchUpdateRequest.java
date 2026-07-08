package org.bsl.sales.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Update every MPR line in one saved generation batch by Product Color. */
public record MprBatchUpdateRequest(
        Map<String, BigDecimal> poQtyByColor,
        Map<String, List<String>> shipToIdsByColor
) { }
