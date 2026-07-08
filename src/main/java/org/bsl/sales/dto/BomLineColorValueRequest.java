package org.bsl.sales.dto;

import org.bsl.sales.model.BomLineColorValue;

/** One selected Product / Style Color and a Child Color in a BOM material line. */
public record BomLineColorValueRequest(String productColorId, String childColorId, String value) {
    public BomLineColorValue toModel() {
        BomLineColorValue result = new BomLineColorValue();
        result.setProductColorId(productColorId == null ? "" : productColorId.trim());
        result.setChildColorId(childColorId == null ? "" : childColorId.trim());
        result.setValue(value == null ? "" : value.trim());
        return result;
    }
}
