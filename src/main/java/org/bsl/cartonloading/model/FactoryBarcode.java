package org.bsl.cartonloading.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsl.cartonloading.enums.FactoryBarcodeStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "factory_barcodes")
public class FactoryBarcode {
    @Id
    private String id;

    /** Human-readable/scannable unique code: YY + factory code + 9-digit running number. */
    private String barcode;
    private Integer year;
    private String factoryCode;
    private Long runningNumber;
    private String batchId;
    private FactoryBarcodeStatus status = FactoryBarcodeStatus.AVAILABLE;

    private Integer printCount = 0;
    private String lastPrintedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastPrintedAt;

    /** Assignment snapshot for quick traceability/search. */
    private String assignedBuyerCode;
    private String assignedOrderId;
    private String assignedOrderName;
    private String assignedCartonId;
    private String assignedCartonCode;
    private Integer assignedCartonNumber;
    private String assignedMasterLineId;
    private String assignedPoNumber;
    private String assignedArticleNumber;
    private String assignedColor;
    private String assignedSize;
    /** Standard Qty/CTN from Master Data. */
    private String assignedQtyPerCarton;
    /** Actual quantity in this physical carton (important for partial last cartons). */
    private String assignedQuantity;
    private String assignedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime assignedAt;

    private String voidReason;
    private String voidBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime voidAt;

    private String createdBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
