package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.dto.ShipToRequest;
import org.bsl.sales.model.ShipTo;
import org.bsl.sales.service.ShipToService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.bsl.sales.support.DownloadFileNames.managerExcel;

@RestController
@RequestMapping("/api/master-data/ship-tos")
public class ShipToController {
    private final ShipToService service;

    public ShipToController(ShipToService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ShipTo> create(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @Valid @RequestBody ShipToRequest request
    ) { return ResponseEntity.ok(service.create(buyerKey, request)); }

    @GetMapping
    public ResponseEntity<Page<ShipTo>> list(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @RequestParam(required = false) String shipToName,
            @RequestParam(required = false) String shipToCode,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) { return ResponseEntity.ok(service.list(buyerKey, shipToName, shipToCode, active, page, size)); }

    @GetMapping("/active")
    public ResponseEntity<List<ShipTo>> listActive(@RequestParam(defaultValue = "LLBEAN") String buyerKey) {
        return ResponseEntity.ok(service.listActive(buyerKey));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShipTo> get(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) { return ResponseEntity.ok(service.get(buyerKey, id)); }

    @PutMapping("/{id}")
    public ResponseEntity<ShipTo> update(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @Valid @RequestBody ShipToRequest request
    ) { return ResponseEntity.ok(service.update(buyerKey, id, request)); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) { service.delete(buyerKey, id); return ResponseEntity.noContent().build(); }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        MasterDataImportResult result = service.upload(file, mode, buyerKey);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit(@RequestParam(defaultValue = "LLBEAN") String buyerKey) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + managerExcel(buyerKey, "SHIPTO") + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.exportForEdit(buyerKey));
    }

    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        MasterDataImportResult result = service.uploadEdited(file, buyerKey);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }
}
