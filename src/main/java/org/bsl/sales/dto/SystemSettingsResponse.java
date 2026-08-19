package org.bsl.sales.dto;

import java.time.LocalDateTime;

public record SystemSettingsResponse(
        String companyName,
        String timeZone,
        String dateFormat,
        String numberFormat,
        Integer decimalPlaces,
        String defaultLanguage,
        String layoutColor,
        boolean logoAvailable,
        String logoFileName,
        String logoContentType,
        String logoUrl,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
