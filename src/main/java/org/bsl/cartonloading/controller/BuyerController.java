package org.bsl.cartonloading.controller;

import jakarta.validation.Valid;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.dto.BuyerRequest;
import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.service.BuyerService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers")
public class BuyerController {
    private final BuyerService buyerService;
    private final AppSocketPublisher socketPublisher;

    public BuyerController(BuyerService buyerService, AppSocketPublisher socketPublisher) {
        this.buyerService = buyerService;
        this.socketPublisher = socketPublisher;
    }

    /** Public list used on the login screen. It contains only active Buyers and no private data. */
    @GetMapping("/login-options")
    public List<Buyer> loginOptions() {
        return buyerService.loginOptions();
    }

    @GetMapping("/accessible")
    public List<Buyer> accessible() {
        return buyerService.accessible();
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @GetMapping
    public Page<Buyer> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "true") boolean paged,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return buyerService.list(keyword, active, paged, page, size);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @GetMapping("/{id}")
    public Buyer get(@PathVariable String id) {
        return buyerService.get(id);
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PostMapping
    public Buyer create(@Valid @RequestBody BuyerRequest request) {
        Buyer buyer = buyerService.create(request);
        socketPublisher.buyerChanged("CREATED", buyer.getId());
        return buyer;
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @PutMapping("/{id}")
    public Buyer update(@PathVariable String id, @Valid @RequestBody BuyerRequest request) {
        Buyer buyer = buyerService.update(id, request);
        socketPublisher.buyerChanged("UPDATED", buyer.getId());
        return buyer;
    }

    @PreAuthorize("@accessControl.isAdmin()")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        buyerService.delete(id);
        socketPublisher.buyerChanged("DELETED", id);
        return ResponseEntity.noContent().build();
    }
}
