package org.bsl.sales.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class MprSelection {
    /** Unique id for one saved Create / Add To MPR action. */
    private String batchId;
    private LocalDateTime createdAt;
    private String createdBy;

    private String bomId;
    private String bomNo;
    private String bomName;
    private List<String> colors = new ArrayList<>();
    private List<String> packingIds = new ArrayList<>();

    /** Total PO Qty per Product Color (sum of the selected Ship To quantities). */
    private Map<String, BigDecimal> poQtyByColor = new LinkedHashMap<>();

    /** Selected Ship To master IDs per Product Color. */
    private Map<String, List<String>> shipToIdsByColor = new LinkedHashMap<>();

    /** Separate PO Qty entered for each selected Ship To, grouped by Product Color. */
    private Map<String, Map<String, BigDecimal>> shipToQtyByColor = new LinkedHashMap<>();

    /** Readable snapshot used for the MPR line and export, e.g. "HN DC + HCM DC". */
    private Map<String, String> shipToByColor = new LinkedHashMap<>();
}
