package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A material Child Color assigned to one BOM Product / Style Color.
 * productColorId links to BomDocument.productColors[].id and childColorId links
 * to that same BOM Product Color's childColors[].id. value is retained for API
 * and Excel compatibility and mirrors the BOM-local Child Color text.
 */
@Data
@NoArgsConstructor
public class BomLineColorValue {
    private String productColorId;
    private String childColorId;
    private String value;
}
