package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Header fields read from rows 2-8 of THE BOM DETAILS workbook. */
@Data
@NoArgsConstructor
public class BomHeader {
    private String buyer;
    private String revStage;
    private String comments;
    private String season;
    private String patternDate;
    private String styleNumber;
    private String patternRevisedDate;
    private String patternNumber;
    private String patternMaker;
    private String styleName;
    private String factoryProduct;
    private String bomMaker;
    private String size;
    private String bomDate;
}
