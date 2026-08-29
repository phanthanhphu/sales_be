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
 * source trace preserves duplicate prevention and batch deletion. PO Qty is a
 * selection-level snapshot repeated on duplicate source rows; it is not an
 * additive contribution during duplicate consolidation.
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

    /** PO Qty snapshot already calculated for the selected BOM/Product Color/Ship To scope. */
    private BigDecimal poQuantity;
    /** Ship To snapshot represented by this physical source row. */
    private List<String> shipToIds = new ArrayList<>();
    private String shipTo;
}
