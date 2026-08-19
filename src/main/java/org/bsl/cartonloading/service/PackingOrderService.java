package org.bsl.cartonloading.service;

import org.bsl.cartonloading.dto.PackingOrderRequest;
import org.bsl.cartonloading.dto.PackingOrderResponse;
import org.bsl.cartonloading.enums.CartonScanStatus;
import org.bsl.cartonloading.exception.OrderBomMprNotFoundException;
import org.bsl.cartonloading.exception.OrderBomMprValidationException;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.CartonScanTransaction;
import org.bsl.cartonloading.model.PackingOrder;
import org.bsl.cartonloading.repository.CartonScanTransactionRepository;
import org.bsl.cartonloading.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.repository.PackingListLineRepository;
import org.bsl.cartonloading.repository.PackingOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class PackingOrderService {
    private final PackingOrderRepository orderRepository;
    private final PackingAllocationLineRepository masterLineRepository;
    private final PackingListLineRepository packingLineRepository;
    private final CartonScanTransactionRepository cartonTransactionRepository;

    public PackingOrderService(
            PackingOrderRepository orderRepository,
            PackingAllocationLineRepository masterLineRepository,
            PackingListLineRepository packingLineRepository,
            CartonScanTransactionRepository cartonTransactionRepository
    ) {
        this.orderRepository = orderRepository;
        this.masterLineRepository = masterLineRepository;
        this.packingLineRepository = packingLineRepository;
        this.cartonTransactionRepository = cartonTransactionRepository;
    }

    public Page<PackingOrderResponse> list(
            String buyerCode,
            String keyword,
            LocalDate orderDate,
            String orderName,
            String createdBy,
            String status,
            Boolean completed,
            int page,
            int size
    ) {
        String buyer = requireBuyer(buyerCode);
        String keywordKey = key(keyword);
        String orderNameKey = key(orderName);
        String createdByKey = key(createdBy);
        String statusKey = normalizedStatus(status);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));

        List<PackingOrderResponse> rows = orderRepository.findByBuyerCode(buyer).stream()
                .map(this::response)
                .filter(order -> orderDate == null || orderDate.equals(order.orderDate()))
                .filter(order -> orderNameKey == null || contains(order.orderName(), orderNameKey))
                .filter(order -> createdByKey == null || contains(order.createdBy(), createdByKey))
                .filter(order -> statusKey == null || statusKey.equals(normalizedStatus(order.status())))
                .filter(order -> completed == null || completed.equals(order.completed()))
                .filter(order -> keywordKey == null || matchesKeyword(order, keywordKey))
                .sorted(Comparator
                        .comparing(PackingOrderResponse::orderDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PackingOrderResponse::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public PackingOrderResponse getResponse(String buyerCode, String id) {
        return response(getEntity(buyerCode, id));
    }

    public PackingOrder getEntity(String buyerCode, String id) {
        String buyer = requireBuyer(buyerCode);
        return orderRepository.findByIdAndBuyerCode(id, buyer)
                .orElseThrow(() -> new OrderBomMprNotFoundException("Order not found"));
    }

    public PackingOrderResponse create(String buyerCode, PackingOrderRequest request) {
        String buyer = requireBuyer(buyerCode);
        LocalDateTime now = LocalDateTime.now();

        PackingOrder entity = new PackingOrder();
        entity.setBuyerCode(buyer);
        apply(entity, request);
        entity.setCreatedBy(RequestActor.current());
        entity.setUpdatedBy(RequestActor.current());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return response(orderRepository.save(entity));
    }

    public PackingOrderResponse update(String buyerCode, String id, PackingOrderRequest request) {
        PackingOrder entity = getEntity(buyerCode, id);
        apply(entity, request);
        entity.setUpdatedBy(RequestActor.current());
        entity.setUpdatedAt(LocalDateTime.now());
        return response(orderRepository.save(entity));
    }

    public void delete(String buyerCode, String id) {
        PackingOrder entity = getEntity(buyerCode, id);
        masterLineRepository.deleteByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        packingLineRepository.deleteByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        cartonTransactionRepository.deleteByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        orderRepository.delete(entity);
    }

    public void touchFromMaster(PackingOrder entity) {
        entity.setUpdatedBy(RequestActor.current());
        entity.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(entity);
    }

    private void apply(PackingOrder entity, PackingOrderRequest request) {
        if (request.orderDate() == null) {
            throw new OrderBomMprValidationException("Order Date is required");
        }
        entity.setOrderDate(request.orderDate());
        entity.setOrderName(required(request.orderName(), "Order Name is required"));
        entity.setSupplierName(trimToNull(request.supplierName()));
        entity.setSupplierNumber(trimToNull(request.supplierNumber()));
        entity.setProductionFacility(trimToNull(request.productionFacility()));
    }

    private PackingOrderResponse response(PackingOrder entity) {
        long masterLineCount = masterLineRepository.countByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        long packingLineCount = packingLineRepository.countByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        List<CartonScanTransaction> cartons = cartonTransactionRepository.findByOrderIdAndBuyerCode(
                entity.getId(), entity.getBuyerCode()
        );

        long plannedCartonCount = cartons.stream()
                .filter(row -> row.getStatus() != CartonScanStatus.CANCELLED)
                .count();
        long completedCartonCount = cartons.stream()
                .filter(row -> row.getStatus() == CartonScanStatus.COMPLETED
                        || row.getStatus() == CartonScanStatus.WEIGHT_WARNING)
                .count();
        boolean started = cartons.stream().anyMatch(row -> row.getStatus() == CartonScanStatus.WAITING_WEIGHT
                || row.getStatus() == CartonScanStatus.COMPLETED
                || row.getStatus() == CartonScanStatus.WEIGHT_WARNING);
        boolean completed = plannedCartonCount > 0 && completedCartonCount >= plannedCartonCount;
        String status = resolveStatus(masterLineCount, completed, started);

        return new PackingOrderResponse(
                entity.getId(),
                entity.getBuyerCode(),
                resolvedOrderDate(entity),
                entity.getOrderName(),
                entity.getSupplierName(),
                entity.getSupplierNumber(),
                entity.getProductionFacility(),
                masterLineCount,
                packingLineCount,
                status,
                completed,
                plannedCartonCount,
                completedCartonCount,
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String resolveStatus(long masterLineCount, boolean completed, boolean started) {
        if (masterLineCount <= 0) return "DRAFT";
        if (completed) return "COMPLETED";
        if (started) return "IN_PROGRESS";
        return "NOT_STARTED";
    }

    private boolean matchesKeyword(PackingOrderResponse order, String keywordKey) {
        String completionText = order.completed() ? "YES COMPLETED" : "NO NOT COMPLETED";
        return contains(order.orderName(), keywordKey)
                || contains(order.createdBy(), keywordKey)
                || contains(order.status(), keywordKey)
                || contains(order.status().replace('_', ' '), keywordKey)
                || contains(completionText, keywordKey)
                || (order.orderDate() != null && order.orderDate().toString().contains(keywordKey));
    }

    private LocalDate resolvedOrderDate(PackingOrder entity) {
        if (entity.getOrderDate() != null) return entity.getOrderDate();
        return entity.getCreatedAt() == null ? null : entity.getCreatedAt().toLocalDate();
    }

    private String requireBuyer(String value) {
        String buyer = BuyerAccess.normalize(value);
        if (buyer.isEmpty()) throw new OrderBomMprValidationException("Unsupported Buyer: " + value);
        return buyer;
    }

    private String required(String value, String message) {
        String clean = trimToNull(value);
        if (clean == null) throw new OrderBomMprValidationException(message);
        return clean;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.isEmpty() ? null : clean;
    }

    private String key(String value) {
        String clean = trimToNull(value);
        return clean == null ? null : clean.toUpperCase(Locale.ROOT);
    }

    private String normalizedStatus(String value) {
        String clean = key(value);
        return clean == null ? null : clean.replace('-', '_').replace(' ', '_');
    }

    private boolean contains(String value, String needleKey) {
        String source = key(value);
        return source != null && source.contains(needleKey);
    }
}
