package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Transient;

/**
 * One Child Color owned by one BOM Product / Style Color.
 * BOM material rows keep the relationship through childColorId.
 */
@Data
@NoArgsConstructor
public class ProductColorAttribute {
    /** Stable id used by BOM material rows. */
    private String id;
    /** Example: MINERAL GREY (17-5102) or MINERAL GREY YKK#181. */
    private String childColor;

    /** Runtime-only compatibility field. Backend validation still prevents removal while in use. */
    @Transient
    private boolean deleteLocked;

    /** Number of material-line references across linked BOMs. */
    @Transient
    private long usageCount;

    /** Human-readable explanation shown by the UI. */
    @Transient
    private String usageMessage;
}
