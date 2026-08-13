package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
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
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
 * Exports BOMs in their original uploaded Excel format whenever a source file
 * exists. BOMs created without a source workbook fall back to the approved BOM
 * upload template.
 */
@Service
public class OrderBomMprExcelExporter {

    /*
     * MPR export always starts from the supplied Excel template. This preserves
     * its sheet name, top notes, merged cells, font, color, borders, widths,
     * row heights, frozen header, filter, print layout, and number formats.
     */
    private static final String BOM_TEMPLATE_RESOURCE = "templates/BOM_Upload_Template.xlsx";
    private static final String MPR_TEMPLATE_RESOURCE = "templates/MPR_Template.xlsx";
    // Approved MPR layout follows MPR(9).xlsx exactly: one totals row,
    // one orange header row, then data. There are 33 visible columns A:AG.
    private static final int MPR_HEADER_ROW = 1;       // Excel row 2
    private static final int MPR_DATA_START_ROW = 2;   // Excel row 3
    private static final int MPR_LAST_COLUMN = 32;     // AG

    // A:AG. The BOM-source block is F:K = No. / MTR / POSITION / MAT COLOR / UNIT / NET.
    private static final int MPR_STYLE_COLOR_KEY_COL = 0;
    private static final int MPR_STYLE_DESCRIPTION_COL = 1;
    private static final int MPR_STYLE_COLOR_COL = 2;
    private static final int MPR_SHIP_TO_COL = 3;
    private static final int MPR_SALES_COMMENT_COL = 4;
    private static final int MPR_BOM_NO_COL = 5;
    private static final int MPR_MATERIAL_TYPE_COL = 6;
    private static final int MPR_POSITION_COL = 7;
    private static final int MPR_MATERIAL_COLOR_COL = 8;
    private static final int MPR_UNIT_COL = 9;
    private static final int MPR_YIELD_COL = 10;
    private static final int MPR_LOSS_COL = 11;
    private static final int MPR_TOTAL_YIELD_COL = 12;
    private static final int MPR_PO_QTY_COL = 13;
    private static final int MPR_REQUIRED_QTY_COL = 14;
    private static final int MPR_SAMPLE_QTY_COL = 15;
    private static final int MPR_SAMPLE_MATERIAL_QTY_COL = 16;
    private static final int MPR_MCD_STOCK_COL = 17;
    private static final int MPR_CMCD_STOCK_COL = 18;
    private static final int MPR_SAP_STOCK_COL = 19;
    private static final int MPR_NON_SAP_STOCK_COL = 20;
    private static final int MPR_PURCHASE_QTY_COL = 21;
    private static final int MPR_CURRENCY_COL = 22;
    private static final int MPR_PRICE_COL = 23;
    private static final int MPR_SHORT_SUPPLIER_COL = 24;
    private static final int MPR_VENDOR_CODE_COL = 25;
    private static final int MPR_VENDOR_NAME_COL = 26;
    private static final int MPR_MAT_CHARGER_COL = 27;
    private static final int MPR_EXCHANGE_RATE_COL = 28;
    private static final int MPR_PRICE_USD_COL = 29;
    private static final int MPR_AMOUNT_USD_COL = 30;
    private static final int MPR_DUE_DATE_COL = 31;
    private static final int MPR_TOTAL_STYLE_AMOUNT_COL = 32;

    private final BomFileStorageService fileStorage;

