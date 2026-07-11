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
 * Reusable Product / Style Color definition.
 *
 * Product Color Master stores only shared data:
 * - Product / Style Color name
 * - shared Child Color comments
 * - one shared image
 *
 * Buyer, Season, Pattern Number, Style Number and Style Name belong to the BOM
 * header/BOM product-color columns, so they are not stored here.
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

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @JsonProperty("hasImage")
    public boolean hasImage() {
        return imageStoredFileName != null && !imageStoredFileName.isBlank();
    }
}
