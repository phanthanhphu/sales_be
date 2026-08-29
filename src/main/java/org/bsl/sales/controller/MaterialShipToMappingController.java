package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.dto.MaterialShipToMappingRequest;
import org.bsl.sales.model.MaterialShipToMapping;
import org.bsl.sales.service.MaterialShipToMappingService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static org.bsl.sales.support.DownloadFileNames.managerExcel;
import static org.bsl.sales.support.DownloadFileNames.managerTemplateExcel;

@RestController
@RequestMapping("/api/master-data/material-ship-to-mappings")
public class MaterialShipToMappingController {
    private final MaterialShipToMappingService service;

    public MaterialShipToMappingController(MaterialShipToMappingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MaterialShipToMapping> create(
            @RequestParam String buyerKey,
            @Valid @RequestBody MaterialShipToMappingRequest request
    ) {
        return ResponseEntity.ok(service.create(buyerKey, request));
    }

    @GetMapping
    public ResponseEntity<Page<MaterialShipToMapping>> list(
            @RequestParam String buyerKey,
            @RequestParam(required = false) String sapCode,
            @RequestParam(required = false) String materialType,
            @RequestParam(required = false) String matFullDescription,
            @RequestParam(required = false) String matColor,
            @RequestParam(required = false) String matUnit,
            @RequestParam(required = false) String shipTo,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(service.list(
                buyerKey, sapCode, materialType, matFullDescription, matColor, matUnit, shipTo, active, page, size
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MaterialShipToMapping> get(
            @RequestParam String buyerKey,
            @PathVariable String id
    ) {
        return ResponseEntity.ok(service.getForBuyer(buyerKey, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MaterialShipToMapping> update(
            @RequestParam String buyerKey,
            @PathVariable String id,
            @Valid @RequestBody MaterialShipToMappingRequest request
    ) {
        return ResponseEntity.ok(service.update(buyerKey, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestParam String buyerKey,
            @PathVariable String id
    ) {
        service.delete(buyerKey, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam String buyerKey,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode
    ) {
        MasterDataImportResult result = service.upload(buyerKey, file, mode);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam String buyerKey) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + managerTemplateExcel(buyerKey, "MATERIALSHIPTO") + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.template(buyerKey));
    }

    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit(@RequestParam String buyerKey) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + managerExcel(buyerKey, "MATERIALSHIPTO") + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.exportForEdit(buyerKey));
    }

    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(
            @RequestParam String buyerKey,
            @RequestParam("file") MultipartFile file
    ) {
        MasterDataImportResult result = service.uploadEdited(buyerKey, file);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }
}
