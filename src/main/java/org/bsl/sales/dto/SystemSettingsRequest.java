package org.bsl.sales.dto;

public record SystemSettingsRequest(
        String companyName,
        String timeZone,
        String dateFormat,
        String numberFormat,
        Integer decimalPlaces,
        String defaultLanguage
) {
}
