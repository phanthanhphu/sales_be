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
    /** Pattern Number for this Product Color column. */
    private String patternNumber;
    /** Season for this Product Color column. */
    private String season;
    /** New BOM format: Style Number stored for this Product Color column. */
    private String styleNumber;
    /** New BOM format: business display order (1, 2, 3...). */
    private Integer sequence;
    /** Zero-based original Excel column, retained to patch the source template on export. */
    private Integer sourceColumnIndex;
}
