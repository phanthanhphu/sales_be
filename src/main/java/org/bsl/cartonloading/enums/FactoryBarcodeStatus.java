package org.bsl.cartonloading.enums;

public enum FactoryBarcodeStatus {
    /** Generated and ready to be printed/scanned for carton assignment. */
    AVAILABLE,
    /** Assigned one-to-one to a generated carton child record. */
    ASSIGNED,
    /** Permanently disabled and never reused. */
    VOID
}
