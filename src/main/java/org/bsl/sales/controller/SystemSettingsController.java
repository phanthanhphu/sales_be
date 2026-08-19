package org.bsl.sales.controller;

import org.bsl.sales.dto.SystemSettingsRequest;
import org.bsl.sales.dto.SystemSettingsResponse;
import org.bsl.sales.service.SystemSettingsService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/system-settings")
public class SystemSettingsController {
    private final SystemSettingsService service;

    public SystemSettingsController(SystemSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SystemSettingsResponse get() {
        return service.get();
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PutMapping
    public SystemSettingsResponse update(@RequestBody SystemSettingsRequest request) {
        return service.update(request);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PutMapping("/layout-color")
    public SystemSettingsResponse updateLayoutColor(@RequestBody LayoutColorRequest request) {
        return service.updateLayoutColor(request == null ? null : request.layoutColor());
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SystemSettingsResponse updateLogo(@RequestPart("file") MultipartFile file) {
        return service.updateLogo(file);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @DeleteMapping("/logo")
    public SystemSettingsResponse deleteLogo() {
        return service.deleteLogo();
    }

    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo() {
        SystemSettingsService.LogoPayload logo = service.getLogo();
        if (logo == null) return ResponseEntity.notFound().build();

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(logo.contentType());
        } catch (Exception ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        String safeName = logo.fileName().replace("\"", "_");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + java.net.URLEncoder.encode(safeName, StandardCharsets.UTF_8))
                .body(logo.data());
    }
    public record LayoutColorRequest(String layoutColor) { }
}
