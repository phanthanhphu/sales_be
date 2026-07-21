package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.util.Locale;

/** Central column map shared by BOM import and export. */
final class BomExcelLayout {

    enum Format { LEGACY, NEW, NEW_WITH_IMAGE }

    private final Format format;
    private final int dataStartOffset;
    private final int imageColumn;
    private final int materialGroupColumn;
    private final int materialTypeColumn;
    private final int sapCodeColumn;
    private final int detailNoColumn;
    private final int positionColumn;
    private final int positionDescriptionColumn;
    private final int positionDescriptionExtraColumn;
    private final int pieceCodeColumn;
    private final int dimensionXColumn;
    private final int dimensionYColumn;
    private final int quantityColumn;
    private final int directionColumn;
    private final int costingColumn;
    private final int costingUnitColumn;
    private final int detailConsumptionColumn;
    private final int consumptionMprColumn;
    private final int consumptionUnitColumn;
    private final int bomRemarkColumn;
    private final int firstColorColumn;
    private final int lastColorColumn;
    private final int additionalRemarkColumn;
    private final int colorNameOffset;
    private final int patternNumberOffset;
    private final int seasonOffset;
    private final int styleNumberOffset;
    private final int sequenceOffset;

    private BomExcelLayout(
            Format format,
            int dataStartOffset,
            int imageColumn,
            int materialGroupColumn,
            int materialTypeColumn,
            int sapCodeColumn,
            int detailNoColumn,
            int positionColumn,
            int positionDescriptionColumn,
            int positionDescriptionExtraColumn,
            int pieceCodeColumn,
            int dimensionXColumn,
            int dimensionYColumn,
            int quantityColumn,
            int directionColumn,
            int costingColumn,
            int costingUnitColumn,
            int detailConsumptionColumn,
            int consumptionMprColumn,
            int consumptionUnitColumn,
            int bomRemarkColumn,
            int firstColorColumn,
            int lastColorColumn,
            int additionalRemarkColumn,
            int colorNameOffset,
            int patternNumberOffset,
            int seasonOffset,
            int styleNumberOffset,
            int sequenceOffset
    ) {
        this.format = format;
        this.dataStartOffset = dataStartOffset;
        this.imageColumn = imageColumn;
        this.materialGroupColumn = materialGroupColumn;
        this.materialTypeColumn = materialTypeColumn;
        this.sapCodeColumn = sapCodeColumn;
        this.detailNoColumn = detailNoColumn;
        this.positionColumn = positionColumn;
        this.positionDescriptionColumn = positionDescriptionColumn;
        this.positionDescriptionExtraColumn = positionDescriptionExtraColumn;
        this.pieceCodeColumn = pieceCodeColumn;
        this.dimensionXColumn = dimensionXColumn;
        this.dimensionYColumn = dimensionYColumn;
        this.quantityColumn = quantityColumn;
        this.directionColumn = directionColumn;
        this.costingColumn = costingColumn;
        this.costingUnitColumn = costingUnitColumn;
        this.detailConsumptionColumn = detailConsumptionColumn;
        this.consumptionMprColumn = consumptionMprColumn;
        this.consumptionUnitColumn = consumptionUnitColumn;
        this.bomRemarkColumn = bomRemarkColumn;
        this.firstColorColumn = firstColorColumn;
        this.lastColorColumn = lastColorColumn;
        this.additionalRemarkColumn = additionalRemarkColumn;
        this.colorNameOffset = colorNameOffset;
        this.patternNumberOffset = patternNumberOffset;
        this.seasonOffset = seasonOffset;
        this.styleNumberOffset = styleNumberOffset;
        this.sequenceOffset = sequenceOffset;
    }

