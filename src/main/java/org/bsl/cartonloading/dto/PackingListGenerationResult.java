package org.bsl.cartonloading.dto;

public record PackingListGenerationResult(
        boolean applied,
        int created,
        int skipped,
        String message
) {
}
