package org.bsl.sales.support;

/** Shared business-material key used by dedicated Material -> Ship To mapping and MPR generation. */
public final class MaterialShipToMappingKeys {
    private MaterialShipToMappingKeys() { }

    public static String build(
            String sapCode,
            String materialType,
            String matFullDescription,
            String position,
            String matColor,
            String matUnit
    ) {
        String sap = normalized(sapCode);
        if (!sap.isEmpty()) {
            return "SAP|" + sap + "|" + normalized(matColor) + "|" + normalized(matUnit);
        }
        return "MAT|" + normalized(materialType)
                + "|" + normalized(firstNonBlank(matFullDescription, position))
                + "|" + normalized(matColor)
                + "|" + normalized(matUnit);
    }

    public static String normalized(String value) {
        String clean = MasterDataTextNormalizer.trimToNull(value);
        return clean == null ? "" : clean.toUpperCase(java.util.Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        String clean = MasterDataTextNormalizer.trimToNull(first);
        return clean != null ? clean : MasterDataTextNormalizer.trimToNull(second);
    }
}
