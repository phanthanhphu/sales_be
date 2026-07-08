package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.LossRequest;
import org.bsl.sales.dto.LossResolutionResponse;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.model.Loss;
import org.bsl.sales.service.LossService;
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

import java.math.BigDecimal;

@RestController
@Validated
@RequestMapping("/api/master-data/loss")
public class LossController {

    private final LossService lossService;

    public LossController(LossService lossService) {
        this.lossService = lossService;
    }

    @PostMapping
    public ResponseEntity<Loss> create(@Valid @RequestBody LossRequest request) {
        return ResponseEntity.ok(lossService.create(request));
    }

    @GetMapping
    public ResponseEntity<Page<Loss>> list(
            @RequestParam(required = false) String masterKey,
            @RequestParam(required = false) String materialGroup,
            @RequestParam(required = false) BigDecimal lossLt501,
            @RequestParam(required = false) BigDecimal lossLt1501,
            @RequestParam(required = false) BigDecimal lossLt3001,
            @RequestParam(required = false) BigDecimal lossGte3001,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(
                lossService.list(
                        masterKey,
                        materialGroup,
                        lossLt501,
                        lossLt1501,
                        lossLt3001,
                        lossGte3001,
                        page,
                        size
                )
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<Loss> getById(@PathVariable String id) {
        return ResponseEntity.ok(lossService.getById(id));
    }

    @GetMapping("/resolve")
    public ResponseEntity<LossResolutionResponse> resolve(
            @RequestParam String materialType,
            @RequestParam BigDecimal totalQuantity
    ) {
        return ResponseEntity.ok(lossService.resolve(materialType, totalQuantity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Loss> update(
            @PathVariable String id,
            @Valid @RequestBody LossRequest request
    ) {
        return ResponseEntity.ok(lossService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        lossService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode
    ) {
        MasterDataImportResult result = lossService.upload(file, mode);
        return result.isApplied()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit() {
        return excelResponse("loss-master-edit.xlsx", lossService.exportForEdit());
    }

    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(@RequestParam("file") MultipartFile file) {
        MasterDataImportResult result = lossService.uploadEdited(file);
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
