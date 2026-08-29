package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One Product / Style Color owned by a single BOM.
 *
 * Identity, Child Colors and the image are BOM-local data. The image itself is
 * stored as a BOM attachment with scope COLOR and productColorId = this id.
 */
@Data
@NoArgsConstructor
public class BomProductColor {
    /** Stable identifier used by BOM lines, COLOR attachments and MPR selections. */
    private String id;
    /** Excel Product / Style Color header, for example BLACK. */
    private String colorName;

    /**
     * Legacy migration pointer only. New code never reads Product Color Master.
     * Existing deployments may still have this value until the BOM is first loaded,
     * at which point legacy master data is copied into this BOM and the link is cleared.
     */
    @Deprecated
    @JsonIgnore
    private String productColorMasterId;

    /** Pattern Number for this BOM Product Color. */
    private String patternNumber;
    /** Season for this BOM Product Color. */
    private String season;
    /** Style Number for this BOM Product Color. */
    private String styleNumber;
    /** Child Colors belong only to this BOM Product Color. */
    private List<ProductColorAttribute> childColors = new ArrayList<>();
    /** Business display order (1, 2, 3...). */
    private Integer sequence;
    /** Zero-based original Excel column, retained to patch the source template on export. */
    private Integer sourceColumnIndex;
}
