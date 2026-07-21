package org.bsl.sales.support;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AuditActionResolver {
    public static final String ADD = "ADD";
    public static final String EDIT = "EDIT";
    public static final String DELETE = "DELETE";

    private static final Set<String> SUPPORTED_ACTIONS = Set.of(ADD, EDIT, DELETE);

    private static final List<String> ACTION_SEGMENTS = List.of(
            "upload", "upload-edited", "replace-excel", "export", "export-edit", "download",
            "preview", "generate", "submit", "apply", "recheck", "resolve", "search",
            "login", "logout", "reset-password", "change-password", "image", "attachments",
            "active", "current", "check-session"
    );

    private AuditActionResolver() {}

    public static String resolveModule(String uri) {
        String path = lower(uri);
        if (path.contains("/mpr")) return "MPR";
        if (path.contains("/boms") || path.contains("/bom-")) return "BOM";
        if (path.contains("/orders")) return "ORDER";
        if (path.contains("/master-data/currencies")) return "CURRENCY";
        if (path.contains("/master-data/vendor-codes") || path.contains("/suppliers")) return "VENDOR_CODE";
        if (path.contains("/master-data/mat-infos")) return "MAT_INFO";
        if (path.contains("/master-data/loss")) return "LOSS";
        if (path.contains("/master-data/ship-tos")) return "SHIP_TO";
        if (path.contains("/master-data/product-colors")) return "PRODUCT_COLOR";
        if (path.contains("/users")) return "USER";
        if (path.contains("/departments")) return "DEPARTMENT";
        if (path.contains("/buyers")) return "BUYER";
        if (path.contains("/audit-logs")) return "AUDIT_LOG";
        if (path.contains("/auth")) return "AUTHENTICATION";
        if (path.contains("/file")) return "FILE";
        return "SYSTEM";
    }

    /**
     * Only data-changing actions are audited. Read-only actions such as VIEW, SEARCH,
     * LOGIN, EXPORT, DOWNLOAD and PREVIEW return null and are not persisted.
     */
    public static String resolveAction(String method, String uri, String queryString) {
        String verb = upper(method);
        String path = lower(uri);

        if (isExcludedPath(path)) return null;

        if (DELETE.equals(verb)) return DELETE;
        if ("PUT".equals(verb) || "PATCH".equals(verb)) return EDIT;

        if (!"POST".equals(verb)) return null;

        if (path.contains("upload-edited")
                || path.contains("replace-excel")
                || path.contains("change-password")
                || path.contains("reset-password")
                || path.endsWith("/submit")
                || path.endsWith("/apply")
                || path.endsWith("/recheck")
                || path.endsWith("/resolve")
                || path.endsWith("/image")) {
            return EDIT;
        }

        // POST creates a new record, child row, attachment, imported dataset or MPR.
        return ADD;
    }

    public static boolean isSupportedAction(String action) {
        return action != null && SUPPORTED_ACTIONS.contains(action.toUpperCase(Locale.ROOT));
    }

    private static boolean isExcludedPath(String path) {
        if (path.isBlank()) return true;
        return path.contains("/audit-logs")
                || path.endsWith("/login")
                || path.endsWith("/logout")
                || path.contains("/check-session")
                || path.endsWith("/current")
                || path.endsWith("/active")
                || path.contains("/preview")
                || path.contains("/export")
                || path.contains("/download")
                || path.contains("/search");
    }

    public static String resolveResourceType(String uri) {
        return resolveModule(uri);
    }

    public static String resolveResourceId(String uri) {
        if (uri == null || uri.isBlank()) return null;
        String[] rawSegments = uri.split("/");
        List<String> segments = Arrays.stream(rawSegments).filter(value -> !value.isBlank()).toList();
        for (int index = segments.size() - 1; index >= 0; index--) {
            String value = segments.get(index);
            String normalized = lower(value);
            if (normalized.equals("api") || ACTION_SEGMENTS.contains(normalized)) continue;
            if (normalized.matches("users|departments|buyers|orders|boms|mpr|lines|batches|packings|product-colors|attachments|master-data|currencies|vendor-codes|mat-infos|loss|ship-tos|audit-logs|auth")) continue;
            return value.length() > 120 ? value.substring(0, 120) : value;
        }
        return null;
    }

    public static String description(String action, String module, String resourceId, boolean success) {
        StringBuilder text = new StringBuilder();
        text.append(action == null ? "ACTION" : action);
        text.append(" on ").append(module == null ? "SYSTEM" : module.replace('_', ' '));
        if (resourceId != null && !resourceId.isBlank()) text.append(" [").append(resourceId).append(']');
        text.append(success ? " completed successfully" : " failed");
        return text.toString();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
