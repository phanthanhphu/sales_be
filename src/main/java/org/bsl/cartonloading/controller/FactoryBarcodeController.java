package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.dto.barcode.FactoryBarcodeGenerateRequest;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeGenerateResponse;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodePrintRequest;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeSequenceResponse;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeVoidRequest;
import org.bsl.cartonloading.model.FactoryBarcode;
import org.bsl.cartonloading.service.FactoryBarcodeService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/factory-barcodes")
public class FactoryBarcodeController {
    private final FactoryBarcodeService service;

    public FactoryBarcodeController(FactoryBarcodeService service) {
        this.service = service;
    }

    @PreAuthorize("@accessControl.canManageSales()")
    @PostMapping("/generate")
    public FactoryBarcodeGenerateResponse generate(@RequestBody FactoryBarcodeGenerateRequest request) {
        return service.generate(request);
    }

    @PreAuthorize("@accessControl.canManageSales()")
    @GetMapping
    public Page<FactoryBarcode> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return service.list(keyword, status, factoryCode, year, page, size);
    }

    @PreAuthorize("@accessControl.canManageSales()")
    @GetMapping("/sequence")
    public FactoryBarcodeSequenceResponse sequence(
            @RequestParam Integer year,
            @RequestParam String factoryCode
    ) {
        return service.sequence(year, factoryCode);
    }

    @PreAuthorize("@accessControl.canManageSales()")
    @GetMapping("/{barcode}")
    public FactoryBarcode get(@PathVariable String barcode) {
        return service.get(barcode);
    }

    @PreAuthorize("@accessControl.canManageSales()")
    @PostMapping("/mark-printed")
    public List<FactoryBarcode> markPrinted(@RequestBody FactoryBarcodePrintRequest request) {
        return service.markPrinted(request == null ? null : request.barcodes());
    }

    @PreAuthorize("@accessControl.canManageSales()")
    @PostMapping("/{barcode}/void")
    public FactoryBarcode voidBarcode(
            @PathVariable String barcode,
            @RequestBody(required = false) FactoryBarcodeVoidRequest request
    ) {
        return service.voidBarcode(barcode, request == null ? null : request.reason());
    }
}
