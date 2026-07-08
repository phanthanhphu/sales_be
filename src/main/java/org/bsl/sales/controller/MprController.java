package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.MprGenerateRequest;
import org.bsl.sales.dto.MprLineUpdateRequest;
import org.bsl.sales.dto.MprBatchDeleteResult;
import org.bsl.sales.dto.MprBatchUpdateRequest;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.service.MprService;
import org.bsl.sales.service.OrderBomMprExcelExporter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/{orderId}/mpr")
public class MprController {
    private final MprService mprService;
    private final OrderBomMprExcelExporter exporter;

    public MprController(MprService mprService, OrderBomMprExcelExporter exporter) {
        this.mprService = mprService;
        this.exporter = exporter;
    }

    @GetMapping
    public MprDocument get(@PathVariable String orderId) { return mprService.getByOrder(orderId); }

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

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable String orderId) {
        MprDocument mpr = mprService.getByOrder(orderId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFileName(mpr.getMprNo()) + ".xlsx\"")
                .body(exporter.exportMpr(mpr));
    }

    private String safeFileName(String value) { return (value == null ? "MPR" : value).replaceAll("[^A-Za-z0-9._-]", "_"); }
}
