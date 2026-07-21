package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Only one current MPR is kept for one order. Generated lines are an immutable-ish snapshot. */
@Data
@NoArgsConstructor
@Document(collection = "mprs")
public class MprDocument {
    @Id
    private String id;

    @Indexed(unique = true)
    private String orderId;

    @Indexed
    private String buyerKey;

    private String mprNo;
    /** DRAFT | COMPLETED */
    private String status;
    private BigDecimal poQuantity;
    private BigDecimal sampleQuantity;
    private List<MprSelection> selections = new ArrayList<>();
    private List<MprLine> lines = new ArrayList<>();

    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
