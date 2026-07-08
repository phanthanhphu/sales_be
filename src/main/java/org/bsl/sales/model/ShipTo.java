package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** One selectable delivery destination used by Sales while creating MPR. */
@Data
@NoArgsConstructor
@Document(collection = "ship_tos")
public class ShipTo {
    @Id
    private String id;

    /** Normalised unique value so duplicated Ship To names cannot be created. */
    @JsonIgnore
    @Indexed(unique = true)
    private String shipToNameKey;

    private String shipToCode;
    private String shipToName;
    private boolean active = true;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
