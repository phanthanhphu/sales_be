package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Transient;

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

    /** Runtime-only usage state. Used Child Colors cannot be removed. */
    @Transient
    private boolean deleteLocked;

    /** Number of material-line references across linked BOMs. */
    @Transient
    private long usageCount;

    /** Human-readable explanation shown by the UI. */
    @Transient
    private String usageMessage;
}
