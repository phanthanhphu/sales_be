package org.bsl.cartonloading.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "packing_allocation_lines")
public class PackingAllocationLine {
    @Id
    private String id;

    private String buyerCode;
    private String orderId;
    private Integer lineNo;

    private String supplierName;
    private String supplierNumber;
    private String productionFacility;
    private String containerNumber;
    private String shipmentMode;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate etd;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate eta;

    private String poNumber;
    private String articleNumber;
    private String styleNumber;
    private String style;
    private String color;
    private String size;
    private BigDecimal qtyPerCarton;
    private String invoiceNumber;
    private BigDecimal totalPcs;
    private BigDecimal totalCartons;
    private BigDecimal pcsAir;
    private BigDecimal cartonsAir;
    private BigDecimal pcsSea;
    private BigDecimal cartonsSea;
    private BigDecimal cbmAir;
    private BigDecimal kgAir;
    private String status;
    private BigDecimal openPoQtyOverdel;
    private String remarks;
    private String yoLotNumber;
    @JsonProperty("hCtn")
    private BigDecimal heightCarton;
    private BigDecimal cbmCtn;

    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
