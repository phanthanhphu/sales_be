package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** One OKARA / Sales order. One order can own many BOMs and one current MPR. */
@Data
@NoArgsConstructor
@Document(collection = "orders")
public class SalesOrder {

    @Id
    private String id;

    @JsonIgnore
    @Indexed(unique = true)
    private String orderNoKey;

    private String orderNo;
    private String style;
    private String customer;
    private String season;
    private String comment;

    /** DRAFT | BOM_IN_PROGRESS | BOM_SUBMITTED | MPR_DRAFT | MPR_COMPLETED */
    private String status;

    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
