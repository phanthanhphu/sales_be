package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable Product / Style Color definition imported from BOM Detail.
 * The master stores only reusable Child Colors. Each BOM material row keeps
 * the relationship to the selected Child Color through childColorId.
 */
@Data
@NoArgsConstructor
@Document(collection = "product_color_masters")
public class ProductColorMaster {
    @Id
    private String id;

    @JsonIgnore
    @Indexed(unique = true)
    private String masterKey;

    private String buyer;
    private String season;
    private String patternNumber;
    private String styleNumber;
    private String styleName;
    private String productColor;
    private boolean active = true;
    private List<ProductColorAttribute> childColors = new ArrayList<>();

    /** Product image belongs to Product Color Master and is reused by every linked BOM. */
    @JsonIgnore
    private String imageStoredFileName;
    private String imageFileName;
    private String imageContentType;
    private long imageSize;
    private LocalDateTime imageUpdatedAt;

    /** The source BOM is returned with enough data for the UI to open the exact BOM route. */
    private String sourceBomId;
    private String sourceOrderId;
    private String sourceBomNo;
    private String sourceBomName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @JsonProperty("hasImage")
    public boolean hasImage() {
        return imageStoredFileName != null && !imageStoredFileName.isBlank();
    }
}
