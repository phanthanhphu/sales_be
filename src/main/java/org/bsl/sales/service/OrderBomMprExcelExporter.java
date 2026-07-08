package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.bsl.sales.model.BomAttachment;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomHeader;
import org.bsl.sales.model.BomLine;
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
    private static final int DETAIL_HEADER_FIRST_COLOR_COLUMN = 17;
    private static final int DETAIL_LAST_STANDARD_COLUMN = 16;
    private static final int MANUAL_IMAGE_FIRST_COLUMN = 25; // Z column, outside the A:Y BOM table.

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
            writeRow(sheet, row++, "BOM No", bom.getBomNo(), "BOM Name", bom.getBomName(), "Status", bom.getStatus());
            writeRow(sheet, row++, "Buyer", bom.getHeader().getBuyer(), "Season", bom.getHeader().getSeason(), "Style", bom.getHeader().getStyleName());
            row++;

            String[] headers = {
                    "No.", "MTR (Material Type)", "SAP CODE", "No.", "POSITION", "Position Description", "Position Description 2",
                    "P", "X", "Y", "Q.TY", "><", "COSTING / MK", "COSTING / UNIT", "CONSUMPTION / NET", "CONSUMPTION / UNIT", "B.O.M REMARKS"
            };
            Row header = sheet.createRow(row++);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            for (BomLine line : safe(bom.getCoreLines())) row = writeFallbackLine(sheet, row, line);
            for (BomPacking packing : safe(bom.getPackings())) {
                Row packingRow = sheet.createRow(row++);
                Cell cell = packingRow.createCell(0);
                cell.setCellValue(packing.getPackingName());
                cell.setCellStyle(headerStyle);
                for (BomLine line : safe(packing.getLines())) row = writeFallbackLine(sheet, row, line);
            }

            for (int c = 0; c < 26; c++) sheet.autoSizeColumn(c);
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

        Map<String, Integer> colorColumns = findColorColumns(sheet, headerRow, formatter, evaluator);
        patchHeader(sheet, bom.getHeader(), formatter, evaluator);
        patchProductColorHeaders(sheet, headerRow, bom, colorColumns);

        for (Integer deletedRow : safe(bom.getDeletedSourceRows())) {
            if (deletedRow != null && deletedRow > 0) {
                clearLineRow(sheet, deletedRow - 1, colorColumns.values());
            }
        }

        for (BomLine line : safe(bom.getCoreLines())) {
            if (line.getSourceRowNumber() != null) patchLineAt(sheet, line.getSourceRowNumber() - 1, line, bom, colorColumns);
        }
        for (BomPacking packing : safe(bom.getPackings())) {
            for (BomLine line : safe(packing.getLines())) {
                if (line.getSourceRowNumber() != null) patchLineAt(sheet, line.getSourceRowNumber() - 1, line, bom, colorColumns);
            }
        }

        appendNewLines(sheet, headerRow, bom, colorColumns);
        embedManualImages(workbook, sheet, bom, colorColumns);
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
        patchHeaderValue(sheet, "FACTORY PRODUCT", header.getFactoryProduct(), formatter, evaluator);
        patchHeaderValue(sheet, "BOM MAKER", header.getBomMaker(), formatter, evaluator);
        patchHeaderValue(sheet, "SIZE", header.getSize(), formatter, evaluator);
        patchHeaderValue(sheet, "BOM DATE", header.getBomDate(), formatter, evaluator);
    }

    private void patchHeaderValue(Sheet sheet, String label, String value, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (value == null) return;
        String expected = normalize(label);

        for (int rowIndex = 0; rowIndex <= Math.min(sheet.getLastRowNum(), 15); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            for (int column = 0; column < Math.min(Math.max(0, row.getLastCellNum()), 30); column++) {
                if (!normalize(text(row, column, formatter, evaluator)).contains(expected)) continue;

                Cell target = firstExistingValueCell(row, column + 1, column + 4);
                if (target == null) target = getOrCreateCell(row, column + 1);
                setCell(target, value);
                return;
            }
        }
    }

    private Cell firstExistingValueCell(Row row, int fromColumn, int toColumn) {
        for (int column = fromColumn; column <= toColumn; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() != CellType.BLANK) return cell;
        }
        return null;
    }

    private void patchLineAt(Sheet sheet, int rowIndex, BomLine line, BomDocument bom, Map<String, Integer> colorColumns) {
        if (rowIndex < 0) return;
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);

        setCell(getOrCreateCell(row, 0), line.getMaterialGroupNo());
        setCell(getOrCreateCell(row, 1), line.getMaterialType());
        setCell(getOrCreateCell(row, 2), line.getSapCode());
        setCell(getOrCreateCell(row, 3), line.getDetailNo());
        setCell(getOrCreateCell(row, 4), line.getPosition());
        setCell(getOrCreateCell(row, 5), line.getPositionDescription());
        setCell(getOrCreateCell(row, 6), line.getPositionDescriptionExtra());
        setCell(getOrCreateCell(row, 7), line.getPieceCode());
        setCell(getOrCreateCell(row, 8), line.getDimensionX());
        setCell(getOrCreateCell(row, 9), line.getDimensionY());
        setCell(getOrCreateCell(row, 10), line.getQuantity());
        setCell(getOrCreateCell(row, 11), line.getDirection());
        setCell(getOrCreateCell(row, 12), line.getCosting());
        setCell(getOrCreateCell(row, 13), line.getCostingUnit());
        setCell(getOrCreateCell(row, 14), line.getConsumptionNet());
        setCell(getOrCreateCell(row, 15), line.getConsumptionUnit());
        setCell(getOrCreateCell(row, 16), line.getBomRemark());

        for (Map.Entry<String, Integer> entry : colorColumns.entrySet()) {
            setCell(getOrCreateCell(row, entry.getValue()), productColorValue(line, bom, entry.getKey()));
        }
    }

    /** Updates the three Product Color header rows in the original template. */
    private void patchProductColorHeaders(Sheet sheet, int headerRow, BomDocument bom, Map<String, Integer> colorColumns) {
        for (BomProductColor productColor : productColors(bom)) {
            Integer column = productColor.getSourceColumnIndex();
            if (column == null || column < DETAIL_HEADER_FIRST_COLOR_COLUMN) {
                column = colorColumns.get(productColor.getColorName());
            }
            if (column == null) continue;

            Row colorRow = sheet.getRow(headerRow);
            if (colorRow == null) colorRow = sheet.createRow(headerRow);
            Row patternRow = sheet.getRow(headerRow + 1);
            if (patternRow == null) patternRow = sheet.createRow(headerRow + 1);
            Row seasonRow = sheet.getRow(headerRow + 2);
            if (seasonRow == null) seasonRow = sheet.createRow(headerRow + 2);

            setCell(getOrCreateCell(colorRow, column), productColor.getColorName());
            setCell(getOrCreateCell(patternRow, column), productColor.getPatternNumber());
            setCell(getOrCreateCell(seasonRow, column), productColor.getSeason());
        }
    }

    /** Returns a value through the Product Color item link, with old colorValues as a fallback. */
    private String productColorValue(BomLine line, BomDocument bom, String colorName) {
        BomProductColor productColor = productColors(bom).stream()
                .filter(item -> normalize(item.getColorName()).equals(normalize(colorName)))
                .findFirst()
                .orElse(null);

        if (productColor != null) {
            for (BomLineColorValue value : safe(line.getProductColorValues())) {
                if (value != null && productColor.getId() != null && productColor.getId().equals(value.getProductColorId())) {
                    return value.getValue();
                }
            }
        }

        if (line.getColorValues() != null) {
            for (Map.Entry<String, String> entry : line.getColorValues().entrySet()) {
                if (normalize(entry.getKey()).equals(normalize(colorName))) return entry.getValue();
            }
        }
        return null;
    }

    private String resolveProductColorName(BomDocument bom, String productColorId, String legacyColorKey) {
        if (productColorId != null && !productColorId.isBlank()) {
            for (BomProductColor productColor : productColors(bom)) {
                if (productColorId.equals(productColor.getId())) return productColor.getColorName();
            }
        }
        return legacyColorKey == null ? "" : legacyColorKey;
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

    private void clearLineRow(Sheet sheet, int rowIndex, Collection<Integer> colorColumns) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) return;
        for (int column = 0; column <= DETAIL_LAST_STANDARD_COLUMN; column++) clearCell(row, column);
        for (Integer column : colorColumns) clearCell(row, column);
    }

    private void appendNewLines(Sheet sheet, int headerRow, BomDocument bom, Map<String, Integer> colorColumns) {
        List<BomLine> newCoreLines = safe(bom.getCoreLines()).stream().filter(line -> line.getSourceRowNumber() == null).toList();
        if (!newCoreLines.isEmpty()) {
            int insertion = firstPackingRow(sheet, headerRow + 1);
            if (insertion < 0) insertion = sheet.getLastRowNum() + 1;
            for (BomLine line : newCoreLines) {
                insertStyledLine(sheet, insertion, Math.max(headerRow + 3, insertion - 1), line, bom, colorColumns);
                insertion++;
            }
        }

        for (BomPacking packing : safe(bom.getPackings())) {
            List<BomLine> newLines = safe(packing.getLines()).stream().filter(line -> line.getSourceRowNumber() == null).toList();
            if (newLines.isEmpty()) continue;

            int packingTitleRow = findPackingRow(sheet, packing.getPackingName());
            if (packingTitleRow < 0) {
                packingTitleRow = appendPackingTitle(sheet, packing.getPackingName(), headerRow);
            }

            int nextPacking = firstPackingRow(sheet, packingTitleRow + 1);
            int insertion = nextPacking < 0 ? sheet.getLastRowNum() + 1 : nextPacking;
            int styleRow = Math.max(packingTitleRow + 1, insertion - 1);
            for (BomLine line : newLines) {
                insertStyledLine(sheet, insertion, styleRow, line, bom, colorColumns);
                insertion++;
                styleRow++;
            }
        }
    }

    private int appendPackingTitle(Sheet sheet, String packingName, int headerRow) {
        int newRowIndex = sheet.getLastRowNum() + 1;
        int templateRow = firstPackingRow(sheet, headerRow + 1);
        if (templateRow >= 0) copyRowStyle(sheet, templateRow, newRowIndex, Math.max(DETAIL_LAST_STANDARD_COLUMN, sheet.getRow(templateRow).getLastCellNum()));
        Row row = sheet.getRow(newRowIndex);
        if (row == null) row = sheet.createRow(newRowIndex);
        setCell(getOrCreateCell(row, 0), packingName);
        return newRowIndex;
    }

    private void insertStyledLine(Sheet sheet, int insertionRow, int templateRow, BomLine line, BomDocument bom, Map<String, Integer> colorColumns) {
        int lastRow = sheet.getLastRowNum();
        if (insertionRow <= lastRow) sheet.shiftRows(insertionRow, lastRow, 1, true, false);
        copyRowStyle(sheet, Math.min(Math.max(0, templateRow), sheet.getLastRowNum()), insertionRow, maxTableColumn(colorColumns));
        patchLineAt(sheet, insertionRow, line, bom, colorColumns);
    }

    private int maxTableColumn(Map<String, Integer> colorColumns) {
        return Math.max(DETAIL_LAST_STANDARD_COLUMN, colorColumns.values().stream().mapToInt(Integer::intValue).max().orElse(DETAIL_LAST_STANDARD_COLUMN));
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

    private void embedManualImages(Workbook workbook, Sheet sheet, BomDocument bom, Map<String, Integer> colorColumns) {
        for (BomAttachment attachment : allAttachments(bom)) {
            if (attachment.isImportedFromExcel() || !isImage(attachment)) continue;
            if (!hasText(attachment.getStoredFileName())) continue;

            try (InputStream input = fileStorage.load(attachment.getStoredFileName()).getInputStream()) {
                byte[] data = input.readAllBytes();
                byte[] pictureBytes = normalizePictureBytes(attachment, data);
                int pictureIndex = workbook.addPicture(pictureBytes, pictureType(attachment));
                Drawing<?> drawing = sheet.createDrawingPatriarch();
                ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();

                int column = MANUAL_IMAGE_FIRST_COLUMN;
                int row = 0;
                String scope = attachment.getScope() == null ? "BOM" : attachment.getScope().toUpperCase(Locale.ROOT);

                String productColorName = resolveProductColorName(bom, attachment.getProductColorId(), attachment.getColorKey());
                if ("COLOR".equals(scope) && colorColumns.containsKey(productColorName)) {
                    column = colorColumns.get(productColorName);
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
            if (value.contains("MTR") && value.contains("SAP") && value.contains("CONSUMPTION")) return rowIndex;
        }
        return -1;
    }

    private Map<String, Integer> findColorColumns(Sheet sheet, int headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Row header = sheet.getRow(headerRow);
        if (header == null) return result;

        for (int column = DETAIL_HEADER_FIRST_COLOR_COLUMN; column <= Math.max(DETAIL_HEADER_FIRST_COLOR_COLUMN, header.getLastCellNum() - 1); column++) {
            String color = text(header, column, formatter, evaluator);
            if (!hasText(color)) color = text(sheet.getRow(headerRow + 1), column, formatter, evaluator);
            if (!hasText(color)) color = text(sheet.getRow(headerRow + 2), column, formatter, evaluator);
            if (hasText(color) && !normalize(color).startsWith("F26")) result.put(color.trim(), column);
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
                "IF(OR(" + excelColumn(MPR_YIELD_COL) + excelRow + "=\"\","
                        + excelColumn(MPR_LOSS_COL) + excelRow + "=\"\"),\"\","
                        + excelColumn(MPR_YIELD_COL) + excelRow + "*"
                        + excelColumn(MPR_LOSS_COL) + excelRow + ")");
        setCell(getOrCreateCell(row, MPR_PO_QTY_COL), line.getPoQuantity());
        setCellFormula(row, MPR_REQUIRED_QTY_COL,
                "IF(OR(" + excelColumn(MPR_TOTAL_YIELD_COL) + excelRow + "=\"\","
                        + excelColumn(MPR_PO_QTY_COL) + excelRow + "=\"\"),\"\","
                        + excelColumn(MPR_TOTAL_YIELD_COL) + excelRow + "*"
                        + excelColumn(MPR_PO_QTY_COL) + excelRow + ")");
        setCell(getOrCreateCell(row, MPR_SAMPLE_QTY_COL), line.getSampleQuantity());
        setCellFormula(row, MPR_SAMPLE_MATERIAL_QTY_COL,
                "IF(OR(" + excelColumn(MPR_SAMPLE_QTY_COL) + excelRow + "=\"\","
                        + excelColumn(MPR_YIELD_COL) + excelRow + "=\"\"),\"\","
                        + excelColumn(MPR_SAMPLE_QTY_COL) + excelRow + "*"
                        + excelColumn(MPR_YIELD_COL) + excelRow + ")");
        setCell(getOrCreateCell(row, MPR_MCD_STOCK_COL), line.getMcdStock());
        setCell(getOrCreateCell(row, MPR_CMCD_STOCK_COL), line.getCmcdStock());
        setCellFormula(row, MPR_SAP_STOCK_COL,
                "IF(COUNT(" + excelColumn(MPR_MCD_STOCK_COL) + excelRow + ":"
                        + excelColumn(MPR_CMCD_STOCK_COL) + excelRow + ")=0,\"\","
                        + excelColumn(MPR_MCD_STOCK_COL) + excelRow + "+"
                        + excelColumn(MPR_CMCD_STOCK_COL) + excelRow + ")");
        setCell(getOrCreateCell(row, MPR_NON_SAP_STOCK_COL), line.getNonSapStockQuantity());
        setCellFormula(row, MPR_PURCHASE_QTY_COL,
                "IF(COUNT(" + excelColumn(MPR_REQUIRED_QTY_COL) + excelRow + ","
                        + excelColumn(MPR_SAMPLE_MATERIAL_QTY_COL) + excelRow + ","
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + ","
                        + excelColumn(MPR_NON_SAP_STOCK_COL) + excelRow + ")=0,\"\","
                        + "MAX(0,"
                        + excelColumn(MPR_REQUIRED_QTY_COL) + excelRow + "+"
                        + excelColumn(MPR_SAMPLE_MATERIAL_QTY_COL) + excelRow + "-"
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + "-"
                        + excelColumn(MPR_NON_SAP_STOCK_COL) + excelRow + "))");

        // X-AC: MAT_INFO and Vendor Code snapshot.
        setCell(getOrCreateCell(row, MPR_CURRENCY_COL), line.getCurrency());
        setCell(getOrCreateCell(row, MPR_PRICE_COL), line.getMatPriceWithoutTax());
        setCell(getOrCreateCell(row, MPR_SHORT_SUPPLIER_COL), line.getShortNameSupplier());
        setCell(getOrCreateCell(row, MPR_VENDOR_CODE_COL), line.getVendorCode());
        setCell(getOrCreateCell(row, MPR_VENDOR_NAME_COL), line.getVendorName());
        setCell(getOrCreateCell(row, MPR_MAT_CHARGER_COL), line.getMatCharger());

        // AD-AH: Currency snapshot and final MPR calculations.
        setCell(getOrCreateCell(row, MPR_EXCHANGE_RATE_COL), line.getExchangeRate());
        setCellFormula(row, MPR_PRICE_USD_COL,
                "IF(OR(" + excelColumn(MPR_PRICE_COL) + excelRow + "=\"\","
                        + excelColumn(MPR_EXCHANGE_RATE_COL) + excelRow + "=\"\"),\"\","
                        + excelColumn(MPR_PRICE_COL) + excelRow + "/"
                        + excelColumn(MPR_EXCHANGE_RATE_COL) + excelRow + ")");
        setCellFormula(row, MPR_AMOUNT_USD_COL,
                "IF(" + excelColumn(MPR_PRICE_USD_COL) + excelRow + "=\"\",\"\","
                        + "ROUND(("
                        + excelColumn(MPR_PURCHASE_QTY_COL) + excelRow + "*"
                        + excelColumn(MPR_PRICE_USD_COL) + excelRow + ")+("
                        + excelColumn(MPR_SAP_STOCK_COL) + excelRow + "*"
                        + excelColumn(MPR_PRICE_USD_COL) + excelRow + "),2))");
        setCell(getOrCreateCell(row, MPR_DUE_DATE_COL), line.getMatDueDate());
        setCellFormula(row, MPR_TOTAL_STYLE_AMOUNT_COL,
                "IF(" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + excelRow + "=\"\",\"\","
                        + "SUMIF($" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + "$" + (MPR_DATA_START_ROW + 1)
                        + ":$" + excelColumn(MPR_STYLE_COLOR_KEY_COL) + "$" + lastExcelDataRow + ","
                        + excelColumn(MPR_STYLE_COLOR_KEY_COL) + excelRow + ",$"
                        + excelColumn(MPR_AMOUNT_USD_COL) + "$" + (MPR_DATA_START_ROW + 1)
                        + ":$" + excelColumn(MPR_AMOUNT_USD_COL) + "$" + lastExcelDataRow + "))");
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

    private int writeFallbackLine(Sheet sheet, int rowNo, BomLine line) {
        Row row = sheet.createRow(rowNo++);
        Object[] values = {line.getMaterialGroupNo(), line.getMaterialType(), line.getSapCode(), line.getDetailNo(), line.getPosition(), line.getPositionDescription(), line.getPositionDescriptionExtra(), line.getPieceCode(), line.getDimensionX(), line.getDimensionY(), line.getQuantity(), line.getDirection(), line.getCosting(), line.getCostingUnit(), line.getConsumptionNet(), line.getConsumptionUnit(), line.getBomRemark()};
        for (int i = 0; i < values.length; i++) setCell(row.createCell(i), values[i]);
        return rowNo;
    }

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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
