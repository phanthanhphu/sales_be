package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One BOM material row per MongoDB document. Keeping rows outside the BOM
 * header avoids MongoDB's 16 MB document limit and allows paged reads.
 */
@Data
@NoArgsConstructor
@Document(collection = "bom_lines")
public class BomLineDocument {
    /** Same UUID as line.id, so a row can be addressed without another lookup key. */
    @Id
    private String id;

    private String bomId;

    /** Null/blank means Core Materials; otherwise it is BomPacking.id. */
    private String packingId;

    private long sortOrder;
    private BomLine line;
}
