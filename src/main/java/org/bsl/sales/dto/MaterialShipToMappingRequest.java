package org.bsl.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MaterialShipToMappingRequest(
        @Size(max = 120, message = "SAP Code must not exceed 120 characters") String sapCode,
        @Size(max = 200, message = "Material Type must not exceed 200 characters") String materialType,
        @Size(max = 2000, message = "MAT Full Description must not exceed 2000 characters") String matFullDescription,
        @Size(max = 500, message = "MAT Color must not exceed 500 characters") String matColor,
        @Size(max = 100, message = "MAT Unit must not exceed 100 characters") String matUnit,
        List<@NotBlank(message = "Ship To id must not be blank") String> shipToIds,
        /** Backward-compatible payload field used by older frontends/workbooks. */
        String shipToId,
        Boolean active,
        @Size(max = 1000, message = "Remark must not exceed 1000 characters") String remark
) { }
