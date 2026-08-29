package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.dto.VendorCodeRequest;
import org.bsl.sales.model.VendorCode;
import org.bsl.sales.service.VendorCodeService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.bsl.sales.support.DownloadFileNames.managerExcel;

@RestController
@Validated
@RequestMapping("/api/master-data/vendor-codes")
public class VendorCodeController {

    private final VendorCodeService vendorCodeService;

    public VendorCodeController(VendorCodeService vendorCodeService) {
        this.vendorCodeService = vendorCodeService;
    }

    @PostMapping
    public ResponseEntity<VendorCode> create(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @Valid @RequestBody VendorCodeRequest request
    ) {
        return ResponseEntity.ok(vendorCodeService.create(buyerKey, request));
    }

    @GetMapping
    public ResponseEntity<Page<VendorCode>> list(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @RequestParam(required = false) String masterKey,
            @RequestParam(required = false) String shortNameSupplier,
            @RequestParam(required = false) String vendorCode,
            @RequestParam(required = false) String vendorName,
            @RequestParam(required = false) String matCharger,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(vendorCodeService.list(
                buyerKey, masterKey, shortNameSupplier, vendorCode, vendorName, matCharger, page, size
        ));
    }

    @GetMapping("/options")
    public ResponseEntity<List<VendorCode>> options(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(vendorCodeService.options(buyerKey, keyword, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VendorCode> getById(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        return ResponseEntity.ok(vendorCodeService.getById(buyerKey, id));
    }

    @GetMapping("/resolve")
    public ResponseEntity<VendorCode> resolve(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @RequestParam String shortNameSupplier
    ) {
        return ResponseEntity.ok(vendorCodeService.resolve(buyerKey, shortNameSupplier));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VendorCode> update(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @Valid @RequestBody VendorCodeRequest request
    ) {
        return ResponseEntity.ok(vendorCodeService.update(buyerKey, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        vendorCodeService.delete(buyerKey, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        MasterDataImportResult result = vendorCodeService.upload(file, mode, buyerKey);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit(@RequestParam(defaultValue = "LLBEAN") String buyerKey) {
        return excelResponse(managerExcel(buyerKey, "VENDORCODE"), vendorCodeService.exportForEdit(buyerKey));
    }

    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        MasterDataImportResult result = vendorCodeService.uploadEdited(file, buyerKey);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    private ResponseEntity<byte[]> excelResponse(String filename, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }
}
