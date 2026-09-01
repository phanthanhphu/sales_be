package org.bsl.sales.exception;

/**
 * Raised only when the generated MPR workbook fails the final runtime integrity
 * gate. The browser must not receive a workbook that Excel would need to repair.
 */
public class MprExcelExportIntegrityException extends RuntimeException {
    public MprExcelExportIntegrityException(String message) {
        super(message);
    }
}
