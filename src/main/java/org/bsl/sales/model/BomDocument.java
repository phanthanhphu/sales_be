package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Lightweight BOM header. Material rows are stored in the bom_lines collection. */
@Data
@NoArgsConstructor
@Document(collection = "boms")
@CompoundIndex(name = "uk_bom_order_no", def = "{'orderId': 1, 'bomNoKey': 1}", unique = true)
public class BomDocument {

    @Id
    private String id;

    @Version
    private Long version;

    @Indexed
    private String orderId;

    @Indexed
    private String buyerKey;

    private String bomNo;
    private String bomNoKey;
    private String bomName;
    private BomHeader header = new BomHeader();

    /** Product Color header items imported from the Buyer-specific BOM Excel color region. */
    private List<BomProductColor> productColors = new ArrayList<>();

    /**
     * Legacy color-name list retained for existing records and older MPR clients.
     * New BOM logic uses productColors as the source of truth.
     */
    private List<String> colors = new ArrayList<>();

    /** SEPARATE for the optimized schema; null/EMBEDDED indicates a legacy record awaiting migration. */
    private String lineStorageMode;
    private long lineCount;
    private long coreLineCount;
    private long imageCount;

    /** Legacy/in-memory aggregate only. New MongoDB records store rows in bom_lines. */
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
