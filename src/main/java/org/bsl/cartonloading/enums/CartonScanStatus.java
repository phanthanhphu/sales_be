package org.bsl.cartonloading.enums;

public enum CartonScanStatus {
    /** Carton child record generated from the WSP master and not scanned yet. */
    PLANNED,
    WAITING_WEIGHT,
    COMPLETED,
    WEIGHT_WARNING,
    CANCELLED
}
