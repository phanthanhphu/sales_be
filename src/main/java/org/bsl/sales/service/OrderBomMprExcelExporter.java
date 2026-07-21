package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.bsl.sales.model.BomAttachment;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomHeader;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomImage;
import org.bsl.sales.model.BomLineColorValue;
import org.bsl.sales.model.BomPacking;
import org.bsl.sales.model.BomProductColor;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.model.MprLine;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Exports BOMs from the original uploaded workbook whenever it is available.
 * This preserves the source sheet name, merged cells, row heights, column widths, colors, formulas and embedded pictures.
 */
@Service
public class OrderBomMprExcelExporter {

    /*
     * MPR export always starts from the supplied Excel template. This preserves
     * its sheet name, top notes, merged cells, font, color, borders, widths,
     * row heights, frozen header, filter, print layout, and number formats.
     */
    private static final String MPR_TEMPLATE_RESOURCE = "templates/MPR_Template.xlsx";
    private static final int MPR_HEADER_ROW = 4;       // Excel row 5
    private static final int MPR_DATA_START_ROW = 5;   // Excel row 6
    private static final int MPR_POUCH_COLUMN = 5;     // F, intentionally removed
    private static final int MPR_TEMPLATE_LAST_COLUMN = 34; // AI in source template
    private static final int MPR_LAST_COLUMN = 33;     // AH after POUCH is removed

    // A-AH after POUCH is removed.
    private static final int MPR_STYLE_COLOR_KEY_COL = 0;
    private static final int MPR_STYLE_DESCRIPTION_COL = 1;
    private static final int MPR_STYLE_COLOR_COL = 2;
    private static final int MPR_SHIP_TO_COL = 3;
    private static final int MPR_SALES_COMMENT_COL = 4;
    private static final int MPR_SAP_CODE_COL = 5;
    private static final int MPR_BOM_NO_COL = 6;
    private static final int MPR_MATERIAL_TYPE_COL = 7;
    private static final int MPR_DESCRIPTION_COL = 8;
    private static final int MPR_MATERIAL_COLOR_COL = 9;
    private static final int MPR_UNIT_COL = 10;
    private static final int MPR_YIELD_COL = 11;
    private static final int MPR_LOSS_COL = 12;
    private static final int MPR_TOTAL_YIELD_COL = 13;
    private static final int MPR_PO_QTY_COL = 14;
    private static final int MPR_REQUIRED_QTY_COL = 15;
    private static final int MPR_SAMPLE_QTY_COL = 16;
    private static final int MPR_SAMPLE_MATERIAL_QTY_COL = 17;
    private static final int MPR_MCD_STOCK_COL = 18;
    private static final int MPR_CMCD_STOCK_COL = 19;
    private static final int MPR_SAP_STOCK_COL = 20;
    private static final int MPR_NON_SAP_STOCK_COL = 21;
    private static final int MPR_PURCHASE_QTY_COL = 22;
    private static final int MPR_CURRENCY_COL = 23;
    private static final int MPR_PRICE_COL = 24;
    private static final int MPR_SHORT_SUPPLIER_COL = 25;
    private static final int MPR_VENDOR_CODE_COL = 26;
    private static final int MPR_VENDOR_NAME_COL = 27;
    private static final int MPR_MAT_CHARGER_COL = 28;
    private static final int MPR_EXCHANGE_RATE_COL = 29;
    private static final int MPR_PRICE_USD_COL = 30;
    private static final int MPR_AMOUNT_USD_COL = 31;
    private static final int MPR_DUE_DATE_COL = 32;
    private static final int MPR_TOTAL_STYLE_AMOUNT_COL = 33;

    private final BomFileStorageService fileStorage;

