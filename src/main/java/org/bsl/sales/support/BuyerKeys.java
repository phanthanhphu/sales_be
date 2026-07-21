package org.bsl.sales.support;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BuyerKeys {
    public static final String LL_BEAN = "LLBEAN";
    public static final String TNF = "TNF";
    public static final String PATAGONIA = "PATAGONIA";
    public static final String LULULEMON = "LULULEMON";
    public static final String FILSON = "FILSON";
    public static final String ENGELBERT_STRAUSS = "ENGELBERT_STRAUSS";

    public static final List<String> DEFAULT_KEYS = List.of(
            LL_BEAN, TNF, PATAGONIA, LULULEMON, FILSON, ENGELBERT_STRAUSS
    );

    public static final Map<String, String> DEFAULT_BUYERS;
    static {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(LL_BEAN, "L.L.BEAN");
        values.put(TNF, "TNF");
        values.put(PATAGONIA, "PATAGONIA");
        values.put(LULULEMON, "LULULEMON");
        values.put(FILSON, "FILSON");
        values.put(ENGELBERT_STRAUSS, "ENGELBERT STRAUSS");
        DEFAULT_BUYERS = Collections.unmodifiableMap(values);
    }

    private BuyerKeys() { }

    public static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) return LL_BEAN;
        return value.trim().toUpperCase(Locale.ROOT)
                .replace(".", "")
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    public static String legacyDefault(String value) {
        return value == null || value.trim().isEmpty() ? LL_BEAN : normalize(value);
    }
}
