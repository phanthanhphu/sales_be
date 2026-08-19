package org.bsl.cartonloading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PackingAllocationRequest(
        @Size(max = 200) String supplierName,
        @Size(max = 80) String supplierNumber,
        @Size(max = 120) String productionFacility,
        @Size(max = 120) String containerNumber,
        @Size(max = 80) String shipmentMode,
        LocalDate etd,
        LocalDate eta,
        @Size(max = 80) String poNumber,
        @Size(max = 80) String articleNumber,
        @Size(max = 80) String styleNumber,
        @Size(max = 500) String style,
        @Size(max = 160) String color,
        @Size(max = 80) String size,
        @DecimalMin(value = "0", inclusive = true) BigDecimal qtyPerCarton,
        @Size(max = 120) String invoiceNumber,
        @DecimalMin(value = "0", inclusive = true) BigDecimal totalPcs,
        @DecimalMin(value = "0", inclusive = true) BigDecimal totalCartons,
        @DecimalMin(value = "0", inclusive = true) BigDecimal pcsAir,
        @DecimalMin(value = "0", inclusive = true) BigDecimal cartonsAir,
        @DecimalMin(value = "0", inclusive = true) BigDecimal pcsSea,
        @DecimalMin(value = "0", inclusive = true) BigDecimal cartonsSea,
        @DecimalMin(value = "0", inclusive = true) BigDecimal cbmAir,
        @DecimalMin(value = "0", inclusive = true) BigDecimal kgAir,
        @Size(max = 80) String status,
        BigDecimal openPoQtyOverdel,
        @Size(max = 1000) String remarks,
        @Size(max = 120) String yoLotNumber,
        @DecimalMin(value = "0", inclusive = true) BigDecimal hCtn,
        @DecimalMin(value = "0", inclusive = true) BigDecimal cbmCtn
) {
}
