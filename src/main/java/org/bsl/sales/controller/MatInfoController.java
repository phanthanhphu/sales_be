package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.MatInfoRequest;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.model.MatInfo;
import org.bsl.sales.service.MatInfoService;
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
@RequestMapping("/api/master-data/mat-infos")
public class MatInfoController {

    private final MatInfoService matInfoService;

    public MatInfoController(MatInfoService matInfoService) {
        this.matInfoService = matInfoService;
    }

    @PostMapping
    public ResponseEntity<MatInfo> create(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @Valid @RequestBody MatInfoRequest request
    ) {
        request.setBuyerKey(buyerKey);
        return ResponseEntity.ok(matInfoService.create(request));
    }

    /**
     * Separate filters intentionally avoid one broad keyword scan.
     * Main searchable columns: Flex ID, Material Type, Mat Full Description,
     * Mat Color and Short Name Supplier.
     */
    @GetMapping
    public ResponseEntity<Page<MatInfo>> list(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
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
                        buyerKey,
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

    @GetMapping("/{id}")
    public ResponseEntity<MatInfo> getById(@PathVariable String id) {
        return ResponseEntity.ok(matInfoService.getById(id));
    }

    /** Kept as a small convenience endpoint; MAT_INFO no longer uses Checking. */
    @GetMapping("/resolve")
    public ResponseEntity<MatInfo> resolve(@RequestParam String id) {
        return ResponseEntity.ok(matInfoService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MatInfo> update(
            @PathVariable String id,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @Valid @RequestBody MatInfoRequest request
    ) {
        request.setBuyerKey(buyerKey);
        return ResponseEntity.ok(matInfoService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        matInfoService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "CREATE_ONLY") ImportMode mode,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        MasterDataImportResult result = matInfoService.upload(file, mode, buyerKey);
        return result.isApplied()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/export-edit")
    public ResponseEntity<byte[]> exportForEdit(@RequestParam(defaultValue = "LLBEAN") String buyerKey) {
        return excelResponse("mat-info-" + buyerKey + "-edit.xlsx", matInfoService.exportForEdit(buyerKey));
    }

    @PostMapping(value = "/upload-edited", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MasterDataImportResult> uploadEdited(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "LLBEAN") String buyerKey
    ) {
        MasterDataImportResult result = matInfoService.uploadEdited(file, buyerKey);
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