    public OrderBomMprExcelExporter(BomFileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * Export Original Format must preserve the workbook that was uploaded through
     * Replace BOM Excel. That source workbook owns the customer's exact merged
     * cells, picture anchors/crops, Comments area, Product Color image slots,
     * borders, widths, heights and print settings.
     *
     * A BOM created without an uploaded source workbook still exports from the
     * approved BOM template as a safe fallback.
     */
    public byte[] exportBom(BomDocument bom) {
        if (bom != null && hasText(bom.getSourceFileStoredName())) {
            try (InputStream input = fileStorage.load(bom.getSourceFileStoredName()).getInputStream();
                 Workbook workbook = WorkbookFactory.create(input);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                Sheet sheet = findBomSheet(workbook);
                patchOriginalTemplate(workbook, sheet, bom);
                workbook.setForceFormulaRecalculation(true);
                sheet.setForceFormulaRecalculation(true);
                workbook.write(out);
                return out.toByteArray();
            } catch (Exception sourceEx) {
                throw new IllegalStateException(
                        "Unable to export the original BOM workbook: " + sourceEx.getMessage(), sourceEx
                );
            }
        }

        ClassPathResource template = new ClassPathResource(BOM_TEMPLATE_RESOURCE);
        if (!template.exists()) {
            throw new IllegalStateException("BOM upload template is missing: " + BOM_TEMPLATE_RESOURCE);
        }

        try (InputStream input = template.getInputStream();
             Workbook workbook = WorkbookFactory.create(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = findBomSheet(workbook);
            renameBomSheet(workbook, sheet);
            populateBomUploadTemplate(workbook, sheet, bom);
            workbook.setForceFormulaRecalculation(true);
            sheet.setForceFormulaRecalculation(true);
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to export BOM: " + ex.getMessage(), ex);
        }
    }

    /** Returns the untouched approved upload form used for new/manual BOMs. */
    public byte[] exportBomTemplate(BomDocument bom) {
        ClassPathResource template = new ClassPathResource(BOM_TEMPLATE_RESOURCE);
        if (!template.exists()) {
            throw new IllegalStateException("BOM upload template is missing: " + BOM_TEMPLATE_RESOURCE);
        }

        try (InputStream input = template.getInputStream();
             Workbook workbook = WorkbookFactory.create(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = findBomSheet(workbook);
            renameBomSheet(workbook, sheet);
            sanitizeBlankBomTemplate(workbook, sheet);
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to download the BOM upload template", ex);
        }
    }

    /**
     * Keeps the downloadable blank BOM template free of accidental drawing overlays
     * and guarantees visible borders across the complete prepared input table.
     * This does not change any parser keys or cell values.
     */
    private void sanitizeBlankBomTemplate(Workbook workbook, Sheet sheet) {
        if (workbook == null || sheet == null) return;

        // A blank upload template must never contain a BOM/product picture or other
        // drawing that can cover the worksheet when opened in desktop Excel.
        if (sheet instanceof XSSFSheet xssfSheet) {
            if (xssfSheet.getCTWorksheet().isSetDrawing()) {
                xssfSheet.getCTWorksheet().unsetDrawing();
            }
        }

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        DataFormatter formatter = new DataFormatter(Locale.US);
        int headerRow = findColumnHeaderRow(sheet, formatter, evaluator);
        if (headerRow < 0) return;

        BomExcelLayout layout = BomExcelLayout.detect(sheet, headerRow, formatter, evaluator);

        // Header information area: Excel rows 1..10, columns A..Q.
        int headerLastRow = Math.max(0, headerRow - 2);
        applyAllBorders(workbook, sheet, 0, headerLastRow, 0, Math.min(16, layout.lastTableColumn()));

        // Complete BOM entry table: numbered guide row + table header + all prepared
        // blank rows in the template. This avoids relying on Excel gridlines.
        int tableFirstRow = Math.max(0, headerRow - 1);
        int tableLastRow = Math.max(tableFirstRow, sheet.getLastRowNum());
        applyAllBorders(workbook, sheet, tableFirstRow, tableLastRow, 0, layout.lastTableColumn());
    }

    /**
     * Writes the current BOM into the approved blank template. Source row
     * numbers and source Product Color columns from an older uploaded workbook
     * are ignored; rows are emitted in the same order as the BOM data model and
     * Product Colors are placed from left to right in the template slots.
     */
    private void populateBomUploadTemplate(Workbook workbook, Sheet sheet, BomDocument bom) throws Exception {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        DataFormatter formatter = new DataFormatter(Locale.US);
        int headerRow = findColumnHeaderRow(sheet, formatter, evaluator);
        if (headerRow < 0) {
            throw new IllegalStateException("Cannot find the BOM detail header in the approved BOM template");
        }

        BomExcelLayout layout = BomExcelLayout.detect(sheet, headerRow, formatter, evaluator);
        clearBomTemplateData(sheet, workbook);
        patchHeader(sheet, bom == null ? null : bom.getHeader(), formatter, evaluator);

        List<BomProductColor> colors = productColors(bom);
        int colorCapacity = Math.max(0, layout.lastColorColumn() - layout.firstColorColumn() + 1);
        if (colors.size() > colorCapacity) {
            throw new IllegalStateException(
                    "The BOM template supports " + colorCapacity + " Product Color column(s), but this BOM contains " + colors.size()
            );
        }

        List<Integer> originalColorColumns = new ArrayList<>();
        for (int index = 0; index < colors.size(); index++) {
            BomProductColor color = colors.get(index);
            originalColorColumns.add(color == null ? null : color.getSourceColumnIndex());
            if (color != null) color.setSourceColumnIndex(layout.firstColorColumn() + index);
        }

        List<BomLine> exportLines = allLines(bom);
        List<Integer> originalLineRows = new ArrayList<>();
        for (BomLine line : exportLines) {
            originalLineRows.add(line == null ? null : line.getSourceRowNumber());
        }

        try {
            Map<String, Integer> colorColumns = new LinkedHashMap<>();
            for (BomProductColor color : colors) {
                if (color == null || color.getSourceColumnIndex() == null) continue;
                colorColumns.putIfAbsent(color.getColorName(), color.getSourceColumnIndex());
            }
            patchProductColorHeaders(sheet, headerRow, layout, bom, colorColumns);
            normalizeOutsideBomTableArea(workbook, sheet, headerRow, layout, colorColumns);
            ensureProductColorImageSlots(workbook, sheet, headerRow, layout, colorColumns);

            int firstDataRow = layout.dataStartRow(headerRow);
            int detailStyleRow = Math.min(firstDataRow + 1, Math.max(firstDataRow, sheet.getLastRowNum()));
            BomTemplateRowStyle mainRowStyle = captureTemplateRowStyle(
                    sheet, firstDataRow, layout.lastTableColumn()
            );
            BomTemplateRowStyle detailRowStyle = captureTemplateRowStyle(
                    sheet, detailStyleRow, layout.lastTableColumn()
            );
            clearTemplateBody(sheet, firstDataRow, layout);

            int outputRow = firstDataRow;
            for (BomLine line : safe(bom == null ? null : bom.getCoreLines())) {
                outputRow = writeTemplateBomLine(
                        sheet, outputRow, mainRowStyle, detailRowStyle, line, bom, layout, colorColumns
                );
            }

            for (BomPacking packing : safe(bom == null ? null : bom.getPackings())) {
                if (packing == null) continue;
                boolean hasPackingName = hasText(packing.getPackingName());
                boolean hasPackingLines = !safe(packing.getLines()).isEmpty();
                if (!hasPackingName && !hasPackingLines) continue;

                if (hasPackingName) {
                    outputRow = writeTemplatePackingTitle(
                            workbook, sheet, outputRow, mainRowStyle, layout, packing.getPackingName()
                    );
                }
                for (BomLine line : safe(packing.getLines())) {
                    outputRow = writeTemplateBomLine(
                            sheet, outputRow, mainRowStyle, detailRowStyle, line, bom, layout, colorColumns
                    );
                }
            }

            embedPrimaryLineImages(workbook, sheet, bom, layout, true);
            embedManualImages(workbook, sheet, bom, layout, colorColumns, true);

            // Values and images are the only export-time changes. The workbook
            // layout, borders, merged cells, widths, heights, view and print
            // settings remain exactly as defined by BOM_Upload_Template.xlsx.
        } finally {
            for (int index = 0; index < colors.size(); index++) {
                BomProductColor color = colors.get(index);
                if (color != null) color.setSourceColumnIndex(originalColorColumns.get(index));
            }
            for (int index = 0; index < exportLines.size(); index++) {
                BomLine line = exportLines.get(index);
                if (line != null) line.setSourceRowNumber(originalLineRows.get(index));
            }
        }
    }

    private void clearBodyMergedRegions(Sheet sheet, int firstDataRow) {
        for (int index = sheet.getNumMergedRegions() - 1; index >= 0; index--) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            if (region != null && region.getLastRow() >= firstDataRow) {
                sheet.removeMergedRegion(index);
            }
        }
    }

    private void clearTemplateBody(Sheet sheet, int firstDataRow, BomExcelLayout layout) {
        int lastColumn = layout.lastTableColumn();
        for (int rowIndex = firstDataRow; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = getOrCreateRow(sheet, rowIndex);
            row.setZeroHeight(false);
            for (int column = Math.max(0, layout.materialGroupColumn()); column <= lastColumn; column++) {
                clearCell(row, column);
            }
        }
    }

    private BomTemplateRowStyle captureTemplateRowStyle(Sheet sheet, int rowIndex, int maxColumn) {
        Row row = getOrCreateRow(sheet, rowIndex);
        List<CellStyle> styles = new ArrayList<>();
        for (int column = 0; column <= maxColumn; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            CellStyle style = cell == null ? sheet.getColumnStyle(column) : cell.getCellStyle();
            styles.add(style);
        }
        return new BomTemplateRowStyle(row.getHeight(), row.getZeroHeight(), styles);
    }

    private void applyTemplateRowStyle(
            Sheet sheet,
            int rowIndex,
            BomTemplateRowStyle rowStyle,
            int maxColumn
    ) {
        Row row = getOrCreateRow(sheet, rowIndex);
        row.setHeight(rowStyle.height());
        row.setZeroHeight(false);
        for (int column = 0; column <= maxColumn; column++) {
            Cell cell = getOrCreateCell(row, column);
            if (column < rowStyle.styles().size() && rowStyle.styles().get(column) != null) {
                cell.setCellStyle(rowStyle.styles().get(column));
            }
            clearCell(row, column);
        }
    }

    private int writeTemplateBomLine(
            Sheet sheet,
            int rowIndex,
            BomTemplateRowStyle mainRowStyle,
            BomTemplateRowStyle detailRowStyle,
            BomLine line,
            BomDocument bom,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns
    ) {
        if (line == null) return rowIndex;
        BomTemplateRowStyle style = hasText(line.getMaterialType()) ? mainRowStyle : detailRowStyle;
        applyTemplateRowStyle(sheet, rowIndex, style, layout.lastTableColumn());
        patchLineAt(sheet, rowIndex, line, bom, layout, colorColumns);
        line.setSourceRowNumber(rowIndex + 1);
        return rowIndex + 1;
    }

    private int writeTemplatePackingTitle(
            Workbook workbook,
            Sheet sheet,
            int rowIndex,
            BomTemplateRowStyle rowStyle,
            BomExcelLayout layout,
            String packingName
    ) {
        // Packing is data, not a different worksheet format. Keep the standard
        // template grid and write the title into the first BOM column only.
        applyTemplateRowStyle(sheet, rowIndex, rowStyle, layout.lastTableColumn());
        Row row = getOrCreateRow(sheet, rowIndex);
        setCell(getOrCreateCell(row, Math.max(0, layout.materialGroupColumn())), packingName);
        return rowIndex + 1;
    }

    private record BomTemplateRowStyle(short height, boolean zeroHeight, List<CellStyle> styles) { }

    /** Fallback only for internal compatibility; normal BOM export always uses the approved template. */
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

    private void clearBomTemplateData(Sheet sheet, Workbook workbook) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        DataFormatter formatter = new DataFormatter(Locale.US);
        int headerRow = findColumnHeaderRow(sheet, formatter, evaluator);
        if (headerRow < 0) {
            throw new IllegalStateException("Cannot find BOM detail header while creating the BOM template");
        }

        BomExcelLayout layout = BomExcelLayout.detect(sheet, headerRow, formatter, evaluator);

        // Keep the visible header keys but remove every BOM-specific value.
        for (String label : List.of(
                "BOM NO", "BOM NAME", "STATUS",
                "BUYER", "REV. STAGE", "COMMENTS", "SEASON",
                "PATTERN DATE", "OLD PATTERN DATE", "STYLE NUMBER",
                "PATTERN REVISED DATE", "PATTERN NUMBER", "PATTERN MAKER",
                "STYLE NAME", "MARKER DATE", "MARKER MAKER",
                "FACTORY PRODUCT", "BOM MAKER", "SIZE", "BOM DATE"
        )) {
            clearHeaderValue(sheet, label, formatter, evaluator);
        }

        // Clear any other top-area values (for example product notes such as NEW)
        // while preserving the visible title, field labels and numbered format row.
        clearUnlabelledTopValues(sheet, headerRow, formatter, evaluator);

        // Product Color header values are data, not template keys.
        LinkedHashSet<Integer> productColorHeaderRows = new LinkedHashSet<>();
        productColorHeaderRows.add(layout.patternNumberRow(headerRow));
        productColorHeaderRows.add(layout.seasonRow(headerRow));
        productColorHeaderRows.add(layout.styleNumberRow(headerRow));
        productColorHeaderRows.add(layout.sequenceRow(headerRow));
        productColorHeaderRows.add(layout.colorNameRow(headerRow));
        for (Integer rowIndex : productColorHeaderRows) {
            if (rowIndex == null || rowIndex < 0) continue;
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = layout.firstColorColumn(); column <= layout.lastColorColumn(); column++) {
                blankTemplateCell(row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            }
        }

        // Clear all material, packing, consumption, color and remark values.
        int firstTableColumn = Math.max(0, layout.materialGroupColumn());
        int lastTableColumn = Math.max(firstTableColumn, layout.lastTableColumn());
        int firstDataRow = layout.dataStartRow(headerRow);
        int lastDataRow = sheet.getLastRowNum();
        for (int rowIndex = firstDataRow; rowIndex <= lastDataRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            int lastExistingColumn = Math.max(lastTableColumn, Math.max(0, row.getLastCellNum() - 1));
            for (int column = firstTableColumn; column <= lastExistingColumn; column++) {
                blankTemplateCell(row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            }
        }

        // Product pictures are data. Remove pictures but keep non-picture shapes.
        removeTemplatePictures(sheet);

        // Do not change borders, merged cells, dimensions, row heights or any
        // other formatting here. Export must retain the template format exactly.
    }

    private void clearUnlabelledTopValues(
            Sheet sheet,
            int headerRow,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        for (int rowIndex = 0; rowIndex < headerRow; rowIndex++) {
            // The row immediately before the column headers is the format's
            // visible column-number/key row and must remain unchanged.
            if (rowIndex == headerRow - 1) continue;
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = 0; column < Math.max(0, row.getLastCellNum()); column++) {
                Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null) continue;
                String value = formatter.formatCellValue(cell, evaluator).trim();
                if (!hasText(value) || isBomTemplateKey(value)) continue;
                blankTemplateCell(cell);
            }
        }
    }

