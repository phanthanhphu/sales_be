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
@Document(collection = "scale_stations")
public class ScaleStation {
    @Id
    private String id;

    private String stationCode;
    private String stationName;
    private String plcIp;
    private String gatewayIp;
    private String location;
    private boolean active = true;
    private boolean online;
    private BigDecimal minimumWeightKg;
    private BigDecimal stabilityToleranceKg;
    private String statusMessage;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastHeartbeatAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
}
