package org.bsl.cartonloading.dto.carton;

import java.math.BigDecimal;

public record CartonProgressResponse(
        String orderId,
        long plannedCartons,
        long completedCartons,
        long waitingCartons,
        long warningCartons,
        long remainingCartons,
        BigDecimal totalWeightKg
) {
}
