package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One physical BOM source row represented by a consolidated MPR line.
 *
 * Several Core/Packing rows may be collapsed into one MPR row when their
 * business material identity + consumption values are identical. Keeping each
 * source trace preserves duplicate prevention, batch deletion, and the PO Qty
 * contribution of every removed duplicate row.
 */
@Data
@NoArgsConstructor
public class MprSourceTrace {
    private String generationBatchId;
    private String sourceBomDedupKey;
    private String sourceLineId;
    private Integer sourceRowNumber;
    /** Exact BOM No./material group number of this physical source row. */
    private Integer bomLineNo;
    private String packingId;
    private String packingName;
    private String section;
    private String sourceLabel;

    /** PO Qty contributed by this physical source row before duplicate consolidation. */
    private BigDecimal poQuantity;
    /** Ship To snapshot contributed by this source row. */
    private List<String> shipToIds = new ArrayList<>();
    private String shipTo;
}