    private boolean isBomTemplateKey(String value) {
        String normalized = normalize(value);
        if (normalized.equals("THEBOMDETAILS")) return true;
        if (value != null && value.trim().endsWith(":")) return true;
        for (String key : List.of(
                "BOM NO", "BOM NAME", "STATUS", "BUYER", "REV STAGE",
                "COMMENTS", "SEASON", "PATTERN DATE", "OLD PATTERN DATE",
                "STYLE NUMBER", "PATTERN REVISED DATE", "PATTERN NUMBER",
                "PATTERN MAKER", "STYLE NAME", "MARKER DATE", "MARKER MAKER",
                "FACTORY PRODUCT", "BOM MAKER", "SIZE", "BOM DATE"
        )) {
            if (normalized.contains(normalize(key))) return true;
        }
        return false;
    }

    private void clearHeaderValue(
            Sheet sheet,
            String label,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        for (int rowIndex = 0; rowIndex <= Math.min(sheet.getLastRowNum(), 18); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = 0; column < Math.min(Math.max(0, row.getLastCellNum()), 30); column++) {
                if (!headerLabelMatches(text(row, column, formatter, evaluator), label)) continue;
                blankTemplateCell(resolveHeaderValueCell(sheet, rowIndex, column, formatter, evaluator));
                return;
            }
        }
    }

    private void blankTemplateCell(Cell cell) {
        if (cell == null) return;
        cell.setBlank();
        cell.setCellComment(null);
    }

    private void removeTemplatePictures(Sheet sheet) {
        if (!(sheet instanceof XSSFSheet xssfSheet)) return;
        XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
        if (drawing == null) return;

        var xml = drawing.getCTDrawing();
        for (int index = xml.sizeOfTwoCellAnchorArray() - 1; index >= 0; index--) {
            if (xml.getTwoCellAnchorArray(index).isSetPic()) xml.removeTwoCellAnchor(index);
        }
        for (int index = xml.sizeOfOneCellAnchorArray() - 1; index >= 0; index--) {
            if (xml.getOneCellAnchorArray(index).isSetPic()) xml.removeOneCellAnchor(index);
        }
        for (int index = xml.sizeOfAbsoluteAnchorArray() - 1; index >= 0; index--) {
            if (xml.getAbsoluteAnchorArray(index).isSetPic()) xml.removeAbsoluteAnchor(index);
        }
    }

    private void patchOriginalTemplate(Workbook workbook, Sheet sheet, BomDocument bom) throws Exception {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        DataFormatter formatter = new DataFormatter(Locale.US);
        int headerRow = findColumnHeaderRow(sheet, formatter, evaluator);
        if (headerRow < 0) throw new IllegalStateException("Cannot find BOM detail header in the original workbook");

        BomExcelLayout layout = BomExcelLayout.detect(sheet, headerRow, formatter, evaluator);
        Map<String, Integer> colorColumns = findColorColumns(sheet, headerRow, layout, formatter, evaluator);
        ensureCommentsValueLayout(sheet, headerRow, layout, formatter, evaluator);
        patchHeader(sheet, bom.getHeader(), formatter, evaluator);
        patchProductColorHeaders(sheet, headerRow, layout, bom, colorColumns);
        normalizeOutsideBomTableArea(workbook, sheet, headerRow, layout, colorColumns);
        ensureProductColorImageSlots(workbook, sheet, headerRow, layout, colorColumns);
        removeReplacedOrDeletedLineImages(sheet, bom, layout);
        removeReplacedHeaderImages(sheet, bom, headerRow, layout, colorColumns, formatter, evaluator);

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
        repairMissingBomTableBorders(workbook, sheet, bom, layout, colorColumns);
        embedPrimaryLineImages(workbook, sheet, bom, layout);
        embedManualImages(workbook, sheet, bom, layout, colorColumns);

        // Do NOT redraw a generic border grid on an uploaded workbook. Existing
        // rows already contain the customer's exact border styles, while newly
        // inserted rows copy the style of the surrounding source row. Rebuilding
        // borders here previously changed the Original Format appearance.
    }

    /**
     * Restores the approved Comments value slot without rebuilding the rest of
     * the header. In L.L.Bean this is the blank range immediately after the
     * COMMENTS label and before the first Product Color column (normally J2:Q2).
     */
    private void ensureCommentsValueLayout(
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        int labelRow = -1;
        int labelColumn = -1;
        for (int rowIndex = 0; rowIndex <= Math.min(Math.max(0, headerRow - 1), 18); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = 0; column < Math.min(Math.max(0, row.getLastCellNum()), 30); column++) {
                if (headerLabelMatches(text(row, column, formatter, evaluator), "COMMENTS")) {
                    labelRow = rowIndex;
                    labelColumn = column;
                    break;
                }
            }
            if (labelRow >= 0) break;
        }
        if (labelRow < 0 || labelColumn < 0) return;

        int firstColumn = labelColumn + 1;
        int lastColumn = Math.max(firstColumn, layout.firstColorColumn() - 1);
        if (lastColumn <= firstColumn) return;

        // Do not merge over real business content. Only normalize the range when
        // every cell after the first value slot is blank.
        Row row = getOrCreateRow(sheet, labelRow);
        for (int column = firstColumn + 1; column <= lastColumn; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && hasText(formatter.formatCellValue(cell, evaluator))) return;
        }

        CellRangeAddress desired = new CellRangeAddress(labelRow, labelRow, firstColumn, lastColumn);
        boolean alreadyMerged = false;
        for (int index = sheet.getNumMergedRegions() - 1; index >= 0; index--) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            if (region == null || !region.intersects(desired)) continue;
            if (region.getFirstRow() == desired.getFirstRow()
                    && region.getLastRow() == desired.getLastRow()
                    && region.getFirstColumn() == desired.getFirstColumn()
                    && region.getLastColumn() == desired.getLastColumn()) {
                alreadyMerged = true;
                break;
            }
            // Avoid damaging a different intentional merge in a non-standard BOM.
            return;
        }
        if (!alreadyMerged) sheet.addMergedRegion(desired);

        Cell valueCell = getOrCreateCell(row, firstColumn);
        CellStyle base = valueCell.getCellStyle();
        CellStyle wrapped = sheet.getWorkbook().createCellStyle();
        wrapped.cloneStyleFrom(base);
        wrapped.setWrapText(true);
        wrapped.setVerticalAlignment(VerticalAlignment.TOP);
        valueCell.setCellStyle(wrapped);
    }

    /**
     * Repairs only missing borders on real BOM data rows. Existing border
     * styles, fills, fonts, alignment and number formats are preserved. This
     * avoids the old behaviour of repainting the entire workbook while still
     * producing a clean continuous grid for exported material/packing rows.
     */
    private void repairMissingBomTableBorders(
            Workbook workbook,
            Sheet sheet,
            BomDocument bom,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns
    ) {
        int firstColumn = Math.max(0, layout.materialGroupColumn());
        int lastColumn = maxTableColumn(layout, colorColumns);
        Map<Short, CellStyle> repairedStyles = new LinkedHashMap<>();
        java.util.Set<Integer> activeRows = new LinkedHashSet<>();

        for (BomLine line : allLines(bom)) {
            if (line != null && line.getSourceRowNumber() != null && line.getSourceRowNumber() > 0) {
                activeRows.add(line.getSourceRowNumber() - 1);
            }
        }

        for (Integer rowIndex : activeRows) {
            if (rowIndex == null || rowIndex < 0) continue;
            Row row = getOrCreateRow(sheet, rowIndex);
            for (int column = firstColumn; column <= lastColumn; column++) {
                Cell cell = getOrCreateCell(row, column);
                CellStyle style = cell.getCellStyle();
                if (hasAllThinOrStrongerBorders(style)) continue;

                short sourceStyleId = style == null ? 0 : style.getIndex();
                CellStyle repaired = repairedStyles.get(sourceStyleId);
                if (repaired == null) {
                    repaired = workbook.createCellStyle();
                    if (style != null) repaired.cloneStyleFrom(style);
                    if (repaired.getBorderTop() == BorderStyle.NONE) repaired.setBorderTop(BorderStyle.THIN);
                    if (repaired.getBorderBottom() == BorderStyle.NONE) repaired.setBorderBottom(BorderStyle.THIN);
                    if (repaired.getBorderLeft() == BorderStyle.NONE) repaired.setBorderLeft(BorderStyle.THIN);
                    if (repaired.getBorderRight() == BorderStyle.NONE) repaired.setBorderRight(BorderStyle.THIN);
                    repairedStyles.put(sourceStyleId, repaired);
                }
                cell.setCellStyle(repaired);
            }
        }

        // Packing section titles are merged across the table. Restore a visible
        // boundary around each section without touching the section fill/font.
        for (BomPacking packing : safe(bom.getPackings())) {
            if (packing == null || !hasText(packing.getPackingName())) continue;
            int packingRow = findPackingRow(sheet, packing.getPackingName());
            if (packingRow < 0) continue;
            CellRangeAddress region = mergedRegionAt(sheet, packingRow, firstColumn);
            if (region == null) region = new CellRangeAddress(packingRow, packingRow, firstColumn, lastColumn);
            RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
            RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
            RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
            RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
        }
    }

    private boolean hasAllThinOrStrongerBorders(CellStyle style) {
        return style != null
                && style.getBorderTop() != BorderStyle.NONE
                && style.getBorderBottom() != BorderStyle.NONE
                && style.getBorderLeft() != BorderStyle.NONE
                && style.getBorderRight() != BorderStyle.NONE;
    }

    private CellRangeAddress mergedRegionAt(Sheet sheet, int row, int column) {
        for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            if (region != null && region.isInRange(row, column)) return region;
        }
        return null;
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

        for (int rowIndex = 0; rowIndex <= Math.min(sheet.getLastRowNum(), 18); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            for (int column = 0; column < Math.min(Math.max(0, row.getLastCellNum()), 30); column++) {
                if (!headerLabelMatches(text(row, column, formatter, evaluator), label)) continue;

                Cell target = resolveHeaderValueCell(sheet, rowIndex, column, formatter, evaluator);
                if (target == null) target = getOrCreateCell(row, column + 1);
                setCell(target, value);
                return;
            }
        }
    }

    /**
     * Header values in the L.L.Bean workbook are layout slots, and many slots
     * are intentionally blank before data is written (for example Comments is
     * the merged J2:Q2 range in the approved form). The old implementation
     * searched only non-blank cells, which could fall through to a cell below
     * the label and move the exported value away from its original position.
     */
    private Cell resolveHeaderValueCell(
            Sheet sheet,
            int labelRow,
            int labelColumn,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        Row row = getOrCreateRow(sheet, labelRow);
        for (int offset = 1; offset <= 4; offset++) {
            int column = labelColumn + offset;
            Cell existing = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = existing == null ? "" : formatter.formatCellValue(existing, evaluator).trim();
            if (looksLikeHeaderLabel(value)) break;

            Cell mergedTopLeft = mergedTopLeftCell(sheet, labelRow, column);
            if (mergedTopLeft != null) return mergedTopLeft;

            // The first cell immediately after the label is the normal value
            // slot even when it is blank. Keeping it avoids writing Comments,
            // Rev. Stage and other values into a visually different row.
            if (offset == 1) return getOrCreateCell(row, column);
            if (existing != null) return existing;
        }

        Cell below = firstExistingValueCellBelow(sheet, labelRow, labelColumn, formatter, evaluator);
        return below == null ? null : mergedTopLeftCellOrSelf(sheet, below);
    }

    private Cell mergedTopLeftCell(Sheet sheet, int row, int column) {
        for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            if (region == null || !region.isInRange(row, column)) continue;
            return getOrCreateCell(getOrCreateRow(sheet, region.getFirstRow()), region.getFirstColumn());
        }
        return null;
    }

    private Cell mergedTopLeftCellOrSelf(Sheet sheet, Cell cell) {
        if (cell == null) return null;
        Cell merged = mergedTopLeftCell(sheet, cell.getRowIndex(), cell.getColumnIndex());
        return merged == null ? cell : merged;
    }

    private boolean headerLabelMatches(String candidate, String label) {
        String current = normalize(candidate);
        String expected = normalize(label);
        if (current.equals(expected)) return true;

        // Existing L.L.Bean forms label this source field as Old Pattern Date.
        if (expected.equals("PATTERNDATE")) {
            return current.equals("PATTERNDATE") || current.equals("OLDPATTERNDATE");
        }
        // The Size label often includes its W x H x D hint.
        if (expected.equals("SIZE")) return current.startsWith("SIZE");
        return false;
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

    /**
     * If a user replaces a Whole BOM image or Product Color image after the
     * Excel import, remove only the corresponding picture from the stored
     * source workbook before inserting the new one. Untouched imported images
     * remain exactly where Excel originally placed/cropped them.
     */
    private void removeReplacedHeaderImages(
            Sheet sheet,
            BomDocument bom,
            int headerRow,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        if (!(sheet instanceof XSSFSheet xssfSheet)) return;
        XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
        if (drawing == null) return;

        boolean replaceBomImage = false;
        java.util.Set<Integer> replaceColorColumns = new java.util.LinkedHashSet<>();
        for (BomAttachment attachment : allAttachments(bom)) {
            if (attachment == null || attachment.isImportedFromExcel() || !isImage(attachment)) continue;
            String scope = attachment.getScope() == null ? "BOM" : attachment.getScope().toUpperCase(Locale.ROOT);
            if ("BOM".equals(scope)) {
                replaceBomImage = true;
                continue;
            }
            if (!"COLOR".equals(scope)) continue;
            BomProductColor productColor = findProductColor(bom, attachment.getProductColorId(), attachment.getColorKey());
            String productColorName = productColor == null
                    ? resolveProductColorName(bom, attachment.getProductColorId(), attachment.getColorKey())
                    : productColor.getColorName();
            Integer column = productColor != null && productColor.getSourceColumnIndex() != null
                    ? productColor.getSourceColumnIndex()
                    : colorColumns.get(productColorName);
            if (column != null) replaceColorColumns.add(column);
        }
        if (!replaceBomImage && replaceColorColumns.isEmpty()) return;

        CellAddressRange commentsArea = commentsImageArea(sheet, headerRow, layout, formatter, evaluator);
        var ctDrawing = drawing.getCTDrawing();
        for (int index = ctDrawing.sizeOfTwoCellAnchorArray() - 1; index >= 0; index--) {
            var anchor = ctDrawing.getTwoCellAnchorArray(index);
            if (!anchor.isSetPic() || anchor.getFrom() == null) continue;
            int row = anchor.getFrom().getRow();
            int column = anchor.getFrom().getCol();
            if (shouldRemoveHeaderPicture(row, column, headerRow, layout.lastTableColumn(), replaceBomImage, replaceColorColumns, commentsArea)) {
                ctDrawing.removeTwoCellAnchor(index);
            }
        }
        for (int index = ctDrawing.sizeOfOneCellAnchorArray() - 1; index >= 0; index--) {
            var anchor = ctDrawing.getOneCellAnchorArray(index);
            if (!anchor.isSetPic() || anchor.getFrom() == null) continue;
            int row = anchor.getFrom().getRow();
            int column = anchor.getFrom().getCol();
            if (shouldRemoveHeaderPicture(row, column, headerRow, layout.lastTableColumn(), replaceBomImage, replaceColorColumns, commentsArea)) {
                ctDrawing.removeOneCellAnchor(index);
            }
        }
    }

    private boolean shouldRemoveHeaderPicture(
            int row,
            int column,
            int headerRow,
            int lastTableColumn,
            boolean replaceBomImage,
            java.util.Set<Integer> replaceColorColumns,
            CellAddressRange commentsArea
    ) {
        // Product Color replacement removes only pictures that belong to the
        // dedicated white image slot. Lower screenshots/swatches and unrelated
        // header artwork in the same column stay untouched. We also accept the
        // previous upper slot so files exported by older versions remain
        // replaceable without leaving a duplicate image behind.
        CellAddressRange productSlot = productColorImageSlot(headerRow);
        int legacyProductImageZoneEndRow = Math.max(2, headerRow / 2);
        boolean inProductSlot = row >= productSlot.firstRow() && row < productSlot.lastRowExclusive();
        boolean inLegacyProductSlot = row >= 0 && row <= legacyProductImageZoneEndRow;
        if (replaceColorColumns.contains(column) && (inProductSlot || inLegacyProductSlot)) return true;

        if (!replaceBomImage) return false;
        boolean insideComments = column >= commentsArea.firstColumn()
                && column < commentsArea.lastColumnExclusive()
                && row >= Math.max(0, commentsArea.firstRow() - 1)
                && row < commentsArea.lastRowExclusive();
        boolean outsideBomTable = column > lastTableColumn;
        return insideComments || outsideBomTable;
    }

    private void embedPrimaryLineImages(Workbook workbook, Sheet sheet, BomDocument bom, BomExcelLayout layout) {
        embedPrimaryLineImages(workbook, sheet, bom, layout, false);
    }

    private void embedPrimaryLineImages(
            Workbook workbook,
            Sheet sheet,
            BomDocument bom,
            BomExcelLayout layout,
            boolean includeImportedFromExcel
    ) {
        if (!layout.hasImageColumn()) return;
        for (BomLine line : allLines(bom)) {
            BomImage image = line == null ? null : line.getPrimaryImage();
            if (image == null || line.getSourceRowNumber() == null) continue;
            // When exporting from the original uploaded workbook, imported images
            // already exist. When exporting from the approved blank template,
            // they must be inserted again from stored image data.
            if (image.isImportedFromExcel() && !includeImportedFromExcel) continue;

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
                // Preserve the workbook's original row height and Image-column
                // width. Export Original Format must not resize the source grid.
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
        embedManualImages(workbook, sheet, bom, layout, colorColumns, false);
    }

    private void embedManualImages(
            Workbook workbook,
            Sheet sheet,
            BomDocument bom,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns,
            boolean includeImportedFromExcel
    ) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        DataFormatter formatter = new DataFormatter(Locale.US);
        int headerRow = findColumnHeaderRow(sheet, formatter, evaluator);
        CellAddressRange productColorImageSlot = productColorImageSlot(headerRow);
        CellAddressRange commentsArea = commentsImageArea(sheet, headerRow, layout, formatter, evaluator);

        for (BomAttachment attachment : allAttachments(bom)) {
            if ((attachment.isImportedFromExcel() && !includeImportedFromExcel) || !isImage(attachment)) continue;
            if (!hasText(attachment.getStoredFileName())) continue;

            try (InputStream input = fileStorage.load(attachment.getStoredFileName()).getInputStream()) {
                byte[] data = input.readAllBytes();
                byte[] pictureBytes = normalizePictureBytes(attachment, data);

                String scope = attachment.getScope() == null ? "BOM" : attachment.getScope().toUpperCase(Locale.ROOT);
                BomProductColor attachmentProductColor = findProductColor(bom, attachment.getProductColorId(), attachment.getColorKey());
                String productColorName = attachmentProductColor == null
                        ? resolveProductColorName(bom, attachment.getProductColorId(), attachment.getColorKey())
                        : attachmentProductColor.getColorName();
                Integer productColorColumn = attachmentProductColor != null && attachmentProductColor.getSourceColumnIndex() != null
                        ? attachmentProductColor.getSourceColumnIndex()
                        : colorColumns.get(productColorName);

                int outputPictureType = pictureType(attachment);
                if ("COLOR".equals(scope) && productColorColumn != null) {
                    PreparedPicture prepared = prepareProductColorPicture(
                            sheet, productColorImageSlot, productColorColumn, pictureBytes, outputPictureType
                    );
                    pictureBytes = prepared.bytes();
                    outputPictureType = prepared.pictureType();
                }

                int pictureIndex = workbook.addPicture(pictureBytes, outputPictureType);
                Drawing<?> drawing = sheet.createDrawingPatriarch();
                ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
                anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);

                if ("COLOR".equals(scope) && productColorColumn != null) {
                    // Each Product Color owns exactly one header column. The old
                    // +3 column / +7 row anchor overlapped neighboring colors.
                    anchor.setCol1(productColorColumn);
                    anchor.setCol2(productColorColumn + 1);
                    anchor.setRow1(productColorImageSlot.firstRow());
                    anchor.setRow2(productColorImageSlot.lastRowExclusive());
                } else if ("LINE".equals(scope)) {
                    BomLine line = findLineById(bom, attachment.getLineId());
                    int row = line != null && line.getSourceRowNumber() != null
                            ? line.getSourceRowNumber() - 1 : Math.max(0, sheet.getLastRowNum());
                    int imageColumn = layout.hasImageColumn() ? layout.imageColumn() : Math.max(0, layout.materialGroupColumn());
                    anchor.setCol1(imageColumn);
                    anchor.setCol2(imageColumn + 1);
                    anchor.setRow1(Math.max(0, row));
                    anchor.setRow2(Math.max(1, row + 1));
                } else if ("PACKING".equals(scope)) {
                    BomPacking packing = findPackingById(bom, attachment.getPackingId());
                    int packingRow = packing == null ? -1 : findPackingRow(sheet, packing.getPackingName());
                    int row = packingRow >= 0 ? packingRow : Math.max(0, sheet.getLastRowNum());
                    int imageColumn = layout.hasImageColumn() ? layout.imageColumn() : Math.max(0, layout.materialGroupColumn());
                    anchor.setCol1(imageColumn);
                    anchor.setCol2(imageColumn + 1);
                    anchor.setRow1(Math.max(0, row));
                    anchor.setRow2(Math.max(1, row + 1));
                } else {
                    // Whole-BOM/header image belongs inside the Comments block,
                    // not outside the table at AA+. Keep row 2 for the Comments
                    // text itself and use the blank area underneath it.
                    anchor.setCol1(commentsArea.firstColumn());
                    anchor.setCol2(commentsArea.lastColumnExclusive());
                    anchor.setRow1(commentsArea.firstRow());
                    anchor.setRow2(commentsArea.lastRowExclusive());
                }

                drawing.createPicture(anchor, pictureIndex);
            } catch (Exception ignored) {
                // A malformed optional image must not prevent the BOM workbook from being exported.
            }
        }
    }

    /**
     * Keeps the worksheet visually clean outside the real BOM detail table.
     * The BOM detail header and all rows below it keep their original customer
     * formatting. The customer BOM information block above the table is kept
     * exactly as uploaded. Only the Product Color / REMARKS canvas above the
     * table, plus any used columns to the right of the table, is rendered on a
     * plain white canvas with no cell borders. Sheet gridlines are also hidden,
     * so blank areas remain truly white without erasing the original header
     * information table.
     *
     * Values, formulas, fonts, alignment, merged ranges and picture anchors are
     * preserved. Only background fill and borders outside the detail table are
     * normalized. This is safe for Export -> edit values -> Replace BOM Excel
     * because the importer reads the header labels / values and Product Color
     * columns, not decorative borders or fills.
     */
    private void normalizeOutsideBomTableArea(
            Workbook workbook,
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns
    ) {
        if (workbook == null || sheet == null || headerRow <= 0) return;

        // A bordered BOM table is enough to guide the eye. Native worksheet
        // gridlines outside it only create the unwanted grey/yellow cell grid.
        sheet.setDisplayGridlines(false);
        sheet.setPrintGridlines(false);

        int lastTableColumn = maxTableColumn(layout, colorColumns);
        int firstProductColorColumn = colorColumns == null || colorColumns.isEmpty()
                ? lastTableColumn + 1
                : colorColumns.values().stream().mapToInt(Integer::intValue).min().orElse(lastTableColumn + 1);
        int lastUsedColumn = Math.max(lastTableColumn, findLastUsedColumn(sheet));
        Map<Short, CellStyle> whiteStyles = new LinkedHashMap<>();

        // Keep the original BOM information block on the left intact
        // (THE BOM DETAILS, Buyer/Season/Style/Pattern/BOM Maker and the
        // customer Item Name reference table). Only the Product Color /
        // REMARKS canvas above the real BOM table is normalized to white.
        // Native sheet gridlines are hidden, so untouched blank cells on the
        // left still render as clean white without destroying the intentional
        // fills/borders of the customer header table.
        if (firstProductColorColumn <= lastTableColumn) {
            for (int rowIndex = 0; rowIndex < headerRow; rowIndex++) {
                Row row = getOrCreateRow(sheet, rowIndex);
                for (int column = firstProductColorColumn; column <= lastTableColumn; column++) {
                    applyWhiteCanvasStyle(workbook, getOrCreateCell(row, column), whiteStyles);
                }
            }
        }

        // Anything to the right of the BOM table is also outside the table.
        // This removes residual formatting from old AA+ image/reference areas.
        if (lastUsedColumn > lastTableColumn) {
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = getOrCreateRow(sheet, rowIndex);
                for (int column = lastTableColumn + 1; column <= lastUsedColumn; column++) {
                    applyWhiteCanvasStyle(workbook, getOrCreateCell(row, column), whiteStyles);
                }
            }
        }
    }

    private void applyWhiteCanvasStyle(
            Workbook workbook,
            Cell cell,
            Map<Short, CellStyle> whiteStyles
    ) {
        CellStyle source = cell.getCellStyle();
        short styleId = source == null ? 0 : source.getIndex();
        CellStyle white = whiteStyles.get(styleId);
        if (white == null) {
            white = workbook.createCellStyle();
            if (source != null) white.cloneStyleFrom(source);
            white.setFillForegroundColor(IndexedColors.WHITE.getIndex());
            white.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            white.setBorderTop(BorderStyle.NONE);
            white.setBorderBottom(BorderStyle.NONE);
            white.setBorderLeft(BorderStyle.NONE);
            white.setBorderRight(BorderStyle.NONE);
            whiteStyles.put(styleId, white);
        }
        cell.setCellStyle(white);
    }

    private int findLastUsedColumn(Sheet sheet) {
        int last = 0;
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || row.getLastCellNum() < 0) continue;
            last = Math.max(last, row.getLastCellNum() - 1);
        }
        return last;
    }

    /**
     * Creates one clean white image slot per Product Color without touching the
     * Product Color text rows below it. The slot is a vertical merge in that
     * color's own column, so the workbook stays compact and the BOM parser can
     * still identify the Product Color by its column after Export -> edit ->
     * Replace BOM Excel.
     *
     * L.L.Bean currently has the detail header on Excel row 12. That makes the
     * safe image band rows 6:10, leaving row 11 for the existing numbered guide
     * and rows 12:17 for Pattern / Season / Style / Sequence / Color data.
     * Other supported BOM layouts derive the same five-row band relative to the
     * detected header instead of hard-coding an Excel row number.
     */
    private void ensureProductColorImageSlots(
            Workbook workbook,
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            Map<String, Integer> colorColumns
    ) {
        if (workbook == null || sheet == null || headerRow < 2) return;

        CellAddressRange slot = productColorImageSlot(headerRow);
        java.util.Set<Integer> columns = new LinkedHashSet<>();
        if (colorColumns != null) {
            for (Integer column : colorColumns.values()) {
                if (column != null && column >= layout.firstColorColumn() && column <= layout.lastColorColumn()) {
                    columns.add(column);
                }
            }
        }
        // On a blank template there may not yet be a name -> column entry for
        // every available Product Color slot. Only normalize the actual colors
        // when possible; falling back to the detected range keeps template-only
        // exports visually consistent.
        if (columns.isEmpty()) {
            for (int column = layout.firstColorColumn(); column <= layout.lastColorColumn(); column++) {
                columns.add(column);
            }
        }

        Map<Short, CellStyle> whiteStyles = new LinkedHashMap<>();
        for (Integer column : columns) {
            if (column == null || column < 0) continue;
            if (!isBlankImageSlot(sheet, slot, column)) continue;

            /*
             * IMPORTANT: styling only the top-left cell of a merged range is
             * not enough for customer workbooks that already carry borders or
             * fills on the rows underneath the picture. Excel can still render
             * those row separators behind/under the image. Normalize EVERY cell
             * in the future picture slot first, then merge the slot vertically.
             * The result is one continuous white block exactly like the Product
             * Color text block below it.
             */
            normalizeWhiteImageSlotCells(workbook, sheet, slot, column, whiteStyles);
            if (!ensureVerticalMergedSlot(sheet, slot, column)) continue;

            CellRangeAddress merged = new CellRangeAddress(
                    slot.firstRow(),
                    slot.lastRowExclusive() - 1,
                    column,
                    column
            );
            // Remove the outline too. The picture area should be visually clean;
            // the Product Color information table below keeps its own borders.
            RegionUtil.setBorderTop(BorderStyle.NONE, merged, sheet);
            RegionUtil.setBorderBottom(BorderStyle.NONE, merged, sheet);
            RegionUtil.setBorderLeft(BorderStyle.NONE, merged, sheet);
            RegionUtil.setBorderRight(BorderStyle.NONE, merged, sheet);
        }
    }


    private void normalizeWhiteImageSlotCells(
            Workbook workbook,
            Sheet sheet,
            CellAddressRange slot,
            int column,
            Map<Short, CellStyle> whiteStyles
    ) {
        for (int rowIndex = slot.firstRow(); rowIndex < slot.lastRowExclusive(); rowIndex++) {
            Row row = getOrCreateRow(sheet, rowIndex);
            Cell cell = getOrCreateCell(row, column);
            CellStyle source = cell.getCellStyle();
            short styleId = source == null ? 0 : source.getIndex();
            CellStyle white = whiteStyles.get(styleId);
            if (white == null) {
                white = workbook.createCellStyle();
                if (source != null) white.cloneStyleFrom(source);
                white.setFillForegroundColor(IndexedColors.WHITE.getIndex());
                white.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                white.setAlignment(HorizontalAlignment.CENTER);
                white.setVerticalAlignment(VerticalAlignment.CENTER);
                white.setWrapText(false);
                white.setBorderTop(BorderStyle.NONE);
                white.setBorderBottom(BorderStyle.NONE);
                white.setBorderLeft(BorderStyle.NONE);
                white.setBorderRight(BorderStyle.NONE);
                whiteStyles.put(styleId, white);
            }
            cell.setCellStyle(white);
        }
    }

    private CellAddressRange productColorImageSlot(int headerRow) {
        int lastRowExclusive = Math.max(1, headerRow - 1); // leave the guide row directly above the detail header untouched
        int firstRow = Math.max(0, lastRowExclusive - 5); // five Excel rows, e.g. 6:10 when header is row 12
        if (lastRowExclusive <= firstRow) lastRowExclusive = firstRow + 1;
        return new CellAddressRange(firstRow, lastRowExclusive, -1, -1);
    }

    private boolean isBlankImageSlot(Sheet sheet, CellAddressRange slot, int column) {
        DataFormatter formatter = new DataFormatter(Locale.US);
        for (int rowIndex = slot.firstRow(); rowIndex < slot.lastRowExclusive(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) continue;
            String value = formatter.formatCellValue(cell).trim();
            if (hasText(value)) return false;
        }
        return true;
    }

    private boolean ensureVerticalMergedSlot(Sheet sheet, CellAddressRange slot, int column) {
        CellRangeAddress desired = new CellRangeAddress(
                slot.firstRow(),
                slot.lastRowExclusive() - 1,
                column,
                column
        );
        boolean exact = false;
        for (int index = sheet.getNumMergedRegions() - 1; index >= 0; index--) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            if (region == null || !region.intersects(desired)) continue;
            if (region.getFirstRow() == desired.getFirstRow()
                    && region.getLastRow() == desired.getLastRow()
                    && region.getFirstColumn() == desired.getFirstColumn()
                    && region.getLastColumn() == desired.getLastColumn()) {
                exact = true;
                break;
            }
            // Never destroy an intentional merge from a customer-specific BOM.
            return false;
        }
        if (!exact) sheet.addMergedRegion(desired);
        return true;
    }

    private CellAddressRange commentsImageArea(
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        int commentsRow = 1;
        int commentsColumn = Math.max(0, Math.min(layout.firstColorColumn() - 1, 8));
        for (int rowIndex = 0; rowIndex <= Math.min(Math.max(0, headerRow - 1), 18); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = 0; column < Math.min(Math.max(0, row.getLastCellNum()), 30); column++) {
                if (headerLabelMatches(text(row, column, formatter, evaluator), "COMMENTS")) {
                    commentsRow = rowIndex;
                    commentsColumn = column;
                    break;
                }
            }
        }

        int firstColumn = commentsColumn + 1;
        int lastColumnExclusive = Math.max(firstColumn + 1, layout.firstColorColumn());
        int firstRow = Math.min(Math.max(0, commentsRow + 1), Math.max(0, headerRow - 2));
        int lastRowExclusive = Math.max(firstRow + 1, headerRow > 1 ? headerRow - 1 : firstRow + 7);
        return new CellAddressRange(firstRow, lastRowExclusive, firstColumn, lastColumnExclusive);
    }

    private record CellAddressRange(int firstRow, int lastRowExclusive, int firstColumn, int lastColumnExclusive) { }

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
     * Fits a Product Color image onto a white canvas that has the same visual
     * aspect ratio as the merged Excel image slot. The original image keeps its
     * aspect ratio and is centered with a small margin, so square, landscape
     * and portrait images do not get stretched by the two-cell Excel anchor.
     *
     * If Java ImageIO cannot decode the source type, export falls back to the
     * original bytes and picture type rather than failing the BOM export.
     */
    private PreparedPicture prepareProductColorPicture(
            Sheet sheet,
            CellAddressRange slot,
            int column,
            byte[] sourceBytes,
            int sourcePictureType
    ) {
        if (sourceBytes == null || sourceBytes.length == 0 || sheet == null || slot == null || column < 0) {
            return new PreparedPicture(sourceBytes, sourcePictureType);
        }

        try (ByteArrayInputStream input = new ByteArrayInputStream(sourceBytes);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage source = ImageIO.read(input);
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return new PreparedPicture(sourceBytes, sourcePictureType);
            }

            double slotWidthPx = Math.max(1d, sheet.getColumnWidthInPixels(column));
            double slotHeightPx = 0d;
            for (int rowIndex = slot.firstRow(); rowIndex < slot.lastRowExclusive(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                double points = row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints();
                slotHeightPx += Math.max(1d, points * 96d / 72d);
            }
            slotHeightPx = Math.max(1d, slotHeightPx);

            // Use a reasonably sized canvas while preserving the Excel slot's
            // aspect ratio. Excel then fills the merged slot with this canvas,
            // while the actual product picture remains proportional inside it.
            double ratio = slotWidthPx / slotHeightPx;
            int canvasHeight = 600;
            int canvasWidth = Math.max(240, Math.min(1200, (int) Math.round(canvasHeight * ratio)));
            if (canvasWidth == 240 && ratio > 0d) {
                canvasHeight = Math.max(240, Math.min(1200, (int) Math.round(canvasWidth / ratio)));
            }

            BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, canvasWidth, canvasHeight);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int marginX = Math.max(6, (int) Math.round(canvasWidth * 0.06d));
                int marginY = Math.max(6, (int) Math.round(canvasHeight * 0.06d));
                int availableWidth = Math.max(1, canvasWidth - marginX * 2);
                int availableHeight = Math.max(1, canvasHeight - marginY * 2);
                double scale = Math.min(
                        availableWidth / (double) source.getWidth(),
                        availableHeight / (double) source.getHeight()
                );
                int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                int x = (canvasWidth - drawWidth) / 2;
                int y = (canvasHeight - drawHeight) / 2;
                graphics.drawImage(source, x, y, drawWidth, drawHeight, null);
            } finally {
                graphics.dispose();
            }

            if (!ImageIO.write(canvas, "png", output)) {
                return new PreparedPicture(sourceBytes, sourcePictureType);
            }
            return new PreparedPicture(output.toByteArray(), Workbook.PICTURE_TYPE_PNG);
        } catch (IOException | RuntimeException ignored) {
            return new PreparedPicture(sourceBytes, sourcePictureType);
        }
    }

    private record PreparedPicture(byte[] bytes, int pictureType) { }

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

    private void renameBomSheet(Workbook workbook, Sheet sheet) {
        if (workbook == null || sheet == null || "BOM".equals(sheet.getSheetName())) return;
        int sheetIndex = workbook.getSheetIndex(sheet);
        if (sheetIndex >= 0) workbook.setSheetName(sheetIndex, "BOM");
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
            ensureMprBomSourceHeaders(sheet);

            // Export only the final MPR data set. This defensive pass also
            // protects exports of older documents that may still contain
            // several saved rows with the same duplicate key.
            List<MprLine> lines = finalMprLinesForExport(mpr == null ? null : mpr.getLines());

            int lastDataRow = prepareMprDataRows(sheet, lines.size());
            updateMprTemplateSummary(sheet, lastDataRow);

            for (int index = 0; index < lines.size(); index++) {
                int rowIndex = MPR_DATA_START_ROW + index;
                Row row = sheet.getRow(rowIndex);
                if (row == null) row = sheet.createRow(rowIndex);

                row.setZeroHeight(false);
                writeMprTemplateLine(row, rowIndex + 1, lastDataRow + 1, lines.get(index));
            }
            writeMprImportMetadata(workbook, lines);
            applyMprVisualGrouping(workbook, sheet, lines);

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

    /**
     * Adds a hidden line-id map so an exported workbook can be edited and
     * uploaded again without relying on row order or visible business fields.
     */
    private void writeMprImportMetadata(Workbook workbook, List<MprLine> lines) {
        if (workbook == null) return;
        int existingIndex = workbook.getSheetIndex(MprExcelImportService.META_SHEET);
        if (existingIndex >= 0) workbook.removeSheetAt(existingIndex);

        Sheet meta = workbook.createSheet(MprExcelImportService.META_SHEET);
        Row header = meta.createRow(0);
        setCell(getOrCreateCell(header, 0), "DATA_ROW");
        setCell(getOrCreateCell(header, 1), "LINE_ID");
        setCell(getOrCreateCell(header, 2), "BOM_ID");
        setCell(getOrCreateCell(header, 3), "SOURCE_LINE_ID");
        setCell(getOrCreateCell(header, 4), "GENERATION_BATCH_ID");

        for (int index = 0; index < safe(lines).size(); index++) {
            MprLine line = safe(lines).get(index);
            if (line == null) continue;
            Row row = meta.createRow(index + 1);
            setCell(getOrCreateCell(row, 0), MPR_DATA_START_ROW + index + 1);
            setTextCell(getOrCreateCell(row, 1), line.getId());
            setTextCell(getOrCreateCell(row, 2), line.getBomId());
            setTextCell(getOrCreateCell(row, 3), line.getSourceLineId());
            setTextCell(getOrCreateCell(row, 4), line.getGenerationBatchId());
        }
        workbook.setSheetHidden(workbook.getSheetIndex(meta), true);
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
    /**
     * Restores the approved MPR(9) header without adding instruction rows or
     * merged cells. This keeps the downloaded file at two header rows and 33
     * columns, while the BOM fields are mapped correctly into F:K.
     */
    private void ensureMprBomSourceHeaders(Sheet sheet) {
        if (sheet == null) return;

        Row totals = getOrCreateRow(sheet, 0);
        totals.setZeroHeight(false);
        Row header = getOrCreateRow(sheet, MPR_HEADER_ROW);
        header.setZeroHeight(false);

        setCell(getOrCreateCell(header, MPR_BOM_NO_COL), "No.");
        setCell(getOrCreateCell(header, MPR_MATERIAL_TYPE_COL), "MTR\n(Material Type)");
        setCell(getOrCreateCell(header, MPR_POSITION_COL), "POSITION");
        setCell(getOrCreateCell(header, MPR_MATERIAL_COLOR_COL), "MAT COLOR");
        setCell(getOrCreateCell(header, MPR_UNIT_COL), "UNIT");
        setCell(getOrCreateCell(header, MPR_YIELD_COL), "NET");
        setCell(getOrCreateCell(header, MPR_LOSS_COL), "LOSS");
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

        // Excel row 4 in the approved template is a normal, unhighlighted data row.
        // Copying that style to every output row removes stale green/yellow/black
        // sample formatting before dynamic BOM/color/packing grouping is applied.
        int normalStyleRow = Math.min(Math.max(MPR_DATA_START_ROW + 1, 0), originalLastRow);

        for (int rowIndex = MPR_DATA_START_ROW; rowIndex <= lastRowToPrepare; rowIndex++) {
            if (normalStyleRow >= MPR_DATA_START_ROW && sheet.getRow(normalStyleRow) != null) {
                copyRowStyle(sheet, normalStyleRow, rowIndex, MPR_LAST_COLUMN);
            } else if (rowIndex > originalLastRow) {
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
                "IFERROR(SUBTOTAL(9," + excelColumn(MPR_PO_QTY_COL) + firstExcelDataRow + ":"
                        + excelColumn(MPR_PO_QTY_COL) + lastExcelDataRow + "),0)");
        setCellFormula(sheet, 0, MPR_PURCHASE_QTY_COL,
                "IFERROR(SUBTOTAL(9," + excelColumn(MPR_PURCHASE_QTY_COL) + firstExcelDataRow + ":"
                        + excelColumn(MPR_PURCHASE_QTY_COL) + lastExcelDataRow + "),0)");
        setCellFormula(sheet, 0, MPR_AMOUNT_USD_COL,
                "IFERROR(SUBTOTAL(9," + excelColumn(MPR_AMOUNT_USD_COL) + firstExcelDataRow + ":"
                        + excelColumn(MPR_AMOUNT_USD_COL) + lastExcelDataRow + "),0)");

    }

    /**
     * Keeps exactly one exported row for each final MPR duplicate key.
     * The service normally supplies an already-consolidated document, but the
     * exporter repeats the rule so a legacy/raw document can never produce
     * duplicate rows in Excel.
     */
    private List<MprLine> finalMprLinesForExport(List<MprLine> sourceLines) {
        Map<String, MprLine> survivorByKey = new LinkedHashMap<>();
        for (MprLine line : safe(sourceLines)) {
            if (line == null) continue;
            survivorByKey.putIfAbsent(mprExportDuplicateKey(line), line);
        }
        return new ArrayList<>(survivorByKey.values());
    }

    private String mprExportDuplicateKey(MprLine line) {
        String color = firstNonBlank(line == null ? null : line.getStyleColor(),
                line == null ? null : line.getProductColorId());
        return normalize(line == null ? null : line.getBomId())
                + "|" + normalize(color)
                + "|" + mprExportMaterialIdentityKey(line)
                + "|" + decimalKey(line == null ? null : line.getSourceDetailConsumption())
                + "|" + decimalKey(line == null ? null : line.getYield());
    }

    private String mprExportMaterialIdentityKey(MprLine line) {
        if (line == null) return "";
        String sapCode = normalize(line.getSapCode());
        if (!sapCode.isEmpty()) {
            return "SAP|" + sapCode
                    + "|" + normalize(line.getMatColor())
                    + "|" + normalize(line.getMatUnit());
        }
        return "MAT|" + normalize(line.getMaterialType())
                + "|" + normalize(firstNonBlank(line.getMatFullDescription(), line.getPosition()))
                + "|" + normalize(line.getMatColor())
                + "|" + normalize(line.getMatUnit());
    }

    private String decimalKey(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * Adds clear visual boundaries without inserting extra rows, so all template
     * formulas, filters and print settings remain valid.
     */
    private void applyMprVisualGrouping(Workbook workbook, Sheet sheet, List<MprLine> lines) {
        if (workbook == null || sheet == null || lines == null || lines.isEmpty()) return;

        Map<String, CellStyle> styleCache = new LinkedHashMap<>();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        CreationHelper helper = workbook.getCreationHelper();
        String previousBom = null;
        String previousColor = null;
        String previousSource = null;

        for (int index = 0; index < lines.size(); index++) {
            MprLine line = lines.get(index);
            if (line == null) continue;
            int rowIndex = MPR_DATA_START_ROW + index;
            Row row = getOrCreateRow(sheet, rowIndex);

            String bomKey = normalize(line.getBomId());
            String colorKey = bomKey + "|" + normalize(firstNonBlank(line.getStyleColor(), line.getProductColorId()));
            String sourceKey = colorKey + "|" + mprSourceGroupKey(line);
            boolean newBom = !Objects.equals(previousBom, bomKey);
            boolean newColor = newBom || !Objects.equals(previousColor, colorKey);
            boolean newSource = newColor || !Objects.equals(previousSource, sourceKey);

            // Keep only business grouping in the exported final MPR:
            // - first row of every BOM/Product Color is green #92D050;
            // - Packing changes use only a thin orange top line.
            // Consolidated/removed duplicate rows are already absent from the final
            // data set, so the survivor is not highlighted or annotated as duplicate.
            String fillHex = null;
            BorderStyle topBorder = BorderStyle.NONE;
            short topColor = IndexedColors.AUTOMATIC.getIndex();
            String styleKind = "NORMAL";

            if (newBom || newColor) {
                fillHex = "92D050";
                styleKind = "COLOR_GROUP";
            } else if (newSource) {
                topBorder = BorderStyle.THIN;
                topColor = IndexedColors.ORANGE.getIndex();
                styleKind = "PACKING_GROUP";
            }


            if (fillHex != null || topBorder != BorderStyle.NONE) {
                for (int column = MPR_STYLE_COLOR_KEY_COL; column <= MPR_LAST_COLUMN; column++) {
                    Cell cell = getOrCreateCell(row, column);
                    String cacheKey = cell.getCellStyle().getIndex() + "|" + styleKind
                            + "|" + fillHex + "|" + topBorder + "|" + topColor;
                    CellStyle grouped = styleCache.get(cacheKey);
                    if (grouped == null) {
                        grouped = workbook.createCellStyle();
                        grouped.cloneStyleFrom(cell.getCellStyle());
                        if (fillHex != null) applyMprFill(grouped, fillHex);
                        if (topBorder != BorderStyle.NONE) {
                            grouped.setBorderTop(topBorder);
                            grouped.setTopBorderColor(topColor);
                        }
                        styleCache.put(cacheKey, grouped);
                    }
                    cell.setCellStyle(grouped);
                }
            }

            if (newBom || newColor || newSource) {
                String boundary = "BOM: " + firstNonBlank(line.getBomNo(), line.getBomName(), line.getBomId())
                        + "\nProduct Color: " + firstNonBlank(line.getStyleColor(), line.getProductColorId(), "-")
                        + "\nSource: " + mprSourceGroupLabel(line);
                addMprCellComment(helper, drawing, getOrCreateCell(row, MPR_STYLE_COLOR_KEY_COL), boundary);
            }
            previousBom = bomKey;
            previousColor = colorKey;
            previousSource = sourceKey;
        }
    }

    private void applyMprFill(CellStyle style, String rgbHex) {
        if (style == null || rgbHex == null || rgbHex.length() != 6) return;
        if (style instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle xssfStyle) {
            byte[] rgb = new byte[] {
                    (byte) Integer.parseInt(rgbHex.substring(0, 2), 16),
                    (byte) Integer.parseInt(rgbHex.substring(2, 4), 16),
                    (byte) Integer.parseInt(rgbHex.substring(4, 6), 16)
            };
            xssfStyle.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(
                    rgb,
                    new org.apache.poi.xssf.usermodel.DefaultIndexedColorMap()
            ));
        } else {
            style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        }
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    private String mprSourceGroupKey(MprLine line) {
        if (line == null || !"PACKING".equalsIgnoreCase(trim(line.getSection()))) return "CORE";
        return "PACKING|" + normalize(firstNonBlank(line.getPackingId(), line.getPackingName()));
    }

    private String mprSourceGroupLabel(MprLine line) {
        if (line == null || !"PACKING".equalsIgnoreCase(trim(line.getSection()))) {
            return "Core BOM (No Packing)";
        }
        return "Packing: " + firstNonBlank(line.getPackingName(), line.getPackingId(), "Packing");
    }

    private void addMprCellComment(CreationHelper helper, Drawing<?> drawing, Cell cell, String text) {
        if (helper == null || drawing == null || cell == null || !hasText(text)) return;
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(Math.min(MPR_LAST_COLUMN + 1, cell.getColumnIndex() + 5));
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 5);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(text));
        comment.setAuthor("BOM & MPR Software");
        cell.setCellComment(comment);
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

        // F-K: exact BOM-source block No./MTR/POSITION/MAT COLOR/UNIT/NET.
        setCell(getOrCreateCell(row, MPR_BOM_NO_COL), line.getBomLineNo());
        setCell(getOrCreateCell(row, MPR_MATERIAL_TYPE_COL), line.getMaterialType());
        setCell(getOrCreateCell(row, MPR_POSITION_COL), line.getPosition());
        setCell(getOrCreateCell(row, MPR_MATERIAL_COLOR_COL), line.getMatColor());
        setCell(getOrCreateCell(row, MPR_UNIT_COL), line.getMatUnit());
        setCell(getOrCreateCell(row, MPR_YIELD_COL), numericOrZero(line.getYield()));
        setCell(getOrCreateCell(row, MPR_LOSS_COL), numericOrZero(line.getLossFactor()));

        // M-V: approved L.L.BEAN workbook formulas with zero-safe inputs.
        // PO Qty is supplied by the system. Blank numeric inputs are written as 0.
        setCellFormula(row, MPR_TOTAL_YIELD_COL,
                "N(" + excelColumn(MPR_YIELD_COL) + excelRow + ")*N("
                        + excelColumn(MPR_LOSS_COL) + excelRow + ")");
        setCell(getOrCreateCell(row, MPR_PO_QTY_COL), numericOrZero(line.getPoQuantity()));
        setCellFormula(row, MPR_REQUIRED_QTY_COL,
                "N(" + excelColumn(MPR_TOTAL_YIELD_COL) + excelRow + ")*N("
                        + excelColumn(MPR_PO_QTY_COL) + excelRow + ")");
        setCell(getOrCreateCell(row, MPR_SAMPLE_QTY_COL), numericOrZero(line.getSampleQuantity()));
        setCellFormula(row, MPR_SAMPLE_MATERIAL_QTY_COL,
                "N(" + excelColumn(MPR_SAMPLE_QTY_COL) + excelRow + ")*N("
                        + excelColumn(MPR_YIELD_COL) + excelRow + ")");
        setCell(getOrCreateCell(row, MPR_MCD_STOCK_COL), numericOrZero(line.getMcdStock()));
        setCell(getOrCreateCell(row, MPR_CMCD_STOCK_COL), numericOrZero(line.getCmcdStock()));
        setCellFormula(row, MPR_SAP_STOCK_COL,
                "N(" + excelColumn(MPR_MCD_STOCK_COL) + excelRow + ")+N("
                        + excelColumn(MPR_CMCD_STOCK_COL) + excelRow + ")");
        setCell(getOrCreateCell(row, MPR_NON_SAP_STOCK_COL), numericOrZero(line.getNonSapStockQuantity()));
        setCellFormula(row, MPR_PURCHASE_QTY_COL,
                "MAX(0,N(" + excelColumn(MPR_REQUIRED_QTY_COL) + excelRow + ")+N("
                        + excelColumn(MPR_SAMPLE_MATERIAL_QTY_COL) + excelRow + ")-N("
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + ")-N("
                        + excelColumn(MPR_NON_SAP_STOCK_COL) + excelRow + "))");

        // W-AB: MAT_INFO and Vendor Code snapshot.
        setCell(getOrCreateCell(row, MPR_CURRENCY_COL), line.getCurrency());
        setCell(getOrCreateCell(row, MPR_PRICE_COL), numericOrZero(line.getMatPriceWithoutTax()));
        setCell(getOrCreateCell(row, MPR_SHORT_SUPPLIER_COL), line.getShortNameSupplier());
        setTextCell(getOrCreateCell(row, MPR_VENDOR_CODE_COL), vendorCodeText(line.getVendorCode()));
        setCell(getOrCreateCell(row, MPR_VENDOR_NAME_COL), line.getVendorName());
        setCell(getOrCreateCell(row, MPR_MAT_CHARGER_COL), line.getMatCharger());

        // AC-AE: Exchange Rate is supplied by the system; AD/AE match the approved workbook.
        setCell(getOrCreateCell(row, MPR_EXCHANGE_RATE_COL), numericOrZero(line.getExchangeRate()));
        setCellFormula(row, MPR_PRICE_USD_COL,
                "IFERROR(N(" + excelColumn(MPR_PRICE_COL) + excelRow + ")/N("
                        + excelColumn(MPR_EXCHANGE_RATE_COL) + excelRow + "),0)");
        setCellFormula(row, MPR_AMOUNT_USD_COL,
                "ROUND((N(" + excelColumn(MPR_PURCHASE_QTY_COL) + excelRow + ")+N("
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + "))*N("
                        + excelColumn(MPR_PRICE_USD_COL) + excelRow + "),2)");

        setCell(getOrCreateCell(row, MPR_DUE_DATE_COL), line.getMatDueDate());
        setCellFormula(row, MPR_TOTAL_STYLE_AMOUNT_COL,
                "IF(" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + excelRow + "=\"\",0,"
                        + "IFERROR(SUMIF($" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + "$" + (MPR_DATA_START_ROW + 1)
                        + ":$" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + "$" + lastExcelDataRow + ","
                        + excelColumn(MPR_STYLE_COLOR_KEY_COL) + excelRow + ",$"
                        + excelColumn(MPR_AMOUNT_USD_COL) + "$" + (MPR_DATA_START_ROW + 1)
                        + ":$" + excelColumn(MPR_AMOUNT_USD_COL) + "$" + lastExcelDataRow + "),0))");
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

    private BigDecimal numericOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) if (hasText(value)) return value.trim();
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
