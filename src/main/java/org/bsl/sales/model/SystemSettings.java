package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "system_settings")
public class SystemSettings {
    public static final String GENERAL_ID = "GENERAL";

    @Id
    private String id = GENERAL_ID;

    private String companyName;
    private String timeZone;
    private String dateFormat;
    private String numberFormat;
    private Integer decimalPlaces;
    private String defaultLanguage;
    private String layoutColor;

    private String logoFileName;
    private String logoContentType;

    @JsonIgnore
    private byte[] logoData;

    private String updatedBy;
    private LocalDateTime updatedAt;
}
