package org.bsl.sales.exception;

import java.util.ArrayList;
import java.util.List;

/**
 * Signals that a BOM Excel upload contains Product Color columns with the same
 * four business keys and therefore requires an explicit user decision before
 * the existing BOM is replaced.
 */
public class BomExcelDuplicateProductColorException extends RuntimeException {

    public static final String CODE = "DUPLICATE_PRODUCT_COLOR_CONFIRMATION_REQUIRED";

    private final List<DuplicateProductColor> duplicates;

    public BomExcelDuplicateProductColorException(List<DuplicateProductColor> duplicates) {
        super("Duplicate Product Color detected in BOM Excel. Continue to keep the first occurrence, or cancel to check the file.");
        this.duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
    }

    public List<DuplicateProductColor> getDuplicates() {
        return duplicates;
    }

    /** One duplicate group. keptColumn is always the first/leftmost Excel column. */
    public record DuplicateProductColor(
            String colorName,
            String patternNumber,
            String season,
            String styleNumber,
            String keptColumn,
            List<String> duplicateColumns
    ) {
        public DuplicateProductColor {
            duplicateColumns = duplicateColumns == null
                    ? List.of()
                    : List.copyOf(new ArrayList<>(duplicateColumns));
        }
    }
}
