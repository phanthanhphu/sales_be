package org.bsl.sales.controller;

import jakarta.validation.Valid;
import org.bsl.sales.dto.OrderRequest;
import org.bsl.sales.model.SalesOrder;
import org.bsl.sales.service.OrderService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @GetMapping
    public Page<SalesOrder> list(
            @RequestParam(defaultValue = "LLBEAN") String buyerKey,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String season,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) { return orderService.list(buyerKey, keyword, season, status, page, size); }

    @GetMapping("/{id}")
    public SalesOrder get(
            @PathVariable String id,
            @RequestParam(required = false) String buyerKey
    ) { return orderService.get(id, buyerKey); }

    @PostMapping
    public ResponseEntity<SalesOrder> create(@Valid @RequestBody OrderRequest request) {
        return ResponseEntity.ok(orderService.create(request));
    }

    @PutMapping("/{id}")
    public SalesOrder update(@PathVariable String id, @Valid @RequestBody OrderRequest request) {
        return orderService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        orderService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
