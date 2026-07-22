package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable Product / Style Color definition.
 *
 * Product Color Master stores one reusable Product Color identity.
 * A record is unique inside one Buyer only when all four values match:
 * - Pattern Number
 * - Product / Style Color
 * - Season
 * - Style Number
 *
 * Child Colors and one image are reused only by BOM Product Colors with that
 * exact four-field identity.
 */
@Data
@NoArgsConstructor
@Document(collection = "product_color_masters")
@CompoundIndexes({
        @CompoundIndex(name = "uk_product_color_buyer_key", def = "{'buyerKey': 1, 'masterKey': 1}", unique = true),
        @CompoundIndex(name = "idx_product_color_buyer_updated", def = "{'buyerKey': 1, 'updatedAt': -1}"),
        @CompoundIndex(name = "idx_product_color_buyer_pattern", def = "{'buyerKey': 1, 'patternNumber': 1}"),
        @CompoundIndex(name = "idx_product_color_buyer_color", def = "{'buyerKey': 1, 'productColor': 1}"),
        @CompoundIndex(name = "idx_product_color_buyer_season", def = "{'buyerKey': 1, 'season': 1}"),
        @CompoundIndex(name = "idx_product_color_buyer_style", def = "{'buyerKey': 1, 'styleNumber': 1}")
})
public class ProductColorMaster {
    @Id
    private String id;

    @JsonIgnore
    @Indexed
    private String masterKey;

    @Indexed
    private String buyerKey;

    private String patternNumber;
    private String productColor;
    private String season;
    private String styleNumber;
    private boolean active = true;
    private List<ProductColorAttribute> childColors = new ArrayList<>();

    /**
     * Runtime-only usage information. A Product Color that is still linked to
     * at least one BOM cannot be deleted or have its four identity fields
     * changed. These fields are calculated by ProductColorMasterService and
     * are never persisted to MongoDB.
     */
    @Transient
    private boolean deleteLocked;

    @Transient
    private long linkedBomCount;

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
