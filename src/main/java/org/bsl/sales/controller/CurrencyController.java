package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.CurrencyMasterRequest;
import org.bsl.sales.model.CurrencyMaster;
import org.bsl.sales.service.CurrencyMasterService;
import org.springframework.data.domain.Page;
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

import java.util.List;

/**
 * Currency Master is for conversion to VND only.
 * GET and /resolve always expose the latest rateToVnd; no history DTO/API is
 * needed by FE or MAT_INFO.
 */
@RestController
@Validated
@RequestMapping("/api/master-data/currencies")
public class CurrencyController {

    private final CurrencyMasterService currencyMasterService;

    public CurrencyController(CurrencyMasterService currencyMasterService) {
        this.currencyMasterService = currencyMasterService;
    }

    @PostMapping
    public ResponseEntity<CurrencyMaster> create(@Valid @RequestBody CurrencyMasterRequest request) {
        return ResponseEntity.ok(currencyMasterService.create(request));
    }

    @GetMapping
    public ResponseEntity<Page<CurrencyMaster>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(currencyMasterService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CurrencyMaster> getById(@PathVariable String id) {
        return ResponseEntity.ok(currencyMasterService.getById(id));
    }

    /** Used by MAT_INFO select: exactly one newest rate row per Currency Code. */
    @GetMapping("/current")
    public ResponseEntity<List<CurrencyMaster>> listCurrent() {
        return ResponseEntity.ok(currencyMasterService.listCurrent());
    }

    /** Used by MAT_INFO, MPR and BOM to obtain the current/latest VND rate. */
    @GetMapping("/resolve")
    public ResponseEntity<CurrencyMaster> resolve(@RequestParam String currencyCode) {
        return ResponseEntity.ok(currencyMasterService.resolveCurrent(currencyCode));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CurrencyMaster> update(
            @PathVariable String id,
            @Valid @RequestBody CurrencyMasterRequest request
    ) {
        return ResponseEntity.ok(currencyMasterService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        currencyMasterService.delete(id);
        return ResponseEntity.noContent().build();
    }

}
