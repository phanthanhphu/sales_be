package org.bsl.sales.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

public final class MasterDataTextNormalizer {

    private static final Map<String, String> MATERIAL_GROUP_ALIASES = Map.ofEntries(
            Map.entry("PRINT", "PRINTING"),
            Map.entry("PRINTING", "PRINTING"),
            Map.entry("REAL LEATHER", "LEATHER"),
            Map.entry("LEATHER", "LEATHER"),
            Map.entry("HANG TAG", "HANGTAG"),
            Map.entry("HANGTAG", "HANGTAG"),
            Map.entry("ELASTIC BAND", "EBAND"),
            Map.entry("E BAND", "EBAND"),
            Map.entry("EBAND", "EBAND")
    );

    private MasterDataTextNormalizer() {
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String result = value.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");
        return result.isEmpty() ? null : result;
    }

    public static String key(String value) {
        String clean = trimToNull(value);
        return clean == null ? null : clean.toUpperCase(Locale.ROOT);
    }

    public static String materialGroupKey(String value) {
        String normalized = key(value);
        if (normalized == null) {
            return null;
        }
        return MATERIAL_GROUP_ALIASES.getOrDefault(normalized, normalized);
    }

    public static String upper(String value) {
        String clean = trimToNull(value);
        return clean == null ? null : clean.toUpperCase(Locale.ROOT);
    }

    public static BigDecimal normalizeMoney(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static String headerKey(String value) {
        String clean = trimToNull(value);
        return clean == null ? "" : clean.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
