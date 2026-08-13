package org.bsl.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Dedicated material delivery rule. UI may expose this under a Buyer (currently L.L.BEAN),
 * while one shared collection supports future Buyers through buyerKey.
 */
@Data
@NoArgsConstructor
@Document(collection = "material_ship_to_mappings")
@CompoundIndex(name = "uq_material_ship_to_buyer_material", def = "{'buyerKey':1,'materialKey':1}", unique = true)
public class MaterialShipToMapping {
    @Id
    private String id;

    @Indexed(unique = true, sparse = true)
    private String masterKey;

    @Indexed
    private String buyerKey;

    /** Normalized material identity matching MPR material identity. */
    @JsonIgnore
    private String materialKey;

    private String sapCode;
    private String materialType;
    private String matFullDescription;
    private String matColor;
    private String matUnit;

    @Indexed
    private String shipToId;
    private String shipToCode;
    private String shipToName;

    private boolean active = true;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
