package org.bsl.sales.service;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.bsl.sales.exception.MprExcelExportIntegrityException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runtime integrity gate for generated MPR .xlsx files.
 *
 * The exporter validates the exact bytes that will be returned to the browser.
 * Reopening through Apache POI catches broken ZIP/OOXML structures, then the
 * remaining checks protect the workbook areas that have caused Excel repair
 * warnings in the past: sheets, formulas, defined names and merged regions.
 */
@Component
public class MprExcelExportValidator {

    private static final String MPR_SHEET = "MPR";
    private static final int MPR_HEADER_ROW = 1; // zero-based, Excel row 2
    private static final int MPR_SALES_COMMENT_COL = 4; // E
    private static final String SALES_COMMENT_HEADER = "Sales comment";

    public void validate(byte[] workbookBytes, int expectedLineCount) {
        if (workbookBytes == null || workbookBytes.length == 0) {
            throw invalid("generated workbook is empty");
        }

        // This is intentionally a second open of the serialized output. A
        // workbook that was valid in memory can still become invalid while it
        // is serialized, so validate the exact bytes sent to the user.
        try (ByteArrayInputStream input = new ByteArrayInputStream(workbookBytes);
             Workbook workbook = WorkbookFactory.create(input)) {

            validateSheets(workbook, Math.max(0, expectedLineCount));
            validateDefinedNames(workbook);
            validateMergedRegions(workbook);
            validateFormulas(workbook);
        } catch (MprExcelExportIntegrityException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("Apache POI could not reopen the generated OOXML workbook (possible corrupt ZIP/XML): "
                    + safeMessage(ex));
        }
    }

    private void validateSheets(Workbook workbook, int expectedLineCount) {
        if (workbook.getNumberOfSheets() <= 0) {
            throw invalid("workbook contains no sheets");
        }

        Sheet mpr = workbook.getSheet(MPR_SHEET);
        if (mpr == null) {
            throw invalid("required sheet 'MPR' is missing");
        }

        Row header = mpr.getRow(MPR_HEADER_ROW);
        if (header == null) {
            throw invalid("MPR header row is missing");
        }
        Cell salesComment = header.getCell(MPR_SALES_COMMENT_COL);
        String salesCommentLabel = salesComment == null ? "" : salesComment.toString().trim();
        if (!SALES_COMMENT_HEADER.equalsIgnoreCase(salesCommentLabel)) {
            throw invalid("MPR Sales comment header is invalid: '" + salesCommentLabel + "'");
        }

        Sheet meta = workbook.getSheet(MprExcelImportService.META_SHEET);
        if (meta == null) {
            throw invalid("MPR import metadata sheet is missing");
        }

        int metadataLineCount = 0;
        for (int rowIndex = 1; rowIndex <= meta.getLastRowNum(); rowIndex++) {
            Row row = meta.getRow(rowIndex);
            if (row == null) continue;
            Cell lineId = row.getCell(1);
            if (lineId != null && !lineId.toString().trim().isEmpty()) metadataLineCount++;
        }
        if (metadataLineCount != expectedLineCount) {
            throw invalid("MPR metadata line count does not match exported data. Expected "
                    + expectedLineCount + " but found " + metadataLineCount);
        }
    }

    private void validateDefinedNames(Workbook workbook) {
        for (Name name : new ArrayList<>(workbook.getAllNames())) {
            if (name == null) continue;
            String formula = trim(name.getRefersToFormula());
            if (formula.isEmpty()) continue;

            String normalized = formula.toUpperCase(Locale.ROOT);
            if (normalized.contains("#REF!")) {
                throw invalid("defined name '" + trim(name.getNameName()) + "' contains #REF!");
            }
            if (looksLikeExternalWorkbookReference(normalized)) {
                throw invalid("defined name '" + trim(name.getNameName()) + "' contains an external workbook reference");
            }
        }
    }

    private void validateFormulas(Workbook workbook) {
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.FORMULA) continue;

                    String formula = trim(cell.getCellFormula());
                    if (formula.isEmpty()) {
                        throw invalid("blank formula at " + cellRef(sheet, cell));
                    }
                    String normalized = formula.toUpperCase(Locale.ROOT);
                    if (normalized.contains("#REF!")) {
                        throw invalid("formula contains #REF! at " + cellRef(sheet, cell));
                    }
                    if (looksLikeExternalWorkbookReference(normalized)) {
                        throw invalid("formula contains an external workbook reference at " + cellRef(sheet, cell));
                    }

                    // FormulaEvaluator is not an Excel compatibility or file-
                    // integrity validator. It supports only a subset of Excel's
                    // calculation engine and can throw for a valid formula or
                    // shared-formula follower (the template fails first at O1).
                    // The workbook is already configured for recalculation in
                    // Excel, so validate the serialized formula and its cached
                    // error value without making POI evaluation a download gate.
                    if (cell.getCachedFormulaResultType() == CellType.ERROR) {
                        byte code = cell.getErrorCellValue();
                        if (code == FormulaError.REF.getCode() || code == FormulaError.NAME.getCode()) {
                            throw invalid("formula has cached result " + FormulaError.forInt(code).getString()
                                    + " at " + cellRef(sheet, cell));
                        }
                    }
                }
            }
        }
    }

    private void validateMergedRegions(Workbook workbook) {
        int maxRow = SpreadsheetVersion.EXCEL2007.getLastRowIndex();
        int maxCol = SpreadsheetVersion.EXCEL2007.getLastColumnIndex();

        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            List<CellRangeAddress> regions = new ArrayList<>();
            for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
                CellRangeAddress region = sheet.getMergedRegion(index);
                if (region == null) continue;

                if (region.getFirstRow() < 0 || region.getFirstColumn() < 0
                        || region.getLastRow() < region.getFirstRow()
                        || region.getLastColumn() < region.getFirstColumn()
                        || region.getLastRow() > maxRow
                        || region.getLastColumn() > maxCol) {
                    throw invalid("invalid merged region " + region.formatAsString()
                            + " on sheet '" + sheet.getSheetName() + "'");
                }

                for (CellRangeAddress existing : regions) {
                    if (overlaps(existing, region)) {
                        throw invalid("overlapping merged regions " + existing.formatAsString()
                                + " and " + region.formatAsString()
                                + " on sheet '" + sheet.getSheetName() + "'");
                    }
                }
                regions.add(region);
            }
        }
    }

    private boolean overlaps(CellRangeAddress left, CellRangeAddress right) {
        boolean rowsOverlap = left.getFirstRow() <= right.getLastRow()
                && right.getFirstRow() <= left.getLastRow();
        boolean columnsOverlap = left.getFirstColumn() <= right.getLastColumn()
                && right.getFirstColumn() <= left.getLastColumn();
        return rowsOverlap && columnsOverlap;
    }

    private boolean looksLikeExternalWorkbookReference(String formula) {
        return formula.contains("[") && formula.contains("]") && formula.contains("!");
    }

    private String cellRef(Sheet sheet, Cell cell) {
        return "'" + sheet.getSheetName() + "'!" + cell.getAddress().formatAsString();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : trim(error.getMessage());
        return message.isEmpty() && error != null ? error.getClass().getSimpleName() : message;
    }

    private MprExcelExportIntegrityException invalid(String detail) {
        return new MprExcelExportIntegrityException(
                "Generated MPR Excel failed integrity validation before download: " + detail
        );
    }
}
