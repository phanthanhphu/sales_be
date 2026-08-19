package org.bsl.cartonloading.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "packing_orders")
public class PackingOrder {
    @Id
    private String id;

    private String buyerCode;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate orderDate;

    private String orderName;
    private String supplierName;
    private String supplierNumber;
    private String productionFacility;

    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
