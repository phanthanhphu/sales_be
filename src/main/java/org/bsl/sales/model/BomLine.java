package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One material/detail row in a BOM core section or a packing section. */
@Data
@NoArgsConstructor
public class BomLine {
    private String id;
    /** Excel row number, 1-based. Used to patch the original uploaded workbook on export. */
    private Integer sourceRowNumber;

    /** Column A - No. */
    private Integer materialGroupNo;
    /** Column B - MTR (Material Type). */
    private String materialType;
    /** SAP CODE: legacy column C, Image-format column D. */
    private String sapCode;
    /** Detail No.: legacy column D, Image-format column E. */
    private String detailNo;
    /** Column E - POSITION. */
    private String position;
    /** Column F - Position description. */
    private String positionDescription;
    /** Column G - second Position description column. */
    private String positionDescriptionExtra;
    /** Column H - P. */
    private String pieceCode;
    /** Dimension X: legacy column I, new format column J. */
    private BigDecimal dimensionX;
    /** Dimension Y: legacy column J, new format column I. */
    private BigDecimal dimensionY;
    /** Column K - Q.TY. */
    private BigDecimal quantity;
    /** Column L - ><. */
    private String direction;
    /** Legacy format column M - COSTING / MK. */
    private BigDecimal costing;
    /** Legacy format column N - COSTING / UNIT. */
    private String costingUnit;
    /** New format column M - detail CONS. used to calculate the MPR consumption. */
    private BigDecimal detailConsumption;
    /** Consumption used by MPR: legacy O/NET, new N/CONSUMPTION MPR. */
    private BigDecimal consumptionNet;
    /** Consumption unit: legacy P, new O. */
    private String consumptionUnit;
    /** Remarks on BOM: legacy Q, new P. */
    private String bomRemark;
    /** New format trailing REMARKS column (Y in the current template). */
    private String additionalRemark;

    /**
     * Product Color values linked to BomDocument.productColors[].id.
     * This is the source of truth for newly created/updated lines.
     */
    private List<BomLineColorValue> productColorValues = new ArrayList<>();

    /**
     * Backward-compatible color-name map for existing Mongo documents and old API clients.
     * It is synchronized from productColorValues whenever a line is saved.
     */
    private Map<String, String> colorValues = new LinkedHashMap<>();

    /** Main image displayed in the dedicated Excel/UI Image column. Binary data is stored outside MongoDB. */
    private BomImage primaryImage;

    /** Other images/files specifically attached to this material/detail line. */
    private List<BomAttachment> attachments = new ArrayList<>();

    /** Small denormalized count so table GET does not need another query. */
    private int attachmentCount;

    /** False for a material-group line, true for a subordinate construction/detail row. */
    private boolean detailLine;
}
