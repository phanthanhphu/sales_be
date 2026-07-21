package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** One packing section inside a BOM. */
@Data
@NoArgsConstructor
public class BomPacking {
    private String id;
    private String packingName;
    private Integer sequence;

    /**
     * Stable links to BOM Product Color items. Empty means this packing applies to every Product Color.
     * Packing screens resolve the display fields from BomDocument.productColors, so edits are reflected everywhere.
     */
    private List<String> applicableProductColorIds = new ArrayList<>();

    /**
     * Legacy readable color names. This is synchronized from applicableProductColorIds for old API clients/export code.
     */
    private List<String> applicableColors = new ArrayList<>();

    /** Number of rows in bom_lines for this packing. */
    private long lineCount;

    /** Legacy/in-memory aggregate only. New MongoDB records store rows in bom_lines. */
    private List<BomLine> lines = new ArrayList<>();
    /** Imported Excel packing files are retained for export/history. Manual Packing-file upload is disabled. */
    private List<BomAttachment> attachments = new ArrayList<>();
}