    public OrderBomMprExcelExporter(BomFileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    public byte[] exportBom(BomDocument bom) {
        if (hasText(bom.getSourceFileStoredName())) {
            try {
                Resource resource = fileStorage.load(bom.getSourceFileStoredName());
                try (InputStream input = resource.getInputStream();
                     Workbook workbook = WorkbookFactory.create(input);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                    Sheet sheet = findBomSheet(workbook);
                    patchOriginalTemplate(workbook, sheet, bom);
                    workbook.write(out);
                    return out.toByteArray();
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to export the original BOM workbook: " + ex.getMessage(), ex);
            }
        }

        return exportBomFallback(bom);
    }

    /** Fallback only for BOMs created manually without an uploaded Excel template. */
    private byte[] exportBomFallback(BomDocument bom) {
        try (Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("BOM Details");
            CellStyle headerStyle = headerStyle(workbook);
            int row = 0;
            BomHeader header = bom.getHeader() == null ? new BomHeader() : bom.getHeader();
            writeRow(sheet, row++, "BOM No", bom.getBomNo(), "BOM Name", bom.getBomName(), "Status", bom.getStatus());
            writeRow(sheet, row++, "Buyer", header.getBuyer(), "Rev. Stage", header.getRevStage(), "Comments", header.getComments());
            writeRow(sheet, row++, "Season", header.getSeason(), "Pattern Date", header.getPatternDate(), "Style Number", header.getStyleNumber());
            writeRow(sheet, row++, "Pattern Revised Date", header.getPatternRevisedDate(), "Pattern Number", header.getPatternNumber(), "Pattern Maker", header.getPatternMaker());
            writeRow(sheet, row++, "Style Name", header.getStyleName(), "Marker Date", header.getMarkerDate(), "Marker Maker", header.getMarkerMaker());
            writeRow(sheet, row++, "Factory Product", header.getFactoryProduct(), "BOM Maker", header.getBomMaker(), "Size", header.getSize());
            writeRow(sheet, row++, "BOM Date", header.getBomDate());
            row++;

            List<BomProductColor> productColors = productColors(bom);
            int firstColorColumn = 17; // R; C is the dedicated Image column.
            int additionalRemarkColumn = firstColorColumn + productColors.size();
            int headerRow = row;

            String[] standardHeaders = {
                    "No.", "MTR (Material Type)", "Image", "SAP CODE", "No.", "POSITION", "Position Description", "Position Description 2",
                    "P", "Y", "X", "Q.TY", "><", "CONS.", "NET CONSUMPTION", "UNIT", "REMARKS ON BOM"
            };
            Row mainHeader = sheet.createRow(row++);
            for (int i = 0; i < standardHeaders.length; i++) {
                Cell cell = mainHeader.createCell(i);
                cell.setCellValue(standardHeaders[i]);
                cell.setCellStyle(headerStyle);
            }
            setCell(getOrCreateCell(mainHeader, additionalRemarkColumn), "REMARKS");
            getOrCreateCell(mainHeader, additionalRemarkColumn).setCellStyle(headerStyle);

            Row seasonRow = sheet.createRow(row++);
            Row styleRow = sheet.createRow(row++);
            Row sequenceRow = sheet.createRow(row++);
            Row colorRow = sheet.createRow(row++);
            setCell(getOrCreateCell(colorRow, 14), "MK");
            setCell(getOrCreateCell(colorRow, 15), "UNIT");
            setCell(getOrCreateCell(styleRow, 2), "Image");

            for (int index = 0; index < productColors.size(); index++) {
                BomProductColor productColor = productColors.get(index);
                int column = firstColorColumn + index;
                setCell(getOrCreateCell(mainHeader, column), productColor.getPatternNumber());
                setCell(getOrCreateCell(seasonRow, column), productColor.getSeason());
                setCell(getOrCreateCell(styleRow, column), productColor.getStyleNumber());
                setCell(getOrCreateCell(sequenceRow, column), productColor.getSequence() == null ? index + 1 : productColor.getSequence());
                setCell(getOrCreateCell(colorRow, column), productColor.getColorName());
                for (Row headerRowItem : List.of(mainHeader, seasonRow, styleRow, sequenceRow, colorRow)) {
                    getOrCreateCell(headerRowItem, column).setCellStyle(headerStyle);
                }
            }
            for (int column : List.of(14, 15)) getOrCreateCell(colorRow, column).setCellStyle(headerStyle);
            getOrCreateCell(styleRow, 2).setCellStyle(headerStyle);

            for (BomLine line : safe(bom.getCoreLines())) {
                row = writeFallbackLine(workbook, sheet, row, line, bom, productColors, firstColorColumn, additionalRemarkColumn);
            }
            for (BomPacking packing : safe(bom.getPackings())) {
                Row packingRow = sheet.createRow(row++);
                Cell cell = packingRow.createCell(0);
                cell.setCellValue(packing.getPackingName());
                cell.setCellStyle(headerStyle);
                for (BomLine line : safe(packing.getLines())) {
                    row = writeFallbackLine(workbook, sheet, row, line, bom, productColors, firstColorColumn, additionalRemarkColumn);
                }
            }

            applyAllBorders(
                    workbook,
                    sheet,
                    headerRow,
                    Math.max(headerRow, row - 1),
                    0,
                    additionalRemarkColumn
            );

            sheet.createFreezePane(0, headerRow + 5);
            for (int c = 0; c <= additionalRemarkColumn; c++) sheet.autoSizeColumn(c);
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to export BOM", ex);
        }
    }

    private void patchOriginalTemplate(Workbook workbook, Sheet sheet, BomDocument bom) throws Exception {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        DataFormatter formatter = new DataFormatter(Locale.US);
        int headerRow = findColumnHeaderRow(sheet, formatter, evaluator);
        if (headerRow < 0) throw new IllegalStateException("Cannot find BOM detail header in the original workbook");

        BomExcelLayout layout = BomExcelLayout.detect(sheet, headerRow, formatter, evaluator);
        Map<String, Integer> colorColumns = findColorColumns(sheet, headerRow, layout, formatter, evaluator);
        patchHeader(sheet, bom.getHeader(), formatter, evaluator);
        patchProductColorHeaders(sheet, headerRow, layout, bom, colorColumns);
        removeReplacedOrDeletedLineImages(sheet, bom, layout);

        for (Integer deletedRow : safe(bom.getDeletedSourceRows())) {
            if (deletedRow != null && deletedRow > 0) {
                clearLineRow(sheet, deletedRow - 1, layout, colorColumns.values());
            }
        }

        for (BomLine line : safe(bom.getCoreLines())) {
            if (line.getSourceRowNumber() != null) patchLineAt(sheet, line.getSourceRowNumber() - 1, line, bom, layout, colorColumns);
        }
        for (BomPacking packing : safe(bom.getPackings())) {
            for (BomLine line : safe(packing.getLines())) {
                if (line.getSourceRowNumber() != null) patchLineAt(sheet, line.getSourceRowNumber() - 1, line, bom, layout, colorColumns);
            }
        }

        appendNewLines(sheet, headerRow, layout, bom, colorColumns);
        embedPrimaryLineImages(workbook, sheet, bom, layout);
        embedManualImages(workbook, sheet, bom, layout, colorColumns);

        // Finish only the detected BOM detail table with a complete grid.
        // Blank cells inside the table receive borders; all cells outside the
        // table (including the BOM header-information area) remain untouched.
        applyAllBordersToBomTable(workbook, sheet, headerRow, layout, bom, colorColumns);
    }

    private void patchHeader(Sheet sheet, BomHeader header, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (header == null) return;
        patchHeaderValue(sheet, "BUYER", header.getBuyer(), formatter, evaluator);
        patchHeaderValue(sheet, "REV. STAGE", header.getRevStage(), formatter, evaluator);
        patchHeaderValue(sheet, "COMMENTS", header.getComments(), formatter, evaluator);
        patchHeaderValue(sheet, "SEASON", header.getSeason(), formatter, evaluator);
        patchHeaderValue(sheet, "PATTERN DATE", header.getPatternDate(), formatter, evaluator);
        patchHeaderValue(sheet, "STYLE NUMBER", header.getStyleNumber(), formatter, evaluator);
        patchHeaderValue(sheet, "PATTERN REVISED DATE", header.getPatternRevisedDate(), formatter, evaluator);
        patchHeaderValue(sheet, "PATTERN NUMBER", header.getPatternNumber(), formatter, evaluator);
        patchHeaderValue(sheet, "PATTERN MAKER", header.getPatternMaker(), formatter, evaluator);
        patchHeaderValue(sheet, "STYLE NAME", header.getStyleName(), formatter, evaluator);
        patchHeaderValue(sheet, "MARKER DATE", header.getMarkerDate(), formatter, evaluator);
        patchHeaderValue(sheet, "MARKER MAKER", header.getMarkerMaker(), formatter, evaluator);
        patchHeaderValue(sheet, "FACTORY PRODUCT", header.getFactoryProduct(), formatter, evaluator);
        patchHeaderValue(sheet, "BOM MAKER", header.getBomMaker(), formatter, evaluator);
        patchHeaderValue(sheet, "SIZE", header.getSize(), formatter, evaluator);
        patchHeaderValue(sheet, "BOM DATE", header.getBomDate(), formatter, evaluator);
    }

    private void patchHeaderValue(Sheet sheet, String label, String value, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (value == null) return;
        String expected = normalize(label);

        for (int rowIndex = 0; rowIndex <= Math.min(sheet.getLastRowNum(), 18); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            for (int column = 0; column < Math.min(Math.max(0, row.getLastCellNum()), 30); column++) {
                if (!normalize(text(row, column, formatter, evaluator)).contains(expected)) continue;

                Cell target = firstExistingValueCell(
                        row,
                        column + 1,
                        column + 4,
                        formatter,
                        evaluator
                );
                if (target == null) {
                    target = firstExistingValueCellBelow(sheet, rowIndex, column, formatter, evaluator);
                }
                if (target == null) target = getOrCreateCell(row, column + 1);
                setCell(target, value);
                return;
            }
        }
    }

    private Cell firstExistingValueCell(
            Row row,
            int fromColumn,
            int toColumn,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        for (int column = fromColumn; column <= toColumn; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null || cell.getCellType() == CellType.BLANK) continue;

            String existingValue = formatter.formatCellValue(cell, evaluator).trim();
            // Never use the next header label as the value cell of the current
            // header. For example, Rev. Stage must stop before Comments:.
            if (looksLikeHeaderLabel(existingValue)) break;
            return cell;
        }
        return null;
    }

    private boolean looksLikeHeaderLabel(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.endsWith(":")) return true;
        String normalized = normalize(clean);
        return normalized.equals("BUYER")
                || normalized.equals("REVSTAGE")
                || normalized.equals("COMMENTS")
                || normalized.equals("SEASON")
                || normalized.contains("PATTERNDATE")
                || normalized.equals("OLDPATTERNDATE")
                || normalized.equals("STYLENUMBER")
                || normalized.equals("PATTERNREVISEDDATE")
                || normalized.equals("PATTERNNUMBER")
                || normalized.equals("PATTERNMAKER")
                || normalized.equals("STYLENAME")
                || normalized.equals("MARKERDATE")
                || normalized.equals("MARKERMAKER")
                || normalized.equals("FACTORYPRODUCT")
                || normalized.equals("BOMMAKER")
                || normalized.equals("SIZE")
                || normalized.equals("BOMDATE");
    }

    private Cell firstExistingValueCellBelow(
            Sheet sheet,
            int labelRow,
            int labelColumn,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        for (int offset = 1; offset <= 2; offset++) {
            Row row = sheet.getRow(labelRow + offset);
            if (row == null) continue;
            Cell cell = row.getCell(labelColumn, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null || cell.getCellType() == CellType.BLANK) continue;
            String value = formatter.formatCellValue(cell, evaluator).trim();
            if (!value.endsWith(":")) return cell;
        }
        return null;
    }

    private void patchLineAt(
            Sheet sheet,
            int rowIndex,
            BomLine line,
            BomDocument bom,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns
    ) {
        if (rowIndex < 0) return;
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);

