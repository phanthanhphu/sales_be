package org.bsl.cartonloading.dto.carton;

/**
 * Request sent by the web scan station after a Zebra USB HID scan or a manual QA search.
 * The scanner behaves like a keyboard; the browser submits the completed code here.
 *
 * stationCode is optional when manualMode is true. masterLineId is optional and is used
 * only when one QA Code matches more than one Size/Color parent item.
 */
public record ZebraScanRequest(
        String stationCode,
        String barcode,
        String palletCode,
        String scanId,
        String masterLineId,
        Boolean manualMode
) {
}
