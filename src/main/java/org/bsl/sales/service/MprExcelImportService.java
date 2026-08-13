package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads an exported MPR workbook back into the four Sales-owned editable inputs:
 * SAMPLE QTY, MCD STOCK, CMCD STOCK and NON SAP STOCK QTY.
 *
 * Other visible columns are read only when needed to match older exports that do
 * not contain the hidden LINE_ID metadata. Their values are never written back
 * to the MPR by the import flow.
 */
@Service
public class MprExcelImportService {
    static final String META_SHEET = "_MPR_META";
    private static final int HEADER_ROW = 1;
    private static final int DATA_START_ROW = 2;

    public List<ImportedMprRow> parse(MultipartFile file) {
        validateFile(file);
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet("MPR");
            if (sheet == null && workbook.getNumberOfSheets() > 0) sheet = workbook.getSheetAt(0);
            if (sheet == null) throw new OrderBomMprValidationException("MPR worksheet was not found");

            DataFormatter formatter = new DataFormatter(Locale.US);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            validateHeader(sheet, formatter, evaluator);
            Map<Integer, String> lineIdsByExcelRow = readLineIds(workbook, formatter, evaluator);

            List<ImportedMprRow> rows = new ArrayList<>();
            for (int rowIndex = DATA_START_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || !hasMprData(row, formatter, evaluator)) continue;

                int excelRow = rowIndex + 1;
                String lineId = trim(lineIdsByExcelRow.get(excelRow));

                // Current exports always contain the hidden LINE_ID metadata.
                // Only read reference columns when an older workbook needs the
                // legacy fallback matcher; edits to those columns are otherwise
                // deliberately ignored.
                String rowFallbackKey = "";
                if (!hasText(lineId)) {
                    String styleDescription = text(row, 1, formatter, evaluator);
                    String styleColor = text(row, 2, formatter, evaluator);
                    Integer bomLineNo = integer(row, 5, formatter, evaluator);
                    String materialType = text(row, 6, formatter, evaluator);
                    String position = text(row, 7, formatter, evaluator);
                    String matColor = text(row, 8, formatter, evaluator);
                    String matUnit = text(row, 9, formatter, evaluator);
                    rowFallbackKey = fallbackKey(
                            styleDescription, styleColor, bomLineNo, materialType, position, matColor, matUnit
                    );
                }

                rows.add(new ImportedMprRow(
                        excelRow,
                        lineId,
                        rowFallbackKey,
                        decimal(row, 15, formatter, evaluator),
                        decimal(row, 17, formatter, evaluator),
                        decimal(row, 18, formatter, evaluator),
                        decimal(row, 20, formatter, evaluator)
                ));
            }

