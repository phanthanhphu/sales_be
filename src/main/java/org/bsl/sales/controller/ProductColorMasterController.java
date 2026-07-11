package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.ProductColorMasterRequest;
import org.bsl.sales.model.ProductColorMaster;
import org.bsl.sales.service.ProductColorMasterService;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/master-data/product-colors")
public class ProductColorMasterController {
    private final ProductColorMasterService service;

    public ProductColorMasterController(ProductColorMasterService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProductColorMaster> create(@Valid @RequestBody ProductColorMasterRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping
    public ResponseEntity<Page<ProductColorMaster>> list(
            @RequestParam(required = false) String productColor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(service.list(productColor, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductColorMaster> get(@PathVariable String id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductColorMaster> update(@PathVariable String id, @Valid @RequestBody ProductColorMasterRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /** Stores exactly one image on Product Color Master. Linked BOMs read this same image. */
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductColorMaster> uploadImage(
            @PathVariable String id,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(service.uploadImage(id, file));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> downloadImage(@PathVariable String id) {
        ProductColorMasterService.ProductColorImageResource image = service.downloadImage(id);
        String contentType = image.contentType() == null || image.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : image.contentType();

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + URLEncoder.encode(image.fileName(), StandardCharsets.UTF_8)
                )
                .body(image.resource());
    }

    @DeleteMapping("/{id}/image")
    public ResponseEntity<Void> deleteImage(@PathVariable String id) {
        service.deleteImage(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
