package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.BuyerRequest;
import org.bsl.sales.model.Buyer;
import org.bsl.sales.security.BuyerAccessService;
import org.bsl.sales.service.BuyerService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers")
public class BuyerController {
    private final BuyerService service;
    private final BuyerAccessService buyerAccessService;

    public BuyerController(BuyerService service, BuyerAccessService buyerAccessService) {
        this.service = service;
        this.buyerAccessService = buyerAccessService;
    }

    @GetMapping("/accessible")
    public List<Buyer> accessible() {
        return buyerAccessService.accessibleBuyers();
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @GetMapping
    public Object list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "false") boolean paged,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "sequence") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        if (paged) {
            Page<Buyer> result = service.listPage(keyword, active, page, size, sortBy, sortDir);
            return result;
        }
        return service.list(keyword, active);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @GetMapping("/{id}")
    public Buyer get(@PathVariable String id) {
        return service.get(id);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PostMapping
    public Buyer create(@Valid @RequestBody BuyerRequest request) {
        return service.create(request);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PutMapping("/{id}")
    public Buyer update(@PathVariable String id, @Valid @RequestBody BuyerRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
