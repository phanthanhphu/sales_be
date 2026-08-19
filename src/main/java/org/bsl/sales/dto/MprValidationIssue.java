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
        List<String> materials,
        String message
) { }
