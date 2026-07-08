package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One reusable Child Color under a Product / Style Color.
 * Product Color Master intentionally stores only these color values;
 * BOM material rows keep the relationship through childColorId.
 */
@Data
@NoArgsConstructor
public class ProductColorAttribute {
    /** Stable id used by BOM material rows. */
    private String id;
    /** Example: MINERAL GREY (17-5102) or MINERAL GREY YKK#181. */
    private String childColor;
}
