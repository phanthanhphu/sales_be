package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One BOM-backed field changed by Sales in an MPR line.
 * bomValue is the source value at the time Sales requested review;
 * salesValue is the proposed value that BOM may apply.
 */
@Data
@NoArgsConstructor
public class MprBomReviewChange {
    private String field;
    private String label;
    private String bomField;
    private String bomValue;
    private String salesValue;
}
