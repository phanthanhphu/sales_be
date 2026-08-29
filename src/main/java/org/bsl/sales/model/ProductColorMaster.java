package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * LEGACY migration record. New Product Colors are stored inside each BOM.
 *
 * This collection is retained only so old deployments can migrate existing BOM links.
 * A record is unique inside one Buyer only when all four values match:
 * - Pattern Number
 * - Product / Style Color
 * - Season
 * - Style Number
 *
 * Old Child Colors/image are read only by the one-way BOM migration bridge.
 */
@Data
@NoArgsConstructor
@Document(collection = "product_color_masters")
public class ProductColorMaster {
    @Id
    private String id;

    @JsonIgnore
    private String masterKey;

    private String buyerKey;

    private String patternNumber;
    private String productColor;
    private String season;
    private String styleNumber;
    private boolean active = true;
    private List<ProductColorAttribute> childColors = new ArrayList<>();


    /** Legacy shared image. It is copied into the BOM during migration and is not used by new flows. */
    @JsonIgnore
    private String imageStoredFileName;
    private String imageFileName;
    private String imageContentType;
    private long imageSize;
    private LocalDateTime imageUpdatedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @JsonProperty("hasImage")
    public boolean hasImage() {
        return imageStoredFileName != null && !imageStoredFileName.isBlank();
    }
}
