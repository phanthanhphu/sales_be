package org.bsl.sales.dto;

import java.util.List;

public record MprValidationIssue(
        String code,
        String bomId,
        String bomNo,
        String bomName,
        String productColorId,
        String productColor,
        String requiredShipToId,
        String requiredShipToCode,
        String requiredShipToName,
        List<String> allowedShipToIds,
        List<String> allowedShipToCodes,
        List<String> allowedShipToNames,
        List<String> materials,
        String message,
        String severity,
        boolean blocking,
        String materialType,
        String matFullDescription,
        String matColor,
        String matUnit,
        String masterMaterialType,
        String masterMatFullDescription,
        String masterMatColor,
        String masterMatUnit,
        List<String> mismatchFields,
        Integer sourceRowNumber
) { }
