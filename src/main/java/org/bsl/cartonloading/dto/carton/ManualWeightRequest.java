package org.bsl.cartonloading.dto.carton;

import java.math.BigDecimal;

public record ManualWeightRequest(BigDecimal weightKg, String reason) {
}
