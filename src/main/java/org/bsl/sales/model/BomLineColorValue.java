package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A child material color assigned to one BOM Product / Style Color.
 * productColorId links to BomDocument.productColors[].id; childColorId links
 * to ProductColorMaster.childColors[].id. value is retained for API/export
 * compatibility and always mirrors the canonical Child Color text.
 */
@Data
@NoArgsConstructor
public class BomLineColorValue {
    private String productColorId;
    private String childColorId;
    private String value;
}
