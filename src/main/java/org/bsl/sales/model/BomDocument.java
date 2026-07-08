package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** BOM document with embedded core lines, packings, and image/file attachments. */
@Data
@NoArgsConstructor
@Document(collection = "boms")
public class BomDocument {

    @Id
    private String id;

    @Indexed
    private String orderId;

    private String bomNo;
    private String bomName;
    private BomHeader header = new BomHeader();

    /** Product Color header items imported from the BOM Excel columns R onward. */
    private List<BomProductColor> productColors = new ArrayList<>();

    /**
     * Legacy color-name list retained for existing records and older MPR clients.
     * New BOM logic uses productColors as the source of truth.
     */
    private List<String> colors = new ArrayList<>();
    private List<BomLine> coreLines = new ArrayList<>();
    private List<BomPacking> packings = new ArrayList<>();
    private List<BomAttachment> attachments = new ArrayList<>();

    /** Source Excel rows deleted in UI. Export clears these rows while preserving their workbook formatting. */
    private List<Integer> deletedSourceRows = new ArrayList<>();

    /** DRAFT | SUBMITTED */
    private String status;

    /** The original uploaded workbook. It is used as the export template so formatting and embedded pictures remain intact. */
    private String sourceFileName;
    private String sourceFileStoredName;
    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime submittedAt;
    private String submittedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
