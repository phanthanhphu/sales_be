package org.bsl.cartonloading.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PackingOrderResponse(
        String id,
        String buyerCode,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate orderDate,
        String orderName,
        String supplierName,
        String supplierNumber,
        String productionFacility,
        long masterLineCount,
        long packingLineCount,
        String status,
        boolean completed,
        long plannedCartonCount,
        long completedCartonCount,
        String createdBy,
        String updatedBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {
}