            if (rows.isEmpty()) {
                throw new OrderBomMprValidationException("The uploaded workbook does not contain any MPR data rows");
            }
            return rows;
        } catch (OrderBomMprValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OrderBomMprValidationException("Unable to read MPR Excel: " + ex.getMessage());
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new OrderBomMprValidationException("Select an MPR Excel file to upload");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw new OrderBomMprValidationException("MPR upload supports .xlsx or .xls files only");
        }
        if (file.getSize() > 25L * 1024L * 1024L) {
            throw new OrderBomMprValidationException("MPR Excel file must not exceed 25 MB");
        }
    }

    private void validateHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(HEADER_ROW);
        if (header == null
                || !normalize(text(header, 1, formatter, evaluator)).contains("STYLEDESCRIPTION")
                || !normalize(text(header, 6, formatter, evaluator)).contains("MTR")
                || !normalize(text(header, 7, formatter, evaluator)).contains("POSITION")
                || !normalize(text(header, 10, formatter, evaluator)).contains("NET")
                || !normalize(text(header, 21, formatter, evaluator)).contains("PURCHASEQTY")) {
            throw new OrderBomMprValidationException(
                    "Invalid MPR format. Download MPR from the system, edit that workbook, then upload it again."
            );
        }
    }

    private Map<Integer, String> readLineIds(Workbook workbook, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<Integer, String> result = new LinkedHashMap<>();
        Sheet meta = workbook.getSheet(META_SHEET);
        if (meta == null) return result;
        for (int rowIndex = 1; rowIndex <= meta.getLastRowNum(); rowIndex++) {
            Row row = meta.getRow(rowIndex);
            if (row == null) continue;
            Integer excelRow = integer(row, 0, formatter, evaluator);
            String lineId = text(row, 1, formatter, evaluator);
            if (excelRow != null && hasText(lineId)) result.put(excelRow, lineId.trim());
        }
        return result;
    }

    private boolean hasMprData(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        return hasText(text(row, 1, formatter, evaluator))
                || hasText(text(row, 2, formatter, evaluator))
                || hasText(text(row, 6, formatter, evaluator))
                || hasText(text(row, 7, formatter, evaluator))
                || hasText(text(row, 8, formatter, evaluator))
                || hasText(text(row, 9, formatter, evaluator))
                || hasText(text(row, 15, formatter, evaluator))
                || hasText(text(row, 17, formatter, evaluator))
                || hasText(text(row, 18, formatter, evaluator))
                || hasText(text(row, 20, formatter, evaluator));
    }

    private String text(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || column < 0) return "";
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        try {
            return formatter.formatCellValue(cell, evaluator).replace('\u00A0', ' ').trim();
        } catch (RuntimeException ex) {
            return formatter.formatCellValue(cell).replace('\u00A0', ' ').trim();
        }
    }

    private Integer integer(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        BigDecimal value = decimal(row, column, formatter, evaluator);
        if (value == null) return null;
        try { return value.intValueExact(); }
        catch (ArithmeticException ex) {
            throw new OrderBomMprValidationException("Excel row " + (row.getRowNum() + 1) + ": BOM No. must be an integer");
        }
    }

    private BigDecimal decimal(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || column < 0) return null;
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;

        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return new BigDecimal(NumberToTextConverter.toText(cell.getNumericCellValue()));
            }
            if (cell.getCellType() == CellType.FORMULA) {
                CellValue evaluated = evaluator.evaluate(cell);
                if (evaluated == null) return null;
                if (evaluated.getCellType() == CellType.NUMERIC) {
                    return new BigDecimal(NumberToTextConverter.toText(evaluated.getNumberValue()));
                }
                if (evaluated.getCellType() == CellType.STRING) return parseDecimal(evaluated.getStringValue());
                return null;
            }
            return parseDecimal(text(row, column, formatter, evaluator));
        } catch (NumberFormatException ex) {
            throw new OrderBomMprValidationException(
                    "Excel row " + (row.getRowNum() + 1) + ": invalid number in column " + excelColumn(column)
            );
        }
    }

    private BigDecimal parseDecimal(String input) {
        if (!hasText(input)) return null;
        String value = input.trim().replace(" ", "").replace("\u00A0", "");
        if (value.contains(",") && value.contains(".")) {
            if (value.lastIndexOf(',') > value.lastIndexOf('.')) {
                value = value.replace(".", "").replace(',', '.');
            } else {
                value = value.replace(",", "");
            }
        } else if (value.contains(",")) {
            value = value.replace(',', '.');
        }
        return new BigDecimal(value);
    }

    static String fallbackKey(
            String styleDescription,
            String styleColor,
            Integer bomLineNo,
            String materialType,
            String position,
            String matColor,
            String matUnit
    ) {
        return normalize(styleDescription) + "|" + normalize(styleColor) + "|"
                + (bomLineNo == null ? "" : bomLineNo) + "|" + normalize(materialType) + "|"
                + normalize(position) + "|" + normalize(matColor) + "|" + normalize(matUnit);
    }

    private static String excelColumn(int zeroBasedColumn) {
        StringBuilder result = new StringBuilder();
        int column = zeroBasedColumn + 1;
        while (column > 0) {
            int remainder = (column - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            column = (column - 1) / 26;
        }
        return result.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    public record ImportedMprRow(
            int excelRow,
            String lineId,
            String fallbackKey,
            BigDecimal sampleQuantity,
            BigDecimal mcdStock,
            BigDecimal cmcdStock,
            BigDecimal nonSapStockQuantity
    ) { }
}
