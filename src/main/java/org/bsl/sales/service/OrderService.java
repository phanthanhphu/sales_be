package org.bsl.sales.service;

import org.bsl.sales.dto.OrderRequest;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.SalesOrder;
import org.bsl.sales.repository.BomDocumentRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.bsl.sales.repository.SalesOrderRepository;
import org.bsl.sales.security.BuyerAccessService;
import org.bsl.sales.support.BuyerKeys;
import org.bsl.sales.support.MasterDataSequentialKey;
import org.bsl.sales.support.NewestFirstSort;
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
    private static final String ORDER_NO_PREFIX = "ORD";
    private static final String ORDER_SEQUENCE_PREFIX = "sales_order:";

    private final SalesOrderRepository orderRepository;
    private final BomDocumentRepository bomRepository;
    private final MprDocumentRepository mprRepository;
    private final BuyerAccessService buyerAccess;
    private final MasterDataSequenceService sequenceService;

    public OrderService(
            SalesOrderRepository orderRepository,
            BomDocumentRepository bomRepository,
            MprDocumentRepository mprRepository,
            BuyerAccessService buyerAccess,
            MasterDataSequenceService sequenceService
    ) {
        this.orderRepository = orderRepository;
        this.bomRepository = bomRepository;
        this.mprRepository = mprRepository;
        this.buyerAccess = buyerAccess;
        this.sequenceService = sequenceService;
    }

    public Page<SalesOrder> list(String buyerKey, String keyword, String season, String status, int page, int size, String sortBy, String sortDir) {
        String allowedBuyer = buyerAccess.requireBuyer(buyerKey);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)));
        String keywordKey = key(keyword);
        String seasonKey = key(season);
        String statusKey = key(status);

        List<SalesOrder> rows = orderRepository.findByBuyerKey(allowedBuyer).stream()
                .peek(this::normalizeLegacyMprStatus)
                .filter(order -> keywordKey == null || contains(order.getOrderNo(), keywordKey)
                        || contains(order.getStyle(), keywordKey)
                        || contains(order.getCustomer(), keywordKey))
                .filter(order -> seasonKey == null || contains(order.getSeason(), seasonKey))
                .filter(order -> statusKey == null || statusKey.equals(key(order.getStatus())))
                .sorted(orderComparator(sortBy, sortDir))
                .collect(Collectors.toList());

        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    private Comparator<SalesOrder> orderComparator(String sortBy, String sortDir) {
        String field = sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT);
        int direction = "desc".equalsIgnoreCase(sortDir) ? -1 : 1;
        Comparator<SalesOrder> newestFirst = NewestFirstSort.comparator(
                SalesOrder::getCreatedAt,
                SalesOrder::getUpdatedAt,
                SalesOrder::getId
        );

        if (field.isBlank()) return newestFirst;

        return (left, right) -> {
            int compared = switch (field) {
                case "orderno" -> compareText(left.getOrderNo(), right.getOrderNo(), direction);
                case "style" -> compareText(left.getStyle(), right.getStyle(), direction);
                case "customer" -> compareText(left.getCustomer(), right.getCustomer(), direction);
                case "season" -> compareText(left.getSeason(), right.getSeason(), direction);
                case "status" -> compareText(left.getStatus(), right.getStatus(), direction);
                case "createdat" -> compareComparable(left.getCreatedAt(), right.getCreatedAt(), direction);
                case "updatedat" -> compareComparable(left.getUpdatedAt(), right.getUpdatedAt(), direction);
                default -> 0;
            };
            return compared != 0 ? compared : newestFirst.compare(left, right);
        };
    }

    private int compareText(String left, String right, int direction) {
        String leftValue = left == null ? null : left.trim();
        String rightValue = right == null ? null : right.trim();
        if ((leftValue == null || leftValue.isEmpty()) && (rightValue == null || rightValue.isEmpty())) return 0;
        if (leftValue == null || leftValue.isEmpty()) return 1;
        if (rightValue == null || rightValue.isEmpty()) return -1;
        return leftValue.compareToIgnoreCase(rightValue) * direction;
    }

    private <T extends Comparable<? super T>> int compareComparable(T left, T right, int direction) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return left.compareTo(right) * direction;
    }

    public SalesOrder get(String id) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderBomMprNotFoundException("Order not found"));
        buyerAccess.requireEntityAccess(order.getBuyerKey());
        if (order.getBuyerKey() == null || order.getBuyerKey().isBlank()) {
            order.setBuyerKey(BuyerKeys.LL_BEAN);
        }
        normalizeLegacyMprStatus(order);
        return order;
    }

    public SalesOrder get(String id, String expectedBuyerKey) {
        SalesOrder order = get(id);
        if (expectedBuyerKey != null && !expectedBuyerKey.isBlank()) {
            String expected = buyerAccess.requireBuyer(expectedBuyerKey);
            if (!expected.equals(BuyerKeys.legacyDefault(order.getBuyerKey()))) {
                throw new OrderBomMprNotFoundException("Order not found for Buyer " + expected);
            }
        }
        return order;
    }

    public SalesOrder create(OrderRequest request) {
        String buyerKey = buyerAccess.requireBuyer(request.buyerKey());
        String orderNo = nextAvailableOrderNo(buyerKey);
        String orderNoKey = key(orderNo);
        LocalDateTime now = LocalDateTime.now();
        SalesOrder entity = new SalesOrder();
        entity.setBuyerKey(buyerKey);
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
        String requestedBuyer = request.buyerKey() == null || request.buyerKey().isBlank()
                ? BuyerKeys.legacyDefault(entity.getBuyerKey())
                : buyerAccess.requireBuyer(request.buyerKey());
        String newKey = key(required(request.orderNo(), "Order No is required"));
        if (existsOrderNo(requestedBuyer, newKey, id)) {
            throw new OrderBomMprValidationException("Order No already exists for this Buyer: " + request.orderNo());
        }
        String currentBuyer = BuyerKeys.legacyDefault(entity.getBuyerKey());
        if (!currentBuyer.equals(requestedBuyer)
                && (bomRepository.countByOrderId(id) > 0 || mprRepository.existsByOrderId(id))) {
            throw new OrderBomMprValidationException(
                    "Cannot move an Order to another Buyer while BOM or MPR data exists"
            );
        }
        entity.setBuyerKey(requestedBuyer);
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
        if (!SalesOrder.STATUS_MPR_COMPLETED.equals(order.getStatus())) {
            order.setStatus("BOM_IN_PROGRESS");
            touch(order);
        }
    }

    public void markBomSubmitted(String orderId) {
        SalesOrder order = get(orderId);
        if (SalesOrder.STATUS_MPR_COMPLETED.equals(order.getStatus())) {
            return;
        }

        // Resubmitting an unused BOM must not move an order backwards when an
        // IN_PROGRESS MPR still exists for other BOMs/batches. MPR delete flows
        // remove the MPR document first, so they still correctly fall back to
        // BOM_SUBMITTED here.
        if (mprRepository.existsByOrderId(orderId)) {
            if (!SalesOrder.STATUS_MPR_IN_PROGRESS.equals(order.getStatus())) {
                order.setStatus(SalesOrder.STATUS_MPR_IN_PROGRESS);
                touch(order);
            }
            return;
        }

        order.setStatus("BOM_SUBMITTED");
        touch(order);
    }

    /**
     * Recalculate the order stage after a submitted BOM is returned to DRAFT.
     * - Existing MPR for other BOMs => keep MPR_IN_PROGRESS.
     * - Another submitted BOM remains => keep BOM_SUBMITTED.
     * - Otherwise at least this returned BOM is DRAFT => BOM_IN_PROGRESS.
     */
    public void markBomReturnedToDraft(String orderId) {
        SalesOrder order = get(orderId);
        if (SalesOrder.STATUS_MPR_COMPLETED.equals(order.getStatus())) {
            return;
        }

        String nextStatus;
        if (mprRepository.existsByOrderId(orderId)) {
            nextStatus = SalesOrder.STATUS_MPR_IN_PROGRESS;
        } else if (bomRepository.existsByOrderIdAndStatusIgnoreCase(orderId, "SUBMITTED")) {
            nextStatus = "BOM_SUBMITTED";
        } else {
            nextStatus = "BOM_IN_PROGRESS";
        }

        if (!nextStatus.equals(order.getStatus())) {
            order.setStatus(nextStatus);
            touch(order);
        }
    }

    public void markMprInProgress(String orderId) {
        SalesOrder order = get(orderId);
        order.setStatus(SalesOrder.STATUS_MPR_IN_PROGRESS);
        touch(order);
    }

    public void markMprCompleted(String orderId) {
        SalesOrder order = get(orderId);
        order.setStatus(SalesOrder.STATUS_MPR_COMPLETED);
        touch(order);
    }

    /** Compatibility for old callers while all clients move to IN_PROGRESS. */
    @Deprecated
    public void markMprDraft(String orderId) {
        markMprInProgress(orderId);
    }

    private void normalizeLegacyMprStatus(SalesOrder order) {
        if (order != null && "MPR_DRAFT".equalsIgnoreCase(order.getStatus())) {
            order.setStatus(SalesOrder.STATUS_MPR_IN_PROGRESS);
        }
    }

    private void touch(SalesOrder order) {
        order.setUpdatedAt(LocalDateTime.now());
        order.setUpdatedBy(RequestActor.current());
        orderRepository.save(order);
    }

    private String nextAvailableOrderNo(String buyerKey) {
        String sequenceName = ORDER_SEQUENCE_PREFIX + BuyerKeys.legacyDefault(buyerKey);
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = sequenceService.next(
                    sequenceName,
                    ORDER_NO_PREFIX,
                    () -> maxExistingOrderSequence(buyerKey)
            );
            if (!existsOrderNo(buyerKey, key(candidate), null)) {
                return candidate;
            }
        }
        throw new OrderBomMprValidationException("Unable to allocate the next Order No. Please try again.");
    }

    private long maxExistingOrderSequence(String buyerKey) {
        List<String> existingOrderNos = orderRepository.findByBuyerKey(buyerKey).stream()
                .map(SalesOrder::getOrderNo)
                .toList();
        return MasterDataSequentialKey.maxNumber(existingOrderNos, ORDER_NO_PREFIX);
    }

    private boolean existsOrderNo(String buyerKey, String orderNoKey, String excludedId) {
        return orderRepository.findByBuyerKeyAndOrderNoKey(buyerKey, orderNoKey)
                .filter(item -> !String.valueOf(item.getId()).equals(String.valueOf(excludedId)))
                .isPresent();
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