        setCell(getOrCreateCell(row, layout.materialGroupColumn()), line.getMaterialGroupNo());
        setCell(getOrCreateCell(row, layout.materialTypeColumn()), line.getMaterialType());
        setCell(getOrCreateCell(row, layout.sapCodeColumn()), line.getSapCode());
        setCell(getOrCreateCell(row, layout.detailNoColumn()), line.getDetailNo());
        setCell(getOrCreateCell(row, layout.positionColumn()), line.getPosition());
        setCell(getOrCreateCell(row, layout.positionDescriptionColumn()), line.getPositionDescription());
        setCell(getOrCreateCell(row, layout.positionDescriptionExtraColumn()), line.getPositionDescriptionExtra());
        setCell(getOrCreateCell(row, layout.pieceCodeColumn()), line.getPieceCode());
        setCell(getOrCreateCell(row, layout.dimensionXColumn()), line.getDimensionX());
        setCell(getOrCreateCell(row, layout.dimensionYColumn()), line.getDimensionY());
        setCell(getOrCreateCell(row, layout.quantityColumn()), line.getQuantity());
        setCell(getOrCreateCell(row, layout.directionColumn()), line.getDirection());

        if (layout.costingColumn() >= 0) {
            setCell(getOrCreateCell(row, layout.costingColumn()), line.getCosting());
        }
        if (layout.costingUnitColumn() >= 0) {
            setCell(getOrCreateCell(row, layout.costingUnitColumn()), line.getCostingUnit());
        }
        if (layout.detailConsumptionColumn() >= 0) {
            setCell(getOrCreateCell(row, layout.detailConsumptionColumn()), line.getDetailConsumption());
        }

        setCell(getOrCreateCell(row, layout.consumptionMprColumn()), line.getConsumptionNet());
        setCell(getOrCreateCell(row, layout.consumptionUnitColumn()), line.getConsumptionUnit());
        setCell(getOrCreateCell(row, layout.bomRemarkColumn()), line.getBomRemark());
        if (layout.additionalRemarkColumn() >= 0) {
            setCell(getOrCreateCell(row, layout.additionalRemarkColumn()), line.getAdditionalRemark());
        }

