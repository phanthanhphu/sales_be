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
    public ResponseEntity<Loss> create(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @Valid @RequestBody LossRequest request
    ) {
        return ResponseEntity.ok(lossService.create(buyerKey, request));
    }

    @GetMapping
    public ResponseEntity<Page<Loss>> list(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
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
                        buyerKey,
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
    public ResponseEntity<Loss> getById(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        return ResponseEntity.ok(lossService.getById(buyerKey, id));
    }

    @GetMapping("/resolve")
    public ResponseEntity<LossResolutionResponse> resolve(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @RequestParam String materialType,
            @RequestParam BigDecimal totalQuantity
    ) {
        return ResponseEntity.ok(lossService.resolve(buyerKey, materialType, totalQuantity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Loss> update(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @Valid @RequestBody LossRequest request
    ) {
        return ResponseEntity.ok(lossService.update(buyerKey, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        lossService.delete(buyerKey, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        MasterDataImportResult result = lossService.upload(file, mode, buyerKey);
        return result.isApplied()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit(@RequestParam(defaultValue = "LLBEAN") String buyerKey) {
        return excelResponse("loss-master-" + buyerKey + "-edit.xlsx", lossService.exportForEdit(buyerKey));
    }

    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        MasterDataImportResult result = lossService.uploadEdited(file, buyerKey);
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
