package org.bsl.cartonloading.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Buyer key normalizer shared by authentication, permission checks and buyer-scoped data.
 *
 * <p>The first project version used a fixed buyer list. Buyer Management is now dynamic,
 * therefore any safe uppercase buyer key is accepted while legacy aliases are still
 * converted to their canonical values.</p>
 */
public final class BuyerAccess {
    public static final String LL_BEAN = "LL_BEAN";
    public static final String TNF = "TNF";
    public static final String PATAGONA = "PATAGONA";
    public static final String LULULEMON = "LULULEMON";
    public static final String FILSON = "FILSON";
    public static final String ENGELBERT_STRAUSS = "ENGELBERT_STRAUSS";

    /** Default buyers seeded into a new database. */
    public static final List<String> ALL = List.of(
            LL_BEAN,
            TNF,
            PATAGONA,
            LULULEMON,
            FILSON,
            ENGELBERT_STRAUSS
    );

    private BuyerAccess() {
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('&', '_')
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");

        normalized = switch (normalized) {
            case "L_L_BEAN", "LLBEAN", "LL_BEAN" -> LL_BEAN;
            case "TNF", "THE_NORTH_FACE" -> TNF;
            case "PATAGONIA", "PATAGONA" -> PATAGONA;
            case "ENGELBERT_STRAUSS", "ENGELBERTSTRAUSS" -> ENGELBERT_STRAUSS;
            default -> normalized;
        };

        if (normalized.isEmpty() || normalized.length() > 60 || !normalized.matches("[A-Z0-9][A-Z0-9_]*")) {
            return "";
        }
        return normalized;
    }

    /**
     * Normalizes only values explicitly assigned to the account. ADMIN access is handled
     * by {@link User#canAccessBuyer(String)} and by the Buyer accessible-list endpoint,
     * so dynamic Buyers do not depend on a hard-coded list inside the User document.
     */
    public static List<String> normalizeAll(Collection<String> values, boolean ignoredAdminFlag) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = normalize(value);
                if (!normalized.isEmpty()) result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    public static boolean isSupported(String value) {
        return !normalize(value).isEmpty();
    }
}