        for (BomProductColor productColor : productColors(bom)) {
            Integer column = productColorColumn(productColor, layout, colorColumns);
            if (column == null) continue;
            setCell(getOrCreateCell(row, column), productColorValue(line, productColor));
        }
    }

    /** Updates Product Color header rows in either the legacy or new workbook format. */
    private void patchProductColorHeaders(
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            BomDocument bom,
            Map<String, Integer> colorColumns
    ) {
        for (BomProductColor productColor : productColors(bom)) {
            Integer column = productColorColumn(productColor, layout, colorColumns);
            if (column == null) continue;

            setCell(getOrCreateCell(getOrCreateRow(sheet, layout.colorNameRow(headerRow)), column), productColor.getColorName());
            setCell(getOrCreateCell(getOrCreateRow(sheet, layout.patternNumberRow(headerRow)), column), productColor.getPatternNumber());
            setCell(getOrCreateCell(getOrCreateRow(sheet, layout.seasonRow(headerRow)), column), productColor.getSeason());

            if (layout.styleNumberRow(headerRow) >= 0) {
                setCell(getOrCreateCell(getOrCreateRow(sheet, layout.styleNumberRow(headerRow)), column), productColor.getStyleNumber());
            }
            if (layout.sequenceRow(headerRow) >= 0) {
                setCell(getOrCreateCell(getOrCreateRow(sheet, layout.sequenceRow(headerRow)), column), productColor.getSequence());
            }
        }
    }

    /** Returns a value through the stable Product Color id, with old colorValues as a fallback. */
    private String productColorValue(BomLine line, BomProductColor productColor) {
        if (line == null || productColor == null) return null;

        for (BomLineColorValue value : safe(line.getProductColorValues())) {
            if (value != null && productColor.getId() != null && productColor.getId().equals(value.getProductColorId())) {
                return value.getValue();
            }
        }

        if (line.getColorValues() != null) {
            for (Map.Entry<String, String> entry : line.getColorValues().entrySet()) {
                if (normalize(entry.getKey()).equals(normalize(productColor.getColorName()))) return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Imported Product Colors keep their original source column, which also
     * preserves two columns that happen to have the same visible color name.
     * Old/manual records fall back to the readable color-name map.
     */
    private Integer productColorColumn(
            BomProductColor productColor,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns
    ) {
        if (productColor == null) return null;
        Integer sourceColumn = productColor.getSourceColumnIndex();
        if (sourceColumn != null
                && sourceColumn >= layout.firstColorColumn()
                && sourceColumn <= layout.lastColorColumn()) {
            return sourceColumn;
        }
        return colorColumns.get(productColor.getColorName());
    }

    private String resolveProductColorName(BomDocument bom, String productColorId, String legacyColorKey) {
        if (productColorId != null && !productColorId.isBlank()) {
            for (BomProductColor productColor : productColors(bom)) {
                if (productColorId.equals(productColor.getId())) return productColor.getColorName();
            }
        }
        return legacyColorKey == null ? "" : legacyColorKey;
    }

    private BomProductColor findProductColor(BomDocument bom, String productColorId, String legacyColorKey) {
        if (hasText(productColorId)) {
            for (BomProductColor productColor : productColors(bom)) {
                if (productColor != null && productColorId.equals(productColor.getId())) return productColor;
            }
        }
        if (hasText(legacyColorKey)) {
            for (BomProductColor productColor : productColors(bom)) {
                if (productColor != null && normalize(legacyColorKey).equals(normalize(productColor.getColorName()))) {
                    return productColor;
                }
            }
        }
        return null;
    }

    private List<BomProductColor> productColors(BomDocument bom) {
        if (bom.getProductColors() != null && !bom.getProductColors().isEmpty()) return bom.getProductColors();
        List<BomProductColor> legacy = new ArrayList<>();
        for (String color : safe(bom.getColors())) {
            BomProductColor item = new BomProductColor();
            item.setColorName(color);
            legacy.add(item);
        }
        return legacy;
    }

    private void clearLineRow(Sheet sheet, int rowIndex, BomExcelLayout layout, Collection<Integer> colorColumns) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) return;
        for (int column = 0; column <= layout.bomRemarkColumn(); column++) clearCell(row, column);
        // Clear the complete layout-defined color range, not only a name-based
        // map, so duplicate Product Color names are also cleared correctly.
        for (int column = layout.firstColorColumn(); column <= layout.lastColorColumn(); column++) clearCell(row, column);
        for (Integer column : colorColumns) clearCell(row, column);
        if (layout.additionalRemarkColumn() >= 0) clearCell(row, layout.additionalRemarkColumn());
    }

    private void appendNewLines(
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            BomDocument bom,
            Map<String, Integer> colorColumns
    ) {
        List<BomLine> newCoreLines = safe(bom.getCoreLines()).stream().filter(line -> line.getSourceRowNumber() == null).toList();
        if (!newCoreLines.isEmpty()) {
            int insertion = firstPackingRow(sheet, layout.dataStartRow(headerRow));
            if (insertion < 0) insertion = sheet.getLastRowNum() + 1;
            for (BomLine line : newCoreLines) {
                insertStyledLine(sheet, insertion, Math.max(layout.dataStartRow(headerRow), insertion - 1), line, bom, layout, colorColumns);
                insertion++;
            }
        }

        for (BomPacking packing : safe(bom.getPackings())) {
            List<BomLine> newLines = safe(packing.getLines()).stream().filter(line -> line.getSourceRowNumber() == null).toList();
            if (newLines.isEmpty()) continue;

            int packingTitleRow = findPackingRow(sheet, packing.getPackingName());
            if (packingTitleRow < 0) {
                packingTitleRow = appendPackingTitle(sheet, packing.getPackingName(), headerRow, layout);
            }

            int nextPacking = firstPackingRow(sheet, packingTitleRow + 1);
            int insertion = nextPacking < 0 ? sheet.getLastRowNum() + 1 : nextPacking;
            int styleRow = Math.max(packingTitleRow + 1, insertion - 1);
            for (BomLine line : newLines) {
                insertStyledLine(sheet, insertion, styleRow, line, bom, layout, colorColumns);
                insertion++;
                styleRow++;
            }
        }
    }

    private int appendPackingTitle(Sheet sheet, String packingName, int headerRow, BomExcelLayout layout) {
        int newRowIndex = sheet.getLastRowNum() + 1;
        int templateRow = firstPackingRow(sheet, layout.dataStartRow(headerRow));
        if (templateRow >= 0) copyRowStyle(sheet, templateRow, newRowIndex, Math.max(layout.lastTableColumn(), sheet.getRow(templateRow).getLastCellNum()));
        Row row = sheet.getRow(newRowIndex);
        if (row == null) row = sheet.createRow(newRowIndex);
        setCell(getOrCreateCell(row, 0), packingName);
        return newRowIndex;
    }

    private void insertStyledLine(
            Sheet sheet,
            int insertionRow,
            int templateRow,
            BomLine line,
            BomDocument bom,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns
    ) {
        int lastRow = sheet.getLastRowNum();
        if (insertionRow <= lastRow) sheet.shiftRows(insertionRow, lastRow, 1, true, false);
        copyRowStyle(sheet, Math.min(Math.max(0, templateRow), sheet.getLastRowNum()), insertionRow, maxTableColumn(layout, colorColumns));
        patchLineAt(sheet, insertionRow, line, bom, layout, colorColumns);
        line.setSourceRowNumber(insertionRow + 1);
    }

    private int maxTableColumn(BomExcelLayout layout, Map<String, Integer> colorColumns) {
        return Math.max(layout.lastTableColumn(), colorColumns.values().stream().mapToInt(Integer::intValue).max().orElse(layout.lastTableColumn()));
    }

    private void copyRowStyle(Sheet sheet, int sourceRowIndex, int targetRowIndex, int maxColumn) {
        Row source = sheet.getRow(sourceRowIndex);
        Row target = sheet.getRow(targetRowIndex);
        if (target == null) target = sheet.createRow(targetRowIndex);
        if (source == null) return;

        target.setHeight(source.getHeight());
        target.setZeroHeight(source.getZeroHeight());
        for (int column = 0; column <= maxColumn; column++) {
            Cell sourceCell = source.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            Cell targetCell = getOrCreateCell(target, column);
            if (sourceCell != null) {
                targetCell.setCellStyle(sourceCell.getCellStyle());
                if (sourceCell.getHyperlink() != null) targetCell.setHyperlink(sourceCell.getHyperlink());
            }
            clearCell(target, column);
        }
    }

    /**
     * Applies borders only inside the BOM detail table. The table starts at
     * the detected detail-header row and ends at the final active BOM/packing
     * row. Header information above the table and cells outside the detected
     * table columns are intentionally left untouched.
     *
     * Blank cells inside that rectangle are physically created so they also
     * receive borders. Existing fills, fonts, alignment and number formats
     * are preserved.
     */
    private void applyAllBordersToBomTable(
            Workbook workbook,
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            BomDocument bom,
            Map<String, Integer> colorColumns
    ) {
        int firstTableColumn = Math.max(0, layout.materialGroupColumn());
        int lastTableColumn = maxTableColumn(layout, colorColumns);
        int lastTableRow = layout.dataStartRow(headerRow) - 1;

        // Only active rows determine the end of the table. Deleted historical
        // source rows must not extend the bordered area into blank/outside rows.
        for (BomLine line : allLines(bom)) {
            if (line != null && line.getSourceRowNumber() != null && line.getSourceRowNumber() > 0) {
                lastTableRow = Math.max(lastTableRow, line.getSourceRowNumber() - 1);
            }
        }
        for (BomPacking packing : safe(bom.getPackings())) {
            if (packing == null || !hasText(packing.getPackingName())) continue;
            int packingRow = findPackingRow(sheet, packing.getPackingName());
            if (packingRow >= 0) lastTableRow = Math.max(lastTableRow, packingRow);
        }

        applyAllBorders(
                workbook,
                sheet,
                headerRow,
                lastTableRow,
                firstTableColumn,
                lastTableColumn
        );
    }

    private void applyAllBorders(
            Workbook workbook,
            Sheet sheet,
            int firstRow,
            int lastRow,
            int firstColumn,
            int lastColumn
    ) {
        if (firstRow < 0 || lastRow < firstRow || firstColumn < 0 || lastColumn < firstColumn) return;

        Map<Integer, CellStyle> borderedStyles = new LinkedHashMap<>();
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = getOrCreateRow(sheet, rowIndex);
            for (int column = firstColumn; column <= lastColumn; column++) {
                Cell cell = getOrCreateCell(row, column);
                CellStyle sourceStyle = cell.getCellStyle();

                // A missing cell normally has the default style. Prefer the
                // column style when the template defines one.
                if (sourceStyle.getIndex() == 0) {
                    CellStyle columnStyle = sheet.getColumnStyle(column);
                    if (columnStyle != null && columnStyle.getIndex() != 0) {
                        sourceStyle = columnStyle;
                    }
                }

                if (hasAllBorders(sourceStyle)) {
                    cell.setCellStyle(sourceStyle);
                    continue;
                }

                int styleKey = sourceStyle.getIndex();
                CellStyle borderedStyle = borderedStyles.get(styleKey);
                if (borderedStyle == null) {
                    borderedStyle = workbook.createCellStyle();
                    borderedStyle.cloneStyleFrom(sourceStyle);
                    ensureAllBorders(borderedStyle);
                    borderedStyles.put(styleKey, borderedStyle);
                }
                cell.setCellStyle(borderedStyle);
            }
        }
    }

    private boolean hasAllBorders(CellStyle style) {
        return style.getBorderTop() != BorderStyle.NONE
                && style.getBorderBottom() != BorderStyle.NONE
                && style.getBorderLeft() != BorderStyle.NONE
                && style.getBorderRight() != BorderStyle.NONE;
    }

    private void ensureAllBorders(CellStyle style) {
        short black = IndexedColors.BLACK.getIndex();
        if (style.getBorderTop() == BorderStyle.NONE) {
            style.setBorderTop(BorderStyle.THIN);
            style.setTopBorderColor(black);
        }
        if (style.getBorderBottom() == BorderStyle.NONE) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBottomBorderColor(black);
        }
        if (style.getBorderLeft() == BorderStyle.NONE) {
            style.setBorderLeft(BorderStyle.THIN);
            style.setLeftBorderColor(black);
        }
        if (style.getBorderRight() == BorderStyle.NONE) {
            style.setBorderRight(BorderStyle.THIN);
            style.setRightBorderColor(black);
        }
    }

    private int firstPackingRow(Sheet sheet, int startRow) {
        for (int rowIndex = Math.max(0, startRow); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Cell cell = row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && normalize(new DataFormatter().formatCellValue(cell)).startsWith("PACKING")) return rowIndex;
        }
        return -1;
    }

    private int findPackingRow(Sheet sheet, String packingName) {
        String wanted = normalize(packingName);
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Cell cell = row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && normalize(new DataFormatter().formatCellValue(cell)).equals(wanted)) return rowIndex;
        }
        return -1;
    }

    private void removeReplacedOrDeletedLineImages(Sheet sheet, BomDocument bom, BomExcelLayout layout) {
        if (!layout.hasImageColumn() || !(sheet instanceof org.apache.poi.xssf.usermodel.XSSFSheet xssfSheet)) return;
        org.apache.poi.xssf.usermodel.XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
        if (drawing == null) return;

        java.util.Set<Integer> rows = new java.util.LinkedHashSet<>();
        for (Integer deleted : safe(bom.getDeletedSourceRows())) {
            if (deleted != null && deleted > 0) rows.add(deleted - 1);
        }
        for (BomLine line : allLines(bom)) {
            if (line == null || line.getSourceRowNumber() == null) continue;
            BomImage image = line.getPrimaryImage();
            if (image == null || !image.isImportedFromExcel()) rows.add(line.getSourceRowNumber() - 1);
        }
        if (rows.isEmpty()) return;

        var ctDrawing = drawing.getCTDrawing();
        for (int index = ctDrawing.sizeOfTwoCellAnchorArray() - 1; index >= 0; index--) {
            var anchor = ctDrawing.getTwoCellAnchorArray(index);
            if (anchor.getFrom() != null
                    && anchor.getFrom().getCol() == layout.imageColumn()
                    && rows.contains(anchor.getFrom().getRow())) {
                ctDrawing.removeTwoCellAnchor(index);
            }
        }
        for (int index = ctDrawing.sizeOfOneCellAnchorArray() - 1; index >= 0; index--) {
            var anchor = ctDrawing.getOneCellAnchorArray(index);
            if (anchor.getFrom() != null
                    && anchor.getFrom().getCol() == layout.imageColumn()
                    && rows.contains(anchor.getFrom().getRow())) {
                ctDrawing.removeOneCellAnchor(index);
            }
        }
    }

    private void embedPrimaryLineImages(Workbook workbook, Sheet sheet, BomDocument bom, BomExcelLayout layout) {
        if (!layout.hasImageColumn()) return;
        for (BomLine line : allLines(bom)) {
            BomImage image = line == null ? null : line.getPrimaryImage();
            if (image == null || line.getSourceRowNumber() == null) continue;
            // The untouched image imported from the source workbook already exists in its drawing layer.
            if (image.isImportedFromExcel()) continue;

            StoredBomImage storedImage = exportableStoredImage(image);
            if (storedImage == null) continue;
            try (InputStream input = fileStorage.load(storedImage.storedFileName()).getInputStream()) {
                byte[] bytes = input.readAllBytes();
                int pictureIndex = workbook.addPicture(bytes, storedImage.pictureType());
                Drawing<?> drawing = sheet.createDrawingPatriarch();
                ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
                int row = Math.max(0, line.getSourceRowNumber() - 1);
                anchor.setCol1(layout.imageColumn());
                anchor.setCol2(layout.imageColumn() + 1);
                anchor.setRow1(row);
                anchor.setRow2(row + 1);
                drawing.createPicture(anchor, pictureIndex);
                if (sheet.getRow(row) != null && sheet.getRow(row).getHeightInPoints() < 54f) {
                    sheet.getRow(row).setHeightInPoints(54f);
                }
                if (sheet.getColumnWidth(layout.imageColumn()) < 16 * 256) {
                    sheet.setColumnWidth(layout.imageColumn(), 16 * 256);
                }
            } catch (Exception ignored) {
                // Optional preview failure must not block the workbook export.
            }
        }
    }

    private List<BomLine> allLines(BomDocument bom) {
        List<BomLine> result = new ArrayList<>(safe(bom.getCoreLines()));
        for (BomPacking packing : safe(bom.getPackings())) result.addAll(safe(packing.getLines()));
        return result;
    }

    private void embedManualImages(Workbook workbook, Sheet sheet, BomDocument bom, BomExcelLayout layout, Map<String, Integer> colorColumns) {
        for (BomAttachment attachment : allAttachments(bom)) {
            if (attachment.isImportedFromExcel() || !isImage(attachment)) continue;
            if (!hasText(attachment.getStoredFileName())) continue;

            try (InputStream input = fileStorage.load(attachment.getStoredFileName()).getInputStream()) {
                byte[] data = input.readAllBytes();
                byte[] pictureBytes = normalizePictureBytes(attachment, data);
                int pictureIndex = workbook.addPicture(pictureBytes, pictureType(attachment));
                Drawing<?> drawing = sheet.createDrawingPatriarch();
                ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();

                int column = Math.max(layout.lastTableColumn() + 1, 26);
                int row = 0;
                String scope = attachment.getScope() == null ? "BOM" : attachment.getScope().toUpperCase(Locale.ROOT);

                BomProductColor attachmentProductColor = findProductColor(bom, attachment.getProductColorId(), attachment.getColorKey());
                String productColorName = attachmentProductColor == null
                        ? resolveProductColorName(bom, attachment.getProductColorId(), attachment.getColorKey())
                        : attachmentProductColor.getColorName();
                Integer productColorColumn = attachmentProductColor != null && attachmentProductColor.getSourceColumnIndex() != null
                        ? attachmentProductColor.getSourceColumnIndex()
                        : colorColumns.get(productColorName);
                if ("COLOR".equals(scope) && productColorColumn != null) {
                    column = productColorColumn;
                    row = 0;
                } else if ("PACKING".equals(scope)) {
                    BomPacking packing = findPackingById(bom, attachment.getPackingId());
                    int packingRow = packing == null ? -1 : findPackingRow(sheet, packing.getPackingName());
                    row = packingRow >= 0 ? packingRow : sheet.getLastRowNum();
                } else if ("LINE".equals(scope)) {
                    BomLine line = findLineById(bom, attachment.getLineId());
                    row = line != null && line.getSourceRowNumber() != null ? line.getSourceRowNumber() - 1 : sheet.getLastRowNum();
                }

                anchor.setCol1(column);
                anchor.setCol2(column + 3);
                anchor.setRow1(Math.max(0, row));
                anchor.setRow2(Math.max(1, row + 7));
                drawing.createPicture(anchor, pictureIndex);
            } catch (Exception ignored) {
                // A malformed optional image must not prevent the BOM workbook from being exported.
            }
        }
    }

    private List<BomAttachment> allAttachments(BomDocument bom) {
        List<BomAttachment> result = new ArrayList<>(safe(bom.getAttachments()));
        for (BomLine line : safe(bom.getCoreLines())) result.addAll(safe(line.getAttachments()));
        for (BomPacking packing : safe(bom.getPackings())) {
            result.addAll(safe(packing.getAttachments()));
            for (BomLine line : safe(packing.getLines())) result.addAll(safe(line.getAttachments()));
        }
        return result;
    }

    private BomPacking findPackingById(BomDocument bom, String packingId) {
        return safe(bom.getPackings()).stream().filter(item -> packingId != null && packingId.equals(item.getId())).findFirst().orElse(null);
    }

    private BomLine findLineById(BomDocument bom, String lineId) {
        for (BomLine line : safe(bom.getCoreLines())) if (lineId != null && lineId.equals(line.getId())) return line;
        for (BomPacking packing : safe(bom.getPackings())) {
            for (BomLine line : safe(packing.getLines())) if (lineId != null && lineId.equals(line.getId())) return line;
        }
        return null;
    }

    private boolean isImage(BomAttachment attachment) {
        String type = attachment.getContentType() == null ? "" : attachment.getContentType().toLowerCase(Locale.ROOT);
        String name = attachment.getOriginalFileName() == null ? "" : attachment.getOriginalFileName().toLowerCase(Locale.ROOT);
        return type.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".bmp");
    }

    /**
     * Apache POI does not provide a GIF picture type.
     * GIF attachments are converted to a static PNG image before addPicture().
     */
    private int pictureType(BomAttachment attachment) {
        String value = imageDescriptor(attachment);

        if (value.contains("jpeg") || value.contains("jpg")) {
            return Workbook.PICTURE_TYPE_JPEG;
        }

        if (value.contains("bmp")) {
            return Workbook.PICTURE_TYPE_DIB;
        }

        // PNG, GIF (after conversion) and unknown image types are inserted as PNG.
        return Workbook.PICTURE_TYPE_PNG;
    }

    /**
     * Converts GIF bytes to PNG because Apache POI supports JPEG, PNG and DIB,
     * but does not expose a GIF picture type. Animated GIFs are exported as their first frame.
     */
    private byte[] normalizePictureBytes(BomAttachment attachment, byte[] originalBytes) throws IOException {
        if (originalBytes == null || originalBytes.length == 0) {
            return originalBytes;
        }

        if (!imageDescriptor(attachment).contains("gif")) {
            return originalBytes;
        }

        try (
                ByteArrayInputStream input = new ByteArrayInputStream(originalBytes);
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            BufferedImage image = ImageIO.read(input);

            if (image == null) {
                throw new IOException("Cannot read GIF image: " + attachment.getOriginalFileName());
            }

            boolean written = ImageIO.write(image, "png", output);
            if (!written) {
                throw new IOException("Cannot convert GIF image to PNG: " + attachment.getOriginalFileName());
            }

            return output.toByteArray();
        }
    }

    private String imageDescriptor(BomAttachment attachment) {
        return (
                (attachment.getContentType() == null ? "" : attachment.getContentType())
                        + " "
                        + (attachment.getOriginalFileName() == null ? "" : attachment.getOriginalFileName())
        ).toLowerCase(Locale.ROOT);
    }

    private Sheet findBomSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            String name = sheet.getSheetName().toUpperCase(Locale.ROOT);
            if (name.contains("BOM") && name.contains("DETAIL")) return sheet;
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (workbook.getSheetAt(i).getSheetName().toUpperCase(Locale.ROOT).contains("BOM")) return workbook.getSheetAt(i);
        }
        return workbook.getSheetAt(0);
    }

    private int findColumnHeaderRow(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        int upperBound = Math.min(sheet.getLastRowNum(), 80);
        for (int rowIndex = 0; rowIndex <= upperBound; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            StringBuilder joined = new StringBuilder();
            for (int col = 0; col < Math.min(Math.max(0, row.getLastCellNum()), 30); col++) {
                joined.append(' ').append(text(row, col, formatter, evaluator).toUpperCase(Locale.ROOT));
            }
            String value = joined.toString();
            if (isBomDetailHeader(value)) return rowIndex;
        }
        return -1;
    }

    /**
     * Accepts both BOM code-header names used by current customer files:
     * SAP CODE (legacy/export format) and FLEX ID (new L.L.Bean format).
     * CONS. and NET CONSUMPTION are also accepted as consumption headers.
     */
    private boolean isBomDetailHeader(String value) {
        String normalized = value == null
                ? ""
                : value.replaceAll("[^A-Z0-9]", "").toUpperCase(Locale.ROOT);
        boolean hasMaterialType = normalized.contains("MTR");
        boolean hasMaterialCode = normalized.contains("SAPCODE") || normalized.contains("FLEXID");
        boolean hasConsumption = normalized.contains("CONSUMPTION") || normalized.contains("CONS");
        return hasMaterialType && hasMaterialCode && hasConsumption;
    }

    private Map<String, Integer> findColorColumns(
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Row colorRow = sheet.getRow(layout.colorNameRow(headerRow));
        if (colorRow == null) return result;

        for (int column = layout.firstColorColumn(); column <= layout.lastColorColumn(); column++) {
            String color = text(colorRow, column, formatter, evaluator);
            if (hasText(color)) result.putIfAbsent(color.trim(), column);
        }
        return result;
    }

    /**
     * Exports the MPR from the supplied MPR Excel template rather than building
     * a new workbook. The only intended difference is removing the former
     * POUCH column, because POUCH is no longer part of the MPR application.
     */
    public byte[] exportMpr(MprDocument mpr) {
        ClassPathResource template = new ClassPathResource(MPR_TEMPLATE_RESOURCE);
        if (!template.exists()) {
            throw new IllegalStateException("MPR Excel template is missing: " + MPR_TEMPLATE_RESOURCE);
        }

        try (InputStream input = template.getInputStream();
             Workbook workbook = WorkbookFactory.create(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = findMprSheet(workbook);
            removeMprPouchColumn(sheet);

            List<MprLine> lines = safe(mpr == null ? null : mpr.getLines()).stream()
                    .filter(Objects::nonNull)
                    .toList();

            int lastDataRow = prepareMprDataRows(sheet, lines.size());
            updateMprTemplateSummary(sheet, lastDataRow);

            for (int index = 0; index < lines.size(); index++) {
                int rowIndex = MPR_DATA_START_ROW + index;
                Row row = sheet.getRow(rowIndex);
                if (row == null) row = sheet.createRow(rowIndex);

                row.setZeroHeight(false);
                writeMprTemplateLine(row, rowIndex + 1, lastDataRow + 1, lines.get(index));
            }

            // Preserve template navigation/print behavior while applying the
            // actual exported data range.
            sheet.setAutoFilter(new CellRangeAddress(
                    MPR_HEADER_ROW,
                    Math.max(MPR_HEADER_ROW, lastDataRow),
                    MPR_STYLE_COLOR_KEY_COL,
                    MPR_LAST_COLUMN
            ));
            sheet.createFreezePane(0, MPR_DATA_START_ROW);
            sheet.setForceFormulaRecalculation(true);
            workbook.setForceFormulaRecalculation(true);

            // There are no external formulas in the exported data. Formula
            // values are calculated once now and will also recalculate in Excel.
            try {
                workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            } catch (RuntimeException ignored) {
                // Excel will recalculate because forceFormulaRecalculation is set.
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to export MPR from the Excel template", ex);
        }
    }

    private Sheet findMprSheet(Workbook workbook) {
        Sheet sheet = workbook.getSheet("MPR");
        return sheet != null ? sheet : workbook.getSheetAt(0);
    }

    /**
     * The uploaded template contains column F = POUCH. POUCH was removed from
     * the system, therefore G:AI is shifted left once and the final AI column
     * is hidden. All remaining template styles, widths and headers stay intact.
     */
    private void removeMprPouchColumn(Sheet sheet) {
        List<CellRangeAddress> mergedRegions = new ArrayList<>();
        for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            mergedRegions.add(new CellRangeAddress(
                    region.getFirstRow(),
                    region.getLastRow(),
                    region.getFirstColumn(),
                    region.getLastColumn()
            ));
        }
        for (int index = sheet.getNumMergedRegions() - 1; index >= 0; index--) {
            sheet.removeMergedRegion(index);
        }

        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            for (int column = MPR_POUCH_COLUMN; column < MPR_TEMPLATE_LAST_COLUMN; column++) {
                Cell target = getOrCreateCell(row, column);
                Cell source = row.getCell(column + 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                copyCellWithinWorkbook(source, target);
            }

            Cell trailing = row.getCell(MPR_TEMPLATE_LAST_COLUMN, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (trailing != null) trailing.setBlank();
        }

        for (int column = MPR_POUCH_COLUMN; column < MPR_TEMPLATE_LAST_COLUMN; column++) {
            sheet.setColumnWidth(column, sheet.getColumnWidth(column + 1));
            sheet.setColumnHidden(column, sheet.isColumnHidden(column + 1));

            CellStyle style = sheet.getColumnStyle(column + 1);
            if (style != null) sheet.setDefaultColumnStyle(column, style);
        }
        sheet.setColumnHidden(MPR_TEMPLATE_LAST_COLUMN, true);

        for (CellRangeAddress region : mergedRegions) {
            CellRangeAddress adjusted = adjustMergedRegionAfterColumnRemoval(region, MPR_POUCH_COLUMN);
            if (adjusted != null) sheet.addMergedRegion(adjusted);
        }
    }

    private CellRangeAddress adjustMergedRegionAfterColumnRemoval(CellRangeAddress region, int removedColumn) {
        int first = region.getFirstColumn();
        int last = region.getLastColumn();

        if (last < removedColumn) return region;
        if (first > removedColumn) {
            return new CellRangeAddress(
                    region.getFirstRow(),
                    region.getLastRow(),
                    first - 1,
                    last - 1
            );
        }
        if (first < removedColumn && last >= removedColumn) {
            return new CellRangeAddress(
                    region.getFirstRow(),
                    region.getLastRow(),
                    first,
                    last - 1
            );
        }
        // A merge consisting only of the removed POUCH column is not retained.
        return null;
    }

    private void copyCellWithinWorkbook(Cell source, Cell target) {
        if (source == null) {
            target.setBlank();
            return;
        }

        target.setCellStyle(source.getCellStyle());
        target.setHyperlink(source.getHyperlink());
        target.setCellComment(source.getCellComment());

        switch (source.getCellType()) {
            case STRING -> target.setCellValue(source.getStringCellValue());
            case NUMERIC -> target.setCellValue(source.getNumericCellValue());
            case BOOLEAN -> target.setCellValue(source.getBooleanCellValue());
            case FORMULA -> target.setCellFormula(source.getCellFormula());
            case ERROR -> target.setCellErrorValue(source.getErrorCellValue());
            case BLANK, _NONE -> target.setBlank();
        }
    }

    /**
     * Clears the original sample data while preserving template styles. Only
     * actual MPR lines are made visible; unused prepared rows remain hidden.
     */
    private int prepareMprDataRows(Sheet sheet, int lineCount) {
        int originalLastRow = sheet.getLastRowNum();
        int lastDataRow = lineCount == 0
                ? MPR_HEADER_ROW
                : MPR_DATA_START_ROW + lineCount - 1;
        int lastRowToPrepare = Math.max(originalLastRow, lastDataRow);

        for (int rowIndex = MPR_DATA_START_ROW; rowIndex <= lastRowToPrepare; rowIndex++) {
            if (rowIndex > originalLastRow) {
                copyRowStyle(sheet, MPR_DATA_START_ROW, rowIndex, MPR_LAST_COLUMN);
            }

            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);

            clearMprDataRow(row);
            row.setZeroHeight(rowIndex > lastDataRow);
        }

        return lastDataRow;
    }

    private void clearMprDataRow(Row row) {
        for (int column = MPR_STYLE_COLOR_KEY_COL; column <= MPR_LAST_COLUMN; column++) {
            Cell cell = getOrCreateCell(row, column);
            cell.setBlank();
        }
    }

    private void updateMprTemplateSummary(Sheet sheet, int lastDataRow) {
        int firstExcelDataRow = MPR_DATA_START_ROW + 1;
        int lastExcelDataRow = Math.max(firstExcelDataRow, lastDataRow + 1);

        // Row 1 totals retain the original look but calculate against the new
        // 34-column MPR layout.
        setCellFormula(sheet, 0, MPR_PO_QTY_COL,
                "SUBTOTAL(9," + excelColumn(MPR_PO_QTY_COL) + firstExcelDataRow + ":"
                        + excelColumn(MPR_PO_QTY_COL) + lastExcelDataRow + ")");
        setCellFormula(sheet, 0, MPR_PURCHASE_QTY_COL,
                "SUBTOTAL(9," + excelColumn(MPR_PURCHASE_QTY_COL) + firstExcelDataRow + ":"
                        + excelColumn(MPR_PURCHASE_QTY_COL) + lastExcelDataRow + ")");
        setCellFormula(sheet, 0, MPR_AMOUNT_USD_COL,
                "SUBTOTAL(9," + excelColumn(MPR_AMOUNT_USD_COL) + firstExcelDataRow + ":"
                        + excelColumn(MPR_AMOUNT_USD_COL) + lastExcelDataRow + ")");

        // The template note is updated to the real sources used by the system.
        setCell(getOrCreateCell(sheet.getRow(0), MPR_CURRENCY_COL),
                "From MAT_INFO / Vendor Code / Currency Master");
    }

    private void writeMprTemplateLine(Row row, int excelRow, int lastExcelDataRow, MprLine line) {
        // A-C: Style information.
        setCellFormula(row, MPR_STYLE_COLOR_KEY_COL,
                excelColumn(MPR_STYLE_DESCRIPTION_COL) + excelRow
                        + "&"
                        + excelColumn(MPR_STYLE_COLOR_COL) + excelRow);
        setCell(getOrCreateCell(row, MPR_STYLE_DESCRIPTION_COL), line.getStyleDescription());
        setCell(getOrCreateCell(row, MPR_STYLE_COLOR_COL), line.getStyleColor());
        setCell(getOrCreateCell(row, MPR_SHIP_TO_COL), line.getShipTo());
        setCell(getOrCreateCell(row, MPR_SALES_COMMENT_COL), line.getSalesComment());

        // F-M: values collected from BOM and MAT_INFO.
        setCell(getOrCreateCell(row, MPR_SAP_CODE_COL), line.getSapCode());
        setCell(getOrCreateCell(row, MPR_BOM_NO_COL), line.getBomLineNo());
        setCell(getOrCreateCell(row, MPR_MATERIAL_TYPE_COL), line.getMaterialType());
        setCell(getOrCreateCell(row, MPR_DESCRIPTION_COL), line.getMatFullDescription());
        setCell(getOrCreateCell(row, MPR_MATERIAL_COLOR_COL), line.getMatColor());
        setCell(getOrCreateCell(row, MPR_UNIT_COL), line.getMatUnit());
        setCell(getOrCreateCell(row, MPR_YIELD_COL), line.getYield());
        setCell(getOrCreateCell(row, MPR_LOSS_COL), line.getLossFactor());

        // N-W: MPR calculation columns.
        setCellFormula(row, MPR_TOTAL_YIELD_COL,
                "IF(COUNT(" + excelColumn(MPR_YIELD_COL) + excelRow + ","
                        + excelColumn(MPR_LOSS_COL) + excelRow + ")<2,\"-\","
                        + "ROUND(" + excelColumn(MPR_YIELD_COL) + excelRow + "*"
                        + excelColumn(MPR_LOSS_COL) + excelRow + ",6))");
        setCell(getOrCreateCell(row, MPR_PO_QTY_COL), line.getPoQuantity());
        setCellFormula(row, MPR_REQUIRED_QTY_COL,
                "IF(COUNT(" + excelColumn(MPR_TOTAL_YIELD_COL) + excelRow + ","
                        + excelColumn(MPR_PO_QTY_COL) + excelRow + ")<2,\"-\","
                        + "ROUND(" + excelColumn(MPR_TOTAL_YIELD_COL) + excelRow + "*"
                        + excelColumn(MPR_PO_QTY_COL) + excelRow + ",6))");
        setCell(getOrCreateCell(row, MPR_SAMPLE_QTY_COL), line.getSampleQuantity());
        // MAT SAMPLE Q'TY = SAMPLE Q'TY * YIELD. Missing/non-numeric input is shown as "-".
        setCellFormula(row, MPR_SAMPLE_MATERIAL_QTY_COL,
                "IF(COUNT(" + excelColumn(MPR_SAMPLE_QTY_COL) + excelRow + ","
                        + excelColumn(MPR_YIELD_COL) + excelRow + ")<2,\"-\","
                        + "ROUND(" + excelColumn(MPR_SAMPLE_QTY_COL) + excelRow + "*"
                        + excelColumn(MPR_YIELD_COL) + excelRow + ",6))");
        setCell(getOrCreateCell(row, MPR_MCD_STOCK_COL), line.getMcdStock());
        setCell(getOrCreateCell(row, MPR_CMCD_STOCK_COL), line.getCmcdStock());
        // SAP STOCK QTY = MCD STOCK + CMCD STOCK. SUM ignores blank and "-" text cells.
        setCellFormula(row, MPR_SAP_STOCK_COL,
                "IF(COUNT(" + excelColumn(MPR_MCD_STOCK_COL) + excelRow + ":"
                        + excelColumn(MPR_CMCD_STOCK_COL) + excelRow + ")=0,\"-\","
                        + "ROUND(SUM(" + excelColumn(MPR_MCD_STOCK_COL) + excelRow + ":"
                        + excelColumn(MPR_CMCD_STOCK_COL) + excelRow + "),6))");
        setCell(getOrCreateCell(row, MPR_NON_SAP_STOCK_COL), line.getNonSapStockQuantity());
        // PURCHASE QTY = MAX(0, REQUIRED + SAMPLE MATERIAL - SAP STOCK - NON SAP STOCK).
        // SUM is deliberately used because it ignores blank / "-" text cells and prevents #VALUE!.
        setCellFormula(row, MPR_PURCHASE_QTY_COL,
                "IF(COUNT(" + excelColumn(MPR_REQUIRED_QTY_COL) + excelRow + ","
                        + excelColumn(MPR_SAMPLE_MATERIAL_QTY_COL) + excelRow + ","
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + ","
                        + excelColumn(MPR_NON_SAP_STOCK_COL) + excelRow + ")=0,\"-\","
                        + "ROUND(MAX(0,SUM("
                        + excelColumn(MPR_REQUIRED_QTY_COL) + excelRow + ","
                        + excelColumn(MPR_SAMPLE_MATERIAL_QTY_COL) + excelRow + ")-SUM("
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + ","
                        + excelColumn(MPR_NON_SAP_STOCK_COL) + excelRow + ")),6))");

        // X-AC: MAT_INFO and Vendor Code snapshot.
        setCell(getOrCreateCell(row, MPR_CURRENCY_COL), line.getCurrency());
        setCell(getOrCreateCell(row, MPR_PRICE_COL), line.getMatPriceWithoutTax());
        setCell(getOrCreateCell(row, MPR_SHORT_SUPPLIER_COL), line.getShortNameSupplier());
        setTextCell(getOrCreateCell(row, MPR_VENDOR_CODE_COL), vendorCodeText(line.getVendorCode()));
        setCell(getOrCreateCell(row, MPR_VENDOR_NAME_COL), line.getVendorName());
        setCell(getOrCreateCell(row, MPR_MAT_CHARGER_COL), line.getMatCharger());

        // AD-AH: Currency snapshot and final MPR calculations.
        setCell(getOrCreateCell(row, MPR_EXCHANGE_RATE_COL), line.getExchangeRate());
        setCellFormula(row, MPR_PRICE_USD_COL,
                "IF(COUNT(" + excelColumn(MPR_PRICE_COL) + excelRow + ","
                        + excelColumn(MPR_EXCHANGE_RATE_COL) + excelRow + ")<2,\"-\","
                        + "IFERROR(ROUND(" + excelColumn(MPR_PRICE_COL) + excelRow + "/"
                        + excelColumn(MPR_EXCHANGE_RATE_COL) + excelRow + ",6),\"-\"))");
        // MAT AMOUNT in USD = (PURCHASE QTY + SAP STOCK QTY) * MAT PRICE (USD).
        // COUNT/SUM protect the formula when calculated quantity cells display "-".
        setCellFormula(row, MPR_AMOUNT_USD_COL,
                "IF(OR(COUNT(" + excelColumn(MPR_PRICE_USD_COL) + excelRow + ")=0,"
                        + "COUNT(" + excelColumn(MPR_PURCHASE_QTY_COL) + excelRow + ","
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + ")=0),\"-\","
                        + "IFERROR(ROUND(SUM("
                        + excelColumn(MPR_PURCHASE_QTY_COL) + excelRow + ","
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + ")*"
                        + excelColumn(MPR_PRICE_USD_COL) + excelRow + ",2),\"-\"))");
        setCell(getOrCreateCell(row, MPR_DUE_DATE_COL), line.getMatDueDate());
        setCellFormula(row, MPR_TOTAL_STYLE_AMOUNT_COL,
                "IF(" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + excelRow + "=\"\",\"-\","
                        + "IFERROR(SUMIF($" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + "$" + (MPR_DATA_START_ROW + 1)
                        + ":$" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + "$" + lastExcelDataRow + ","
                        + excelColumn(MPR_STYLE_COLOR_KEY_COL) + excelRow + ",$"
                        + excelColumn(MPR_AMOUNT_USD_COL) + "$" + (MPR_DATA_START_ROW + 1)
                        + ":$" + excelColumn(MPR_AMOUNT_USD_COL) + "$" + lastExcelDataRow + "),\"-\"))");
    }

    private void setCellFormula(Row row, int column, String formula) {
        getOrCreateCell(row, column).setCellFormula(formula);
    }

    private void setCellFormula(Sheet sheet, int rowIndex, int column, String formula) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);
        setCellFormula(row, column, formula);
    }

    private String excelColumn(int zeroBasedColumn) {
        StringBuilder result = new StringBuilder();
        int column = zeroBasedColumn + 1;
        while (column > 0) {
            int remainder = (column - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            column = (column - 1) / 26;
        }
        return result.toString();
    }

    private int writeFallbackLine(
            Workbook workbook,
            Sheet sheet,
            int rowNo,
            BomLine line,
            BomDocument bom,
            List<BomProductColor> productColors,
            int firstColorColumn,
            int additionalRemarkColumn
    ) {
        int excelRowIndex = rowNo;
        Row row = sheet.createRow(rowNo++);
        Object[] values = {
                line.getMaterialGroupNo(), line.getMaterialType(), null, line.getSapCode(), line.getDetailNo(),
                line.getPosition(), line.getPositionDescription(), line.getPositionDescriptionExtra(), line.getPieceCode(),
                line.getDimensionY(), line.getDimensionX(), line.getQuantity(), line.getDirection(),
                line.getDetailConsumption(), line.getConsumptionNet(), line.getConsumptionUnit(), line.getBomRemark()
        };
        for (int i = 0; i < values.length; i++) setCell(row.createCell(i), values[i]);
        for (int index = 0; index < productColors.size(); index++) {
            BomProductColor productColor = productColors.get(index);
            setCell(getOrCreateCell(row, firstColorColumn + index), productColorValue(line, productColor));
        }
        setCell(getOrCreateCell(row, additionalRemarkColumn), line.getAdditionalRemark());
        line.setSourceRowNumber(excelRowIndex + 1);
        embedFallbackImage(workbook, sheet, line, excelRowIndex);
        return rowNo;
    }

    private void embedFallbackImage(Workbook workbook, Sheet sheet, BomLine line, int rowIndex) {
        BomImage image = line == null ? null : line.getPrimaryImage();
        if (image == null) return;
        StoredBomImage storedImage = exportableStoredImage(image);
        if (storedImage == null) return;
        try (InputStream input = fileStorage.load(storedImage.storedFileName()).getInputStream()) {
            int pictureIndex = workbook.addPicture(input.readAllBytes(), storedImage.pictureType());
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(2);
            anchor.setCol2(3);
            anchor.setRow1(rowIndex);
            anchor.setRow2(rowIndex + 1);
            drawing.createPicture(anchor, pictureIndex);
            sheet.getRow(rowIndex).setHeightInPoints(54f);
            if (sheet.getColumnWidth(2) < 16 * 256) sheet.setColumnWidth(2, 16 * 256);
        } catch (Exception ignored) { }
    }


    private StoredBomImage exportableStoredImage(BomImage image) {
        if (image == null) return null;
        if (hasText(image.getPreviewStoredFileName())) {
            return new StoredBomImage(image.getPreviewStoredFileName(), Workbook.PICTURE_TYPE_PNG);
        }
        if (hasText(image.getThumbnailStoredFileName())) {
            return new StoredBomImage(image.getThumbnailStoredFileName(), Workbook.PICTURE_TYPE_PNG);
        }
        if (!hasText(image.getOriginalStoredFileName())) return null;

        String descriptor = ((image.getOriginalContentType() == null ? "" : image.getOriginalContentType())
                + " " + (image.getOriginalFileName() == null ? "" : image.getOriginalFileName()))
                .toLowerCase(Locale.ROOT);
        if (descriptor.contains("jpeg") || descriptor.contains("jpg")) {
            return new StoredBomImage(image.getOriginalStoredFileName(), Workbook.PICTURE_TYPE_JPEG);
        }
        if (descriptor.contains("png")) {
            return new StoredBomImage(image.getOriginalStoredFileName(), Workbook.PICTURE_TYPE_PNG);
        }
        if (descriptor.contains("bmp")) {
            return new StoredBomImage(image.getOriginalStoredFileName(), Workbook.PICTURE_TYPE_DIB);
        }
        if (descriptor.contains("emf")) {
            return new StoredBomImage(image.getOriginalStoredFileName(), Workbook.PICTURE_TYPE_EMF);
        }
        if (descriptor.contains("wmf")) {
            return new StoredBomImage(image.getOriginalStoredFileName(), Workbook.PICTURE_TYPE_WMF);
        }
        return null;
    }

    private record StoredBomImage(String storedFileName, int pictureType) { }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void writeRow(Sheet sheet, int rowNo, String... values) {
        Row row = sheet.createRow(rowNo);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
    }

    private void setTextCell(Cell cell, String value) {
        if (value == null || value.isBlank()) {
            cell.setBlank();
        } else {
            cell.setCellValue(value);
        }
    }

    private String vendorCodeText(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.matches("^[0-9,]+$") ? text.replace(",", "") : text;
    }

    private void setCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private void clearCell(Row row, int column) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell != null) cell.setBlank();
    }

    private Row getOrCreateRow(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row == null ? sheet.createRow(rowIndex) : row;
    }

    private Cell getOrCreateCell(Row row, int column) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? row.createCell(column) : cell;
    }

    private String text(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) return "";
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).replace('\n', ' ').trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) if (hasText(value)) return value;
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
