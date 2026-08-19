package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.MprGenerateRequest;
import org.bsl.sales.dto.MprLineUpdateRequest;
import org.bsl.sales.dto.MprValidationResult;
import org.bsl.sales.dto.MprBatchDeleteResult;
import org.bsl.sales.dto.MprBatchUpdateRequest;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.service.MprService;
import org.bsl.sales.service.OrderBomMprExcelExporter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/orders/{orderId}/mpr")
public class MprController {
    private static final ZoneId DOWNLOAD_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DOWNLOAD_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final MprService mprService;
    private final OrderBomMprExcelExporter exporter;

    public MprController(MprService mprService, OrderBomMprExcelExporter exporter) {
        this.mprService = mprService;
        this.exporter = exporter;
    }

    @GetMapping
    public MprDocument get(@PathVariable String orderId) { return mprService.getByOrder(orderId); }

    @PostMapping("/validate")
    public MprValidationResult validate(@PathVariable String orderId, @Valid @RequestBody MprGenerateRequest request) {
        return mprService.validateGeneration(orderId, request);
    }

    @PostMapping("/preview")
    public MprDocument preview(@PathVariable String orderId, @Valid @RequestBody MprGenerateRequest request) { return mprService.preview(orderId, request); }

    @PostMapping("/generate")
    public MprDocument generate(@PathVariable String orderId, @Valid @RequestBody MprGenerateRequest request) { return mprService.generate(orderId, request); }

    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable String orderId) { mprService.delete(orderId); return ResponseEntity.noContent().build(); }

    @PutMapping("/lines/{lineId}")
    public MprDocument updateLine(
            @PathVariable String orderId,
            @PathVariable String lineId,
            @RequestBody MprLineUpdateRequest request
    ) {
        return mprService.updateLine(orderId, lineId, request);
    }

    @DeleteMapping("/lines/{lineId}")
    public MprDocument deleteLine(@PathVariable String orderId, @PathVariable String lineId) {
        return mprService.deleteLine(orderId, lineId);
    }

    @PutMapping("/batches/{batchId}")
    public MprDocument updateBatch(
            @PathVariable String orderId,
            @PathVariable String batchId,
            @RequestBody MprBatchUpdateRequest request
    ) {
        return mprService.updateBatch(orderId, batchId, request);
    }

    @DeleteMapping("/batches/{batchId}")
    public MprBatchDeleteResult deleteBatch(
            @PathVariable String orderId,
            @PathVariable String batchId
    ) {
        return mprService.deleteBatch(orderId, batchId);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MprDocument importExcel(
            @PathVariable String orderId,
            @RequestPart("file") MultipartFile file
    ) {
        return mprService.importExcel(orderId, file);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable String orderId) {
        MprDocument mpr = mprService.getByOrder(orderId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportFileName(mpr) + "\"")
                .body(exporter.exportMpr(mpr));
    }

    private String exportFileName(MprDocument mpr) {
        String buyer = safeBuyerPart(mpr == null ? null : mpr.getBuyerKey());
        String mprNo = safeFilePart(mpr == null ? null : mpr.getMprNo(), "MPR");
        String date = LocalDate.now(DOWNLOAD_TIME_ZONE).format(DOWNLOAD_DATE_FORMAT);
        return "MPR_FILE_" + buyer + "_" + mprNo + "_" + date + ".xlsx";
    }

    private String safeBuyerPart(String value) {
        String resolved = value == null ? "" : value.trim().toUpperCase();
        String safe = resolved.replaceAll("[^A-Z0-9]", "");
        return safe.isBlank() ? "BUYER" : safe;
    }

    private String safeFilePart(String value, String fallback) {
        String resolved = value == null || value.isBlank() ? fallback : value.trim();
        String safe = resolved.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return safe.isBlank() ? fallback : safe;
    }
}
