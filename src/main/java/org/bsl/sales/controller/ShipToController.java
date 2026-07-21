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

@RestController
@RequestMapping("/api/master-data/ship-tos")
public class ShipToController {
    private final ShipToService service;

    public ShipToController(ShipToService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ShipTo> create(@Valid @RequestBody ShipToRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping
    public ResponseEntity<Page<ShipTo>> list(
            @RequestParam(required = false) String shipToName,
            @RequestParam(required = false) String shipToCode,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(service.list(shipToName, shipToCode, active, page, size));
    }

    @GetMapping("/active")
    public ResponseEntity<List<ShipTo>> listActive() { return ResponseEntity.ok(service.listActive()); }

    @GetMapping("/{id}")
    public ResponseEntity<ShipTo> get(@PathVariable String id) { return ResponseEntity.ok(service.get(id)); }

    @PutMapping("/{id}")
    public ResponseEntity<ShipTo> update(@PathVariable String id, @Valid @RequestBody ShipToRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode
    ) {
        MasterDataImportResult result = service.upload(file, mode);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ship-to-master-edit.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.exportForEdit());
    }

    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(@RequestParam("file") MultipartFile file) {
        MasterDataImportResult result = service.uploadEdited(file);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }
}
