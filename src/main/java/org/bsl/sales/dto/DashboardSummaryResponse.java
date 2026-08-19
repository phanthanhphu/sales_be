package org.bsl.sales.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardSummaryResponse(
        String buyerKey,
        long totalOrders,
        long completedOrders,
        long inProgressOrders,
        long notStartedOrders,
        long totalBoms,
        long submittedBoms,
        long draftBoms,
        long materialsNeedPurchase,
        BigDecimal purchaseAmountUsd,
        List<String> seasons,
        List<String> styles,
        List<OrderProgressPoint> orderProgress,
        List<OrdersByMonthPoint> ordersByMonth,
        List<BomProgressPoint> bomProgress,
        List<PurchaseByStylePoint> purchaseByStyle,
        List<MaterialValuePoint> materialValues
) {
    public record OrderProgressPoint(String status, long count) {}
    public record OrdersByMonthPoint(String month, long count) {}
    public record BomProgressPoint(String status, long count) {}
    public record PurchaseByStylePoint(String style, BigDecimal purchaseAmountUsd) {}
    public record MaterialValuePoint(
            String materialType,
            BigDecimal requiredValueUsd,
            BigDecimal stockValueUsd,
            BigDecimal purchaseValueUsd
    ) {}
}
