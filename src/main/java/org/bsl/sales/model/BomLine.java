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
    /** Column C - SAP CODE. */
    private String sapCode;
    /** Column D - Detail No. */
    private String detailNo;
    /** Column E - POSITION. */
    private String position;
    /** Column F - Position description. */
    private String positionDescription;
    /** Column G - second Position description column. */
    private String positionDescriptionExtra;
    /** Column H - P. */
    private String pieceCode;
    /** Column I - X. */
    private BigDecimal dimensionX;
    /** Column J - Y. */
    private BigDecimal dimensionY;
    /** Column K - Q.TY. */
    private BigDecimal quantity;
    /** Column L - ><. */
    private String direction;
    /** Column M - COSTING / MK. */
    private BigDecimal costing;
    /** Column N - COSTING / UNIT. */
    private String costingUnit;
    /** Column O - CONSUMPTION / NET. */
    private BigDecimal consumptionNet;
    /** Column P - CONSUMPTION / UNIT. */
    private String consumptionUnit;
    /** Column Q - B.O.M REMARKS. */
    private String bomRemark;

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

    /** Images/files specifically attached to this material/detail line. */
    private List<BomAttachment> attachments = new ArrayList<>();

    /** False for a material-group line, true for a subordinate construction/detail row. */
    private boolean detailLine;
}
