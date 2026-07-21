package org.bsl.sales.dto;

import org.bsl.sales.model.BomLine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The BOM page maps every editable Excel detail column to this request. */
public record BomLineRequest(
        String materialType,
        String sapCode,
        String detailNo,
        String position,
        String positionDescription,
        String positionDescriptionExtra,
        String pieceCode,
        BigDecimal dimensionX,
        BigDecimal dimensionY,
        BigDecimal quantity,
        String direction,
        BigDecimal costing,
        String costingUnit,
        BigDecimal detailConsumption,
        BigDecimal consumptionNet,
        String consumptionUnit,
        String bomRemark,
        String additionalRemark,
        List<BomLineColorValueRequest> productColorValues,
        /** Legacy compatibility only. New FE sends productColorValues. */
        Map<String, String> colorValues,
        Boolean detailLine,
        Integer materialGroupNo
) {
    public BomLine toModel() {
        BomLine line = new BomLine();
        line.setMaterialType(materialType);
        line.setSapCode(sapCode);
        line.setDetailNo(detailNo);
        line.setPosition(position);
        line.setPositionDescription(positionDescription);
        line.setPositionDescriptionExtra(positionDescriptionExtra);
        line.setPieceCode(pieceCode);
        line.setDimensionX(dimensionX);
        line.setDimensionY(dimensionY);
        line.setQuantity(quantity);
        line.setDirection(direction);
        line.setCosting(costing);
        line.setCostingUnit(costingUnit);
        line.setDetailConsumption(detailConsumption);
        line.setConsumptionNet(consumptionNet);
        line.setConsumptionUnit(consumptionUnit);
        line.setBomRemark(bomRemark);
        line.setAdditionalRemark(additionalRemark);
        line.setProductColorValues(productColorValues == null
                ? new ArrayList<>()
                : productColorValues.stream().filter(item -> item != null).map(BomLineColorValueRequest::toModel).toList());
        line.setColorValues(colorValues == null ? new LinkedHashMap<>() : new LinkedHashMap<>(colorValues));
        line.setDetailLine(Boolean.TRUE.equals(detailLine));
        line.setMaterialGroupNo(materialGroupNo);
        return line;
    }
}
