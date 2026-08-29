package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Immutable audit entry created whenever a completed MPR is reopened. */
@Data
@NoArgsConstructor
public class MprReopenHistory {
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String reopenedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reopenedAt;
}
