package org.bsl.cartonloading.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "packing_list_lines")
public class PackingListLine {
    @Id
    private String id;

    private String buyerCode;
    private String orderId;
    private Integer lineNo;

    private BigDecimal cartonFrom;
    private BigDecimal cartonTo;
    private BigDecimal cartonsQty;
    private String poNumber;
    private String styleNumber;
    private String style;
    private String articleNumber;
    private String color;
    private String size;
    private BigDecimal qtyPerCarton;
    private BigDecimal totalPcs;
    private String cartonMeasurement;
    private BigDecimal cbm;
    private BigDecimal grossWeightKg;
    private BigDecimal netWeightKg;
    private BigDecimal actualWeightKg;
    private String remarks;

    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