    static BomExcelLayout detect(Sheet sheet, int headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(headerRow);
        boolean imageColumn = false;
        for (int offset = 0; offset < 5; offset++) {
            if (normalize(text(sheet.getRow(headerRow + offset), 2, formatter, evaluator)).contains("IMAGE")) {
                imageColumn = true;
                break;
            }
        }

        // New-with-image: C=Image, D=SAP, N=CONS, O=NET CONSUMPTION, Q=Remarks, R..=colors, Z=Remarks.
        String n = normalize(text(header, 13, formatter, evaluator));
        String o = normalize(text(header, 14, formatter, evaluator));
        String q = normalize(text(header, 16, formatter, evaluator));
        if (imageColumn || (n.contains("CONS") && o.contains("NETCONSUMPTION") && q.contains("REMARKSONBOM"))) {
            int firstColor = 17;
            int additional = findTrailingRemarksColumn(sheet, headerRow, firstColor, formatter, evaluator);
            int lastColor = additional > firstColor ? additional - 1 : maxHeaderColumn(sheet, headerRow, 5, firstColor);
            return new BomExcelLayout(
                    Format.NEW_WITH_IMAGE, 5,
                    2, 0, 1, 3, 4, 5, 6, 7, 8,
                    10, 9, 11, 12,
                    -1, -1, 13, 14, 15, 16,
                    firstColor, lastColor, additional,
                    4, 0, 1, 2, 3
            );
        }

        // Previous new layout without Image: C=SAP, M=CONS, N=NET, P=Remarks, Q..=colors.
        String m = normalize(text(header, 12, formatter, evaluator));
        String p = normalize(text(header, 15, formatter, evaluator));
        boolean newFormat = m.contains("CONS") && n.contains("NETCONSUMPTION") && p.contains("REMARKSONBOM");
        if (!newFormat) {
            String sequence = text(sheet.getRow(headerRow + 3), 16, formatter, evaluator);
            String color = text(sheet.getRow(headerRow + 4), 16, formatter, evaluator);
            newFormat = isInteger(sequence) && hasText(color);
        }
        if (newFormat) {
            int firstColor = 16;
            int additional = findTrailingRemarksColumn(sheet, headerRow, firstColor, formatter, evaluator);
            int lastColor = additional > firstColor ? additional - 1 : maxHeaderColumn(sheet, headerRow, 5, firstColor);
            return new BomExcelLayout(
                    Format.NEW, 5,
                    -1, 0, 1, 2, 3, 4, 5, 6, 7,
                    9, 8, 10, 11,
                    -1, -1, 12, 13, 14, 15,
                    firstColor, lastColor, additional,
                    4, 0, 1, 2, 3
            );
        }

        // Legacy layout.
        int firstColor = 17;
        return new BomExcelLayout(
                Format.LEGACY, 3,
                -1, 0, 1, 2, 3, 4, 5, 6, 7,
                8, 9, 10, 11,
                12, 13, -1, 14, 15, 16,
                firstColor, maxHeaderColumn(sheet, headerRow, 3, firstColor), -1,
                0, 1, 2, -1, -1
        );
    }

    private static int findTrailingRemarksColumn(Sheet sheet, int headerRow, int firstColorColumn, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(headerRow);
        int max = Math.max(firstColorColumn, header == null ? firstColorColumn : header.getLastCellNum() - 1);
        for (int column = firstColorColumn + 1; column <= max; column++) {
            if ("REMARKS".equals(normalize(text(header, column, formatter, evaluator)))) return column;
        }
        return -1;
    }

    private static int maxHeaderColumn(Sheet sheet, int headerRow, int rowCount, int fallback) {
        int max = fallback;
        for (int offset = 0; offset < rowCount; offset++) {
            Row row = sheet.getRow(headerRow + offset);
            if (row != null && row.getLastCellNum() > 0) max = Math.max(max, row.getLastCellNum() - 1);
        }
        return max;
    }

    private static String text(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || column < 0) return "";
        var cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).replace('\n', ' ').trim();
    }

    private static boolean isInteger(String value) {
        if (!hasText(value)) return false;
        try { return new java.math.BigDecimal(value.replace(",", "").trim()).stripTrailingZeros().scale() <= 0; }
        catch (NumberFormatException ex) { return false; }
    }

    private static String normalize(String value) { return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT); }
    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    Format format() { return format; }
    boolean isNewFormat() { return format != Format.LEGACY; }
    boolean hasImageColumn() { return imageColumn >= 0; }
    int dataStartRow(int headerRow) { return headerRow + dataStartOffset; }
    int dataStartOffset() { return dataStartOffset; }
    int imageColumn() { return imageColumn; }
    int materialGroupColumn() { return materialGroupColumn; }
    int materialTypeColumn() { return materialTypeColumn; }
    int sapCodeColumn() { return sapCodeColumn; }
    int detailNoColumn() { return detailNoColumn; }
    int positionColumn() { return positionColumn; }
    int positionDescriptionColumn() { return positionDescriptionColumn; }
    int positionDescriptionExtraColumn() { return positionDescriptionExtraColumn; }
    int pieceCodeColumn() { return pieceCodeColumn; }
    int dimensionXColumn() { return dimensionXColumn; }
    int dimensionYColumn() { return dimensionYColumn; }
    int quantityColumn() { return quantityColumn; }
    int directionColumn() { return directionColumn; }
    int costingColumn() { return costingColumn; }
    int costingUnitColumn() { return costingUnitColumn; }
    int detailConsumptionColumn() { return detailConsumptionColumn; }
    int consumptionMprColumn() { return consumptionMprColumn; }
    int consumptionUnitColumn() { return consumptionUnitColumn; }
    int bomRemarkColumn() { return bomRemarkColumn; }
    int firstColorColumn() { return firstColorColumn; }
    int lastColorColumn() { return lastColorColumn; }
    int additionalRemarkColumn() { return additionalRemarkColumn; }
    int colorNameRow(int headerRow) { return headerRow + colorNameOffset; }
    int patternNumberRow(int headerRow) { return headerRow + patternNumberOffset; }
    int seasonRow(int headerRow) { return headerRow + seasonOffset; }
    int styleNumberRow(int headerRow) { return styleNumberOffset < 0 ? -1 : headerRow + styleNumberOffset; }
    int sequenceRow(int headerRow) { return sequenceOffset < 0 ? -1 : headerRow + sequenceOffset; }
    int lastTableColumn() { return Math.max(Math.max(bomRemarkColumn, additionalRemarkColumn), lastColorColumn); }
}
