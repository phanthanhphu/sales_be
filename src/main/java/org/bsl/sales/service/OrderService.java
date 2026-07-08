package org.bsl.sales.service;

import org.bsl.sales.dto.OrderRequest;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.SalesOrder;
import org.bsl.sales.repository.BomDocumentRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.bsl.sales.repository.SalesOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private final SalesOrderRepository orderRepository;
    private final BomDocumentRepository bomRepository;
    private final MprDocumentRepository mprRepository;

    public OrderService(
            SalesOrderRepository orderRepository,
            BomDocumentRepository bomRepository,
            MprDocumentRepository mprRepository
    ) {
        this.orderRepository = orderRepository;
        this.bomRepository = bomRepository;
        this.mprRepository = mprRepository;
    }

    public Page<SalesOrder> list(String keyword, String season, String status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)));
        String keywordKey = key(keyword);
        String seasonKey = key(season);
        String statusKey = key(status);

        List<SalesOrder> rows = orderRepository.findAll().stream()
                .filter(order -> keywordKey == null || contains(order.getOrderNo(), keywordKey)
                        || contains(order.getStyle(), keywordKey)
                        || contains(order.getCustomer(), keywordKey))
                .filter(order -> seasonKey == null || contains(order.getSeason(), seasonKey))
                .filter(order -> statusKey == null || statusKey.equals(key(order.getStatus())))
                .sorted(Comparator.comparing(SalesOrder::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public SalesOrder get(String id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderBomMprNotFoundException("Order not found"));
    }

    public SalesOrder create(OrderRequest request) {
        String orderNo = required(request.orderNo(), "Order No is required");
        String orderNoKey = key(orderNo);
        if (orderRepository.existsByOrderNoKey(orderNoKey)) {
            throw new OrderBomMprValidationException("Order No already exists: " + orderNo);
        }
        LocalDateTime now = LocalDateTime.now();
        SalesOrder entity = new SalesOrder();
        entity.setOrderNo(orderNo);
        entity.setOrderNoKey(orderNoKey);
        apply(entity, request);
        entity.setStatus("DRAFT");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(RequestActor.current());
        entity.setUpdatedBy(RequestActor.current());
        return orderRepository.save(entity);
    }

    public SalesOrder update(String id, OrderRequest request) {
        SalesOrder entity = get(id);
        String newKey = key(required(request.orderNo(), "Order No is required"));
        if (!newKey.equals(entity.getOrderNoKey()) && orderRepository.existsByOrderNoKey(newKey)) {
            throw new OrderBomMprValidationException("Order No already exists: " + request.orderNo());
        }
        entity.setOrderNo(required(request.orderNo(), "Order No is required"));
        entity.setOrderNoKey(newKey);
        apply(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(RequestActor.current());
        return orderRepository.save(entity);
    }

    public void delete(String id) {
        SalesOrder order = get(id);
        if (bomRepository.countByOrderId(order.getId()) > 0 || mprRepository.existsByOrderId(order.getId())) {
            throw new OrderBomMprValidationException(
                    "Cannot delete order because it already has BOM or MPR data. Delete related data first."
            );
        }
        orderRepository.delete(order);
    }

    public void markBomInProgress(String orderId) {
        SalesOrder order = get(orderId);
        if (!"MPR_COMPLETED".equals(order.getStatus())) {
            order.setStatus("BOM_IN_PROGRESS");
            order.setUpdatedAt(LocalDateTime.now());
            order.setUpdatedBy(RequestActor.current());
            orderRepository.save(order);
        }
    }

    public void markBomSubmitted(String orderId) {
        SalesOrder order = get(orderId);
        if (!"MPR_COMPLETED".equals(order.getStatus())) {
            order.setStatus("BOM_SUBMITTED");
            order.setUpdatedAt(LocalDateTime.now());
            order.setUpdatedBy(RequestActor.current());
            orderRepository.save(order);
        }
    }

    public void markMprDraft(String orderId) {
        SalesOrder order = get(orderId);
        order.setStatus("MPR_DRAFT");
        order.setUpdatedAt(LocalDateTime.now());
        order.setUpdatedBy(RequestActor.current());
        orderRepository.save(order);
    }

    private void apply(SalesOrder entity, OrderRequest request) {
        entity.setStyle(required(request.style(), "Style is required"));
        entity.setCustomer(required(request.customer(), "Customer is required"));
        entity.setSeason(required(request.season(), "Season is required"));
        entity.setComment(trim(request.comment()));
    }

    private String required(String value, String message) {
        String clean = trim(value);
        if (clean.isBlank()) throw new OrderBomMprValidationException(message);
        return clean;
    }

    private String trim(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private String key(String value) { String clean = trim(value); return clean.isBlank() ? null : clean.toUpperCase(Locale.ROOT); }
    private boolean contains(String value, String needleKey) { return key(value) != null && key(value).contains(needleKey); }
}
