package org.bsl.sales.service;

import org.bsl.sales.dto.DashboardSummaryResponse;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.model.MprLine;
import org.bsl.sales.model.SalesOrder;
import org.bsl.sales.repository.BomDocumentRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.bsl.sales.repository.SalesOrderRepository;
import org.bsl.sales.security.BuyerAccessService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DashboardService {
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final SalesOrderRepository orderRepository;
    private final BomDocumentRepository bomRepository;
    private final MprDocumentRepository mprRepository;
    private final BuyerAccessService buyerAccess;

    public DashboardService(
            SalesOrderRepository orderRepository,
            BomDocumentRepository bomRepository,
            MprDocumentRepository mprRepository,
            BuyerAccessService buyerAccess
    ) {
        this.orderRepository = orderRepository;
        this.bomRepository = bomRepository;
        this.mprRepository = mprRepository;
        this.buyerAccess = buyerAccess;
    }

    public DashboardSummaryResponse summary(
            String buyerKey,
            LocalDate fromDate,
            LocalDate toDate,
            String season,
            String style
    ) {
        String allowedBuyer = buyerAccess.requireBuyer(buyerKey);
        LocalDateTime from = fromDate == null ? null : fromDate.atStartOfDay();
        LocalDateTime to = toDate == null ? null : toDate.atTime(LocalTime.MAX);
        String seasonKey = key(season);
        String styleKey = key(style);

        List<SalesOrder> buyerOrders = orderRepository.findByBuyerKey(allowedBuyer);
        List<SalesOrder> dateScopedOrders = buyerOrders.stream()
                .filter(order -> inDateRange(order.getCreatedAt(), from, to))
                .toList();

        List<String> seasons = distinctSorted(dateScopedOrders, SalesOrder::getSeason);
        List<String> styles = distinctSorted(dateScopedOrders, SalesOrder::getStyle);

        List<SalesOrder> orders = dateScopedOrders.stream()
                .filter(order -> seasonKey == null || seasonKey.equals(key(order.getSeason())))
                .filter(order -> styleKey == null || styleKey.equals(key(order.getStyle())))
                .toList();

        List<String> orderIds = orders.stream().map(SalesOrder::getId).filter(Objects::nonNull).toList();
        List<BomDocument> boms = orderIds.isEmpty() ? List.of() : bomRepository.findByOrderIdIn(orderIds);
        List<MprDocument> mprs = orderIds.isEmpty() ? List.of() : mprRepository.findByOrderIdIn(orderIds);

        Map<String, List<BomDocument>> bomsByOrder = boms.stream()
                .filter(item -> item.getOrderId() != null)
                .collect(Collectors.groupingBy(BomDocument::getOrderId));
        Map<String, MprDocument> mprByOrder = mprs.stream()
                .filter(item -> item.getOrderId() != null)
                .collect(Collectors.toMap(MprDocument::getOrderId, Function.identity(), (left, right) -> right));
        Map<String, SalesOrder> orderById = orders.stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(SalesOrder::getId, Function.identity(), (left, right) -> left));

        long completed = 0;
        long notStarted = 0;
        long inProgress = 0;
        for (SalesOrder order : orders) {
            List<BomDocument> orderBoms = bomsByOrder.getOrDefault(order.getId(), List.of());
            MprDocument mpr = mprByOrder.get(order.getId());
            if (orderBoms.isEmpty()) {
                notStarted++;
            } else if (isCompleted(order, orderBoms, mpr)) {
                completed++;
            } else {
                inProgress++;
            }
        }

        long submittedBoms = boms.stream().filter(item -> "SUBMITTED".equalsIgnoreCase(item.getStatus())).count();
        long draftBoms = boms.size() - submittedBoms;

        long materialsNeedPurchase = 0;
        BigDecimal purchaseAmountUsd = BigDecimal.ZERO;
        Map<String, BigDecimal> purchaseByStyle = new LinkedHashMap<>();
        Map<String, MaterialAccumulator> materialByType = new LinkedHashMap<>();

        for (MprDocument mpr : mprs) {
            SalesOrder order = orderById.get(mpr.getOrderId());
            for (MprLine line : safe(mpr.getLines())) {
                if (line == null) continue;
                BigDecimal purchaseQty = zero(line.getPurchaseQuantity());
                BigDecimal priceUsd = zero(line.getMatPriceUsd());
                BigDecimal requiredQty = zero(line.getMatRequiredQuantity()).add(zero(line.getMatSampleQuantity()));
                BigDecimal stockQty = zero(line.getSapStockQuantity()).add(zero(line.getNonSapStockQuantity()));

                if (purchaseQty.signum() > 0) materialsNeedPurchase++;

                BigDecimal purchaseValue = purchaseQty.multiply(priceUsd);
                BigDecimal requiredValue = requiredQty.multiply(priceUsd);
                BigDecimal stockValue = stockQty.multiply(priceUsd);
                purchaseAmountUsd = purchaseAmountUsd.add(purchaseValue);

                String styleName = firstNonBlank(line.getStyleDescription(), order == null ? null : order.getStyle(), "Unspecified");
                purchaseByStyle.merge(styleName, purchaseValue, BigDecimal::add);

                String materialType = firstNonBlank(line.getMaterialType(), "Unspecified");
                materialByType.computeIfAbsent(materialType, ignored -> new MaterialAccumulator())
                        .add(requiredValue, stockValue, purchaseValue);
            }
        }

        List<DashboardSummaryResponse.PurchaseByStylePoint> stylePoints = purchaseByStyle.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .map(entry -> new DashboardSummaryResponse.PurchaseByStylePoint(entry.getKey(), money(entry.getValue())))
                .toList();

        List<DashboardSummaryResponse.MaterialValuePoint> materialPoints = materialByType.entrySet().stream()
                .sorted((left, right) -> right.getValue().requiredValue.compareTo(left.getValue().requiredValue))
                .limit(8)
                .map(entry -> new DashboardSummaryResponse.MaterialValuePoint(
                        entry.getKey(),
                        money(entry.getValue().requiredValue),
                        money(entry.getValue().stockValue),
                        money(entry.getValue().purchaseValue)
                ))
                .toList();

        return new DashboardSummaryResponse(
                allowedBuyer,
                orders.size(),
                completed,
                inProgress,
                notStarted,
                boms.size(),
                submittedBoms,
                draftBoms,
                materialsNeedPurchase,
                money(purchaseAmountUsd),
                seasons,
                styles,
                List.of(
                        new DashboardSummaryResponse.OrderProgressPoint("Completed", completed),
                        new DashboardSummaryResponse.OrderProgressPoint("In Progress", inProgress),
                        new DashboardSummaryResponse.OrderProgressPoint("Not Started", notStarted)
                ),
                ordersByMonth(orders, fromDate, toDate),
                List.of(
                        new DashboardSummaryResponse.BomProgressPoint("Submitted", submittedBoms),
                        new DashboardSummaryResponse.BomProgressPoint("Draft", draftBoms)
                ),
                stylePoints,
                materialPoints
        );
    }

    private List<DashboardSummaryResponse.OrdersByMonthPoint> ordersByMonth(
            List<SalesOrder> orders,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        YearMonth maxOrderMonth = orders.stream()
                .map(SalesOrder::getCreatedAt)
                .filter(Objects::nonNull)
                .map(YearMonth::from)
                .max(Comparator.naturalOrder())
                .orElse(YearMonth.now());

        YearMonth end = toDate == null ? maxOrderMonth : YearMonth.from(toDate);
        YearMonth start = fromDate == null ? end.minusMonths(11) : YearMonth.from(fromDate);
        if (start.isAfter(end)) start = end;

        Map<YearMonth, Long> counts = new TreeMap<>();
        YearMonth cursor = start;
        while (!cursor.isAfter(end)) {
            counts.put(cursor, 0L);
            cursor = cursor.plusMonths(1);
        }
        for (SalesOrder order : orders) {
            if (order.getCreatedAt() == null) continue;
            YearMonth month = YearMonth.from(order.getCreatedAt());
            if (month.isBefore(start) || month.isAfter(end)) continue;
            counts.computeIfPresent(month, (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new DashboardSummaryResponse.OrdersByMonthPoint(entry.getKey().format(MONTH_LABEL), entry.getValue()))
                .toList();
    }

    private boolean isCompleted(SalesOrder order, List<BomDocument> boms, MprDocument mpr) {
        boolean allBomsSubmitted = !boms.isEmpty()
                && boms.stream().allMatch(item -> "SUBMITTED".equalsIgnoreCase(item.getStatus()));
        boolean mprCompleted = (order != null && "MPR_COMPLETED".equalsIgnoreCase(order.getStatus()))
                || (mpr != null && "COMPLETED".equalsIgnoreCase(mpr.getStatus()));
        return allBomsSubmitted && mprCompleted;
    }

    private boolean inDateRange(LocalDateTime value, LocalDateTime from, LocalDateTime to) {
        if (from == null && to == null) return true;
        if (value == null) return false;
        if (from != null && value.isBefore(from)) return false;
        return to == null || !value.isAfter(to);
    }

    private List<String> distinctSorted(List<SalesOrder> orders, Function<SalesOrder, String> getter) {
        return orders.stream()
                .map(getter)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private BigDecimal money(BigDecimal value) {
        return zero(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String key(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }

    private static final class MaterialAccumulator {
        private BigDecimal requiredValue = BigDecimal.ZERO;
        private BigDecimal stockValue = BigDecimal.ZERO;
        private BigDecimal purchaseValue = BigDecimal.ZERO;

        private MaterialAccumulator add(BigDecimal required, BigDecimal stock, BigDecimal purchase) {
            requiredValue = requiredValue.add(required == null ? BigDecimal.ZERO : required);
            stockValue = stockValue.add(stock == null ? BigDecimal.ZERO : stock);
            purchaseValue = purchaseValue.add(purchase == null ? BigDecimal.ZERO : purchase);
            return this;
        }
    }
}
