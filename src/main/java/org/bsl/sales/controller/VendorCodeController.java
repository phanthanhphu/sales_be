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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequestMapping("/api/master-data/vendor-codes")
public class VendorCodeController {

    private final VendorCodeService vendorCodeService;

    public VendorCodeController(VendorCodeService vendorCodeService) {
        this.vendorCodeService = vendorCodeService;
    }

    @PostMapping
    public ResponseEntity<VendorCode> create(@Valid @RequestBody VendorCodeRequest request) {
        return ResponseEntity.ok(vendorCodeService.create(request));
    }

    @GetMapping
    public ResponseEntity<Page<VendorCode>> list(
            @RequestParam(required = false) String masterKey,
            @RequestParam(required = false) String shortNameSupplier,
            @RequestParam(required = false) String vendorCode,
            @RequestParam(required = false) String vendorName,
            @RequestParam(required = false) String matCharger,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(
                vendorCodeService.list(
                        masterKey,
                        shortNameSupplier,
                        vendorCode,
                        vendorName,
                        matCharger,
                        page,
                        size
                )
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<VendorCode> getById(@PathVariable String id) {
        return ResponseEntity.ok(vendorCodeService.getById(id));
    }

    @GetMapping("/resolve")
    public ResponseEntity<VendorCode> resolve(@RequestParam String shortNameSupplier) {
        return ResponseEntity.ok(vendorCodeService.resolve(shortNameSupplier));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VendorCode> update(
            @PathVariable String id,
            @Valid @RequestBody VendorCodeRequest request
    ) {
        return ResponseEntity.ok(vendorCodeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        vendorCodeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode
    ) {
        MasterDataImportResult result = vendorCodeService.upload(file, mode);
        return result.isApplied()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit() {
        return excelResponse("vendor-code-master-edit.xlsx", vendorCodeService.exportForEdit());
    }

    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(@RequestParam("file") MultipartFile file) {
        MasterDataImportResult result = vendorCodeService.uploadEdited(file);
        return result.isApplied()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    private ResponseEntity<byte[]> excelResponse(String filename, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

}
