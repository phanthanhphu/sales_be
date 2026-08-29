package org.bsl.sales.support;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared filename convention for Buyer-scoped Master Manager downloads. */
public final class DownloadFileNames {

    private static final ZoneId DOWNLOAD_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DOWNLOAD_TIMESTAMP = DateTimeFormatter.ofPattern("ddMMyyyy_HHmmss");

    private DownloadFileNames() {
    }

    public static String managerExcel(String buyerKey, String managerName) {
        return fileName(buyerKey, managerName, null);
    }

    public static String managerTemplateExcel(String buyerKey, String managerName) {
        return fileName(buyerKey, managerName, "TEMPLATE");
    }

    private static String fileName(String buyerKey, String managerName, String purpose) {
        StringBuilder filename = new StringBuilder()
                .append(safePart(buyerKey, "BUYER"))
                .append('_')
                .append(safePart(managerName, "MANAGER"));
        if (purpose != null && !purpose.isBlank()) {
            filename.append('_').append(safePart(purpose, "FILE"));
        }
        return filename
                .append('_')
                .append(ZonedDateTime.now(DOWNLOAD_TIME_ZONE).format(DOWNLOAD_TIMESTAMP))
                .append(".xlsx")
                .toString();
    }

    private static String safePart(String value, String fallback) {
        String clean = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return clean.isEmpty() ? fallback : clean;
    }
}
