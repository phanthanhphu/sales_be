package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.dto.carton.*;
import org.bsl.cartonloading.dto.barcode.BarcodeAssignmentPageResponse;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeAssignRequest;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeScanRequest;
import org.bsl.cartonloading.model.CartonScanTransaction;
import org.bsl.cartonloading.model.FactoryBarcode;
import org.bsl.cartonloading.service.CartonLoadingService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/carton-loading")
public class CartonLoadingController {
    private final CartonLoadingService service;

    public CartonLoadingController(CartonLoadingService service) {
        this.service = service;
    }


    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping("/{buyer}/orders/{orderId}/cartons/generate")
    public CartonPlanGenerationResult generateCartonsFromWsp(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(defaultValue = "true") boolean replace
    ) {
        return service.generateFromWsp(buyer, orderId, replace);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/orders/{orderId}/cartons")
    public Page<CartonScanTransaction> listCartons(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return service.listCartons(buyer, orderId, keyword, status, page, size);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/orders/{orderId}/items/{masterLineId}/cartons")
    public List<CartonScanTransaction> listCartonsForItem(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String masterLineId
    ) {
        return service.listCartonsForItem(buyer, orderId, masterLineId);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/orders/{orderId}/barcode-assignment/cartons")
    public BarcodeAssignmentPageResponse listCartonsForBarcodeAssignment(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "UNASSIGNED") String assignment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return service.listCartonsForBarcodeAssignment(buyer, orderId, keyword, assignment, page, size);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/orders/{orderId}/barcode-assignment/check/{barcode}")
    public FactoryBarcode checkFactoryBarcodeForAssignment(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String barcode
    ) {
        return service.checkFactoryBarcodeForAssignment(buyer, orderId, barcode);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/orders/{orderId}/barcode-assignment")
    public CartonScanTransaction assignFactoryBarcode(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody FactoryBarcodeAssignRequest request
    ) {
        return service.assignFactoryBarcode(buyer, orderId, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @DeleteMapping("/{buyer}/orders/{orderId}/barcode-assignment/cartons/{cartonId}")
    public CartonScanTransaction unassignFactoryBarcode(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.unassignFactoryBarcode(buyer, orderId, cartonId);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/orders/{orderId}/factory-barcode/scan")
    public CartonScanTransaction scanAssignedFactoryBarcode(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody FactoryBarcodeScanRequest request
    ) {
        return service.scanAssignedFactoryBarcode(buyer, orderId, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/orders/{orderId}/items/scan-lookup")
    public CartonItemLookupResponse lookupGeneratedItems(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody CartonItemLookupRequest request
    ) {
        return service.lookupGeneratedItems(buyer, orderId, request);
    }

    /**
     * Zebra USB HID flow: match the QA code and atomically reserve the next PLANNED carton.
     * No Android/mobile camera page and no manual carton selection are required.
     */
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/orders/{orderId}/scan-next")
    public CartonScanTransaction scanNextFromZebra(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody ZebraScanRequest request
    ) {
        return service.scanNextFromZebra(buyer, orderId, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/orders/{orderId}/cartons/{cartonId}/manual-complete")
    public CartonScanTransaction completePlannedCartonManually(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody CartonManualCompleteRequest request
    ) {
        return service.completePlannedCartonManually(buyer, orderId, cartonId, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/orders/{orderId}/cartons/{cartonId}/scan")
    public CartonScanTransaction scanPlannedCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody CartonPlanScanRequest request
    ) {
        return service.scanPlannedCarton(buyer, orderId, cartonId, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/orders/{orderId}/lookup")
    public CartonLookupResponse lookup(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody CartonLookupRequest request
    ) {
        return service.lookup(buyer, orderId, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/orders/{orderId}/transactions")
    public CartonScanTransaction start(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody CartonStartRequest request
    ) {
        return service.start(buyer, orderId, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/stations/{stationCode}/current")
    public CartonScanTransaction current(
            @PathVariable String buyer,
            @PathVariable String stationCode
    ) {
        return service.current(buyer, stationCode);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/transactions/{transactionId}")
    public CartonScanTransaction get(
            @PathVariable String buyer,
            @PathVariable String transactionId
    ) {
        return service.get(buyer, transactionId);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping("/{buyer}/transactions/{transactionId}/manual-weight")
    public CartonScanTransaction manualWeight(
            @PathVariable String buyer,
            @PathVariable String transactionId,
            @RequestBody ManualWeightRequest request
    ) {
        return service.manualWeight(buyer, transactionId, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/orders/{orderId}/progress")
    public CartonProgressResponse progress(
            @PathVariable String buyer,
            @PathVariable String orderId
    ) {
        return service.progress(buyer, orderId);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/orders/{orderId}/recent")
    public List<CartonScanTransaction> recent(
            @PathVariable String buyer,
            @PathVariable String orderId
    ) {
        return service.recent(buyer, orderId);
    }

    /* PLC Gateway endpoints. The gateway authenticates with a normal service-user JWT. */
    @PreAuthorize("@accessControl.canManageSales()")
    @GetMapping("/plc/stations/{stationCode}/job")
    public PlcJobResponse currentPlcJob(@PathVariable String stationCode) {
        return service.currentPlcJob(stationCode);
    }

    @PreAuthorize("@accessControl.canManageSales()")
    @PostMapping("/plc/weights")
    public CartonScanTransaction receivePlcWeight(@RequestBody PlcWeightRequest request) {
        return service.receivePlcWeight(request);
    }
}
