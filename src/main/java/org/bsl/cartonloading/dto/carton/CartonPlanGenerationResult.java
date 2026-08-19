package org.bsl.cartonloading.dto.carton;

public record CartonPlanGenerationResult(
        boolean applied,
        int masterRows,
        int createdCartons,
        int skippedRows,
        String message
) {
}
