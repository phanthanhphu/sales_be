package org.bsl.cartonloading.controller;

import jakarta.validation.Valid;
import org.bsl.cartonloading.dto.ImportMode;
import org.bsl.cartonloading.dto.MatInfoRequest;
import org.bsl.cartonloading.dto.MasterDataImportResult;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.MatInfo;
import org.bsl.cartonloading.service.MatInfoService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/master-data/mat-infos")
public class MatInfoController {

    private final MatInfoService matInfoService;

    public MatInfoController(MatInfoService matInfoService) {
        this.matInfoService = matInfoService;
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping
    public ResponseEntity<MatInfo> create(
            @RequestParam String buyer,
            @Valid @RequestBody MatInfoRequest request
    ) {
        request.setBuyerCode(requiredBuyer(buyer));
        return ResponseEntity.ok(matInfoService.create(request));
    }

    /** All MAT_INFO searches are isolated by Buyer. */
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping
    public ResponseEntity<Page<MatInfo>> list(
            @RequestParam String buyer,
            @RequestParam(required = false) String masterKey,
            @RequestParam(required = false) String flexId,
            @RequestParam(required = false) String materialType,
            @RequestParam(required = false) String matFullDescription,
            @RequestParam(required = false) String matColor,
            @RequestParam(required = false) String shortNameSupplier,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(
                matInfoService.list(
                        requiredBuyer(buyer),
                        masterKey,
                        flexId,
                        materialType,
                        matFullDescription,
                        matColor,
                        shortNameSupplier,
                        page,
                        size
                )
        );
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{id}")
    public ResponseEntity<MatInfo> getById(@PathVariable String id, @RequestParam String buyer) {
        return ResponseEntity.ok(matInfoService.getById(id, requiredBuyer(buyer)));
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/resolve")
    public ResponseEntity<MatInfo> resolve(@RequestParam String id, @RequestParam String buyer) {
        return ResponseEntity.ok(matInfoService.getById(id, requiredBuyer(buyer)));
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PutMapping("/{id}")
    public ResponseEntity<MatInfo> update(
            @PathVariable String id,
            @RequestParam String buyer,
            @Valid @RequestBody MatInfoRequest request
    ) {
        request.setBuyerCode(requiredBuyer(buyer));
        return ResponseEntity.ok(matInfoService.update(id, request));
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestParam String buyer) {
        matInfoService.delete(id, requiredBuyer(buyer));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam String buyer,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode
    ) {
        MasterDataImportResult result = matInfoService.upload(file, mode, requiredBuyer(buyer));
        return result.isApplied()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit(@RequestParam String buyer) {
        String normalizedBuyer = requiredBuyer(buyer);
        return excelResponse(
                "mat-info-" + normalizedBuyer.toLowerCase() + "-edit.xlsx",
                matInfoService.exportForEdit(normalizedBuyer)
        );
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(
            @RequestParam String buyer,
            @RequestParam("file") MultipartFile file
    ) {
        MasterDataImportResult result = matInfoService.uploadEdited(file, requiredBuyer(buyer));
        return result.isApplied()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    private String requiredBuyer(String value) {
        String normalized = BuyerAccess.normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Unsupported Buyer: " + value);
        }
        return normalized;
    }

    private ResponseEntity<byte[]> excelResponse(String filename, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }
}
