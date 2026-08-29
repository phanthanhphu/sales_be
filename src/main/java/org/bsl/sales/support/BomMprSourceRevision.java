package org.bsl.sales.support;

import org.bsl.sales.model.BomDocument;

import java.time.LocalDateTime;

/** Shared helpers for the BOM -> MPR stale-source revision. */
public final class BomMprSourceRevision {
    private BomMprSourceRevision() { }

    public static long current(BomDocument bom) {
        if (bom == null || bom.getMprSourceRevision() == null) return 0L;
        return Math.max(0L, bom.getMprSourceRevision());
    }

    public static long snapshot(Long revision) {
        return revision == null ? 0L : Math.max(0L, revision);
    }

    public static void markChanged(
            BomDocument bom,
            String summary,
            String actor,
            LocalDateTime changedAt
    ) {
        if (bom == null) return;
        bom.setMprSourceRevision(current(bom) + 1L);
        bom.setMprSourceChangedAt(changedAt == null ? LocalDateTime.now() : changedAt);
        bom.setMprSourceChangedBy(actor == null ? "" : actor.trim());
        bom.setMprSourceChangeSummary(summary == null ? "BOM source data updated" : summary.trim());
    }
}
