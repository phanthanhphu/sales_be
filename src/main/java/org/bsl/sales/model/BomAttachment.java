package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class BomAttachment {
    private String id;
    private String originalFileName;
    private String storedFileName;
    private String contentType;
    private long size;

    /** BOM | COLOR | PACKING | LINE. */
    private String scope;
    /** Stable link to BomDocument.productColors[].id for COLOR attachments. */
    private String productColorId;
    /** Legacy color name retained for backward compatibility. */
    private String colorKey;
    private String packingId;
    private String lineId;

    /** Original Excel row, 1-based, when the asset is derived from an imported workbook. */
    private Integer sourceRowNumber;
    /** True when the asset was extracted from the uploaded Excel workbook. */
    private boolean importedFromExcel;

    /** Relative API URL, for example /api/boms/{id}/attachments/{id}/download. */
    private String downloadUrl;
    private String uploadedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime uploadedAt;
}
