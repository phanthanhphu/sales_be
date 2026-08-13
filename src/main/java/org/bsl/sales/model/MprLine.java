package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One generated MPR row. BOM source values and Master Data values are copied
 * into this record when Preview/Generate is run, so the MPR is a snapshot.
 * POUCH is intentionally not present because that MPR column was removed.
 */
@Data
@NoArgsConstructor
public class MprLine {
    /* Internal traceability fields. */
    private String id;
    private String bomId;
    private String bomNo;
    private String bomName;
    /** Original 1-based BOM Excel row retained for source traceability; it is not used to re-sort MPR rows. */
    private Integer sourceRowNumber;
    private String sourceLineId;
    private String packingId;
    private String packingName;
    private String section;
    private String productColorId;

    /**
     * Stable source identity for BOM + Product Color + Core/Packing + source row.
     * It prevents re-adding the exact same physical source row. Distinct source
     * rows can later be consolidated while remaining traceable in sourceTraces.
     */
    private String sourceBomDedupKey;

    /**
     * A single Create / Add To MPR action produces one Generation Batch.
     * It makes it possible to trace which user selection produced this row.
     */
    private String generationBatchId;

    /**
     * Every physical Core/Packing source row represented by this MPR row.
     * Older saved records may have an empty list and are handled through the
     * legacy traceability fields above.
     */
    private List<MprSourceTrace> sourceTraces = new ArrayList<>();

    /** Source CONS. value from the BOM, used in the duplicate signature. */
    private BigDecimal sourceDetailConsumption;

    /** Duplicate consolidation metadata shown by the UI and Excel export. */
    private boolean duplicateHighlighted;
    private int removedDuplicateCount;
    private String duplicateNote;

    /**
     * Sales edits to BOM-backed MPR values are held here until BOM approves
     * or sends them back for Sales recheck.
     */
    private List<MprBomReview> bomReviews = new ArrayList<>();

    /* A-C: Style information from BOM Header + selected Product Color. */
    private String styleColorKey;
    private String styleDescription;
    private String styleColor;

    /* D-E: selected by Sales per Product Color. */
    private String shipTo;
    /** Technical traceability for the selected Ship To master records. */
    private java.util.List<String> shipToIds = new java.util.ArrayList<>();
    private String salesComment;

    /* G-Q: BOM-derived MPR fields. */
    private String sapCode;
    private Integer bomLineNo;
    private String materialType;
    /** BOM POSITION copied from the selected Core/Packing source row. */
    private String position;
    private String matFullDescription;
    private String matColor;
    private String matUnit;
    private BigDecimal yield;
    private BigDecimal lossFactor;
    private BigDecimal totalYield;
    private BigDecimal poQuantity;
    private BigDecimal matRequiredQuantity;

    /* R-X: reserved for the next stock/sample calculation phase. */
    private BigDecimal sampleQuantity;
    private BigDecimal matSampleQuantity;
    private BigDecimal mcdStock;
    private BigDecimal cmcdStock;
    private BigDecimal sapStockQuantity;
    private BigDecimal nonSapStockQuantity;
    private BigDecimal purchaseQuantity;

    /* Y-AD: copied from MAT_INFO and Vender Code Master when available. */
    private String currencyMasterId;
    private String currency;
    private BigDecimal matPriceWithoutTax;
    private String shortNameSupplier;
    private String vendorCode;
    private String vendorName;
    private String matCharger;

    /* AE-AI: Currency Master snapshot / future calculation fields. */
    private BigDecimal rateToVnd;
    private BigDecimal matPriceVnd;
    private BigDecimal exchangeRate;
    private BigDecimal matPriceUsd;
    private BigDecimal matAmountUsd;
    private String matDueDate;
    private BigDecimal totalMatAmountPerStyle;

    /* Retained as technical traceability; not a standard MPR column. */
    private String sourceRemark;
}
