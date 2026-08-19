package org.bsl.sales.dto;

import java.util.List;

public record MprValidationResult(
        boolean valid,
        List<MprValidationIssue> issues
) { }
