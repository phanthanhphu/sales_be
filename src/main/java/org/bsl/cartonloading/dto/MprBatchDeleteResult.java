package org.bsl.cartonloading.dto;

import org.bsl.cartonloading.model.MprDocument;

/**
 * Result returned after deleting one saved MPR Generation Batch.
 *
 * If the deleted batch was the only remaining data, mprDeleted is true and
 * mpr is null because the empty MPR document has been removed.
 */
public record MprBatchDeleteResult(
        boolean mprDeleted,
        int removedLineCount,
        int remainingLineCount,
        MprDocument mpr
) {
}
