package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One Product Color column from the BOM Excel header.
 * Example: BLACK / LLB 352 A / F26.
 */
@Data
@NoArgsConstructor
public class BomProductColor {
    /** Stable identifier used by BOM lines and COLOR attachments. */
    private String id;
    /** Excel color header, for example BLACK. */
    private String colorName;
    /** Optional Product Color Master selected for a manually created BOM color. */
    private String productColorMasterId;
    /** Excel row directly below the color header, for example LLB 352 A. */
    private String patternNumber;
    /** Excel row below Pattern Number, for example F26. */
    private String season;
    /** Zero-based original Excel column, retained to patch the source template on export. */
    private Integer sourceColumnIndex;
}
