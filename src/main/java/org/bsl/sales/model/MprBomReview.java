package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A review request created when Sales changes a BOM-backed MPR value.
 * Stored inside MprLine so the MPR retains a complete review history.
 */
@Data
@NoArgsConstructor
public class MprBomReview {
    /** PENDING_BOM_REVIEW | APPLIED_TO_BOM | RECHECK_SALES | CANCELLED */
    private String status;
    private String id;

    private String bomId;
    private String sourceLineId;
    private String packingId;
    private String packingName;
    private String productColorId;
    private String styleColor;
    private String materialLabel;

    private List<MprBomReviewChange> changes = new ArrayList<>();

    private String requestedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime requestedAt;

    private String reviewedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reviewedAt;
    private String reviewComment;
}
