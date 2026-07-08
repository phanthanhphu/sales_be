package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.BomCreateRequest;
import org.bsl.sales.dto.BomLineRequest;
import org.bsl.sales.dto.BomPackingRequest;
import org.bsl.sales.dto.BomProductColorRequest;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.service.BomService;
import org.bsl.sales.service.OrderBomMprExcelExporter;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class BomController {
    private final BomService bomService;
    private final OrderBomMprExcelExporter exporter;

    public BomController(BomService bomService, OrderBomMprExcelExporter exporter) {
        this.bomService = bomService;
        this.exporter = exporter;
    }

    @GetMapping("/orders/{orderId}/boms")
    public List<BomDocument> list(@PathVariable String orderId) { return bomService.listByOrder(orderId); }

    @PostMapping("/orders/{orderId}/boms")
    public BomDocument create(@PathVariable String orderId, @Valid @RequestBody BomCreateRequest request) { return bomService.create(orderId, request); }

    @PostMapping(value = "/orders/{orderId}/boms/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BomDocument upload(
            @PathVariable String orderId,
            @RequestParam(required = false) String bomNo,
            @RequestParam(required = false) String bomName,
            @RequestPart("file") MultipartFile file
    ) { return bomService.upload(orderId, bomNo, bomName, file); }

    @GetMapping("/boms/{id}")
    public BomDocument get(@PathVariable String id) { return bomService.get(id); }

    @PutMapping("/boms/{id}")
    public BomDocument update(@PathVariable String id, @Valid @RequestBody BomCreateRequest request) { return bomService.update(id, request); }

    @PostMapping(value = "/boms/{id}/replace-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BomDocument replaceExcel(@PathVariable String id, @RequestPart("file") MultipartFile file) { return bomService.replaceExcel(id, file); }

    @DeleteMapping("/boms/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) { bomService.delete(id); return ResponseEntity.noContent().build(); }

    @PostMapping("/boms/{id}/submit")
    public BomDocument submit(@PathVariable String id) { return bomService.submit(id); }

    @PostMapping("/boms/{id}/product-colors")
    public BomDocument addProductColor(@PathVariable String id, @Valid @RequestBody BomProductColorRequest request) {
        return bomService.addProductColor(id, request);
    }

    @PutMapping("/boms/{id}/product-colors/{productColorId}")
    public BomDocument updateProductColor(
            @PathVariable String id,
            @PathVariable String productColorId,
            @Valid @RequestBody BomProductColorRequest request
    ) {
        return bomService.updateProductColor(id, productColorId, request);
    }

    @DeleteMapping("/boms/{id}/product-colors/{productColorId}")
    public BomDocument deleteProductColor(@PathVariable String id, @PathVariable String productColorId) {
        return bomService.deleteProductColor(id, productColorId);
    }

    @PostMapping("/boms/{id}/packings")
    public BomDocument addPacking(@PathVariable String id, @Valid @RequestBody BomPackingRequest request) { return bomService.addPacking(id, request); }

    @PutMapping("/boms/{id}/packings/{packingId}")
    public BomDocument updatePacking(@PathVariable String id, @PathVariable String packingId, @Valid @RequestBody BomPackingRequest request) { return bomService.updatePacking(id, packingId, request); }

    @DeleteMapping("/boms/{id}/packings/{packingId}")
    public BomDocument deletePacking(@PathVariable String id, @PathVariable String packingId) { return bomService.deletePacking(id, packingId); }

    @PostMapping("/boms/{id}/lines")
    public BomDocument addLine(@PathVariable String id, @RequestParam(required = false) String packingId, @Valid @RequestBody BomLineRequest request) { return bomService.addLine(id, packingId, request); }

    @PutMapping("/boms/{id}/lines/{lineId}")
    public BomDocument updateLine(@PathVariable String id, @PathVariable String lineId, @Valid @RequestBody BomLineRequest request) { return bomService.updateLine(id, lineId, request); }

    @DeleteMapping("/boms/{id}/lines/{lineId}")
    public BomDocument deleteLine(@PathVariable String id, @PathVariable String lineId) { return bomService.deleteLine(id, lineId); }

    @PostMapping(value = "/boms/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BomDocument addAttachment(
            @PathVariable String id,
            @RequestParam(defaultValue = "BOM") String scope,
            @RequestParam(required = false) String productColorId,
            /** Legacy compatibility: the old FE may still send the color name. */
            @RequestParam(required = false) String colorKey,
            @RequestParam(required = false) String packingId,
            @RequestParam(required = false) String lineId,
            @RequestPart("file") MultipartFile file
    ) { return bomService.addAttachment(id, scope, productColorId, colorKey, packingId, lineId, file); }

    @DeleteMapping("/boms/{id}/attachments/{attachmentId}")
    public BomDocument deleteAttachment(@PathVariable String id, @PathVariable String attachmentId) { return bomService.deleteAttachment(id, attachmentId); }

    @GetMapping("/boms/{id}/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable String id, @PathVariable String attachmentId) {
        BomService.AttachmentResource file = bomService.downloadAttachment(id, attachmentId);
        String contentType = file.contentType() == null || file.contentType().isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.contentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + java.net.URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8))
                .body(file.resource());
    }

    @GetMapping("/boms/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        BomDocument bom = bomService.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFileName(bom.getBomNo()) + ".xlsx\"")
                .body(exporter.exportBom(bom));
    }

    private String safeFileName(String value) { return (value == null ? "BOM" : value).replaceAll("[^A-Za-z0-9._-]", "_"); }
}
