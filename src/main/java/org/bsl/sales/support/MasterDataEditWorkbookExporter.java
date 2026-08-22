package org.bsl.sales.support;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bsl.sales.model.Loss;
import org.bsl.sales.model.MatInfo;
import org.bsl.sales.model.MaterialShipToMapping;
import org.bsl.sales.model.ShipTo;
import org.bsl.sales.model.VendorCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Creates Excel files used by the download-edit-upload workflow. */
public final class MasterDataEditWorkbookExporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int MATERIAL_SHIP_TO_MIN_INPUT_ROWS = 5000;
    private static final String SHIP_TO_REFERENCE_SHEET = "SHIP TO REFERENCE";
    private static final String SHIP_TO_NAME_RANGE = "SHIP_TO_NAMES";

    private MasterDataEditWorkbookExporter() {
    }

    public static byte[] vendorCodes(List<VendorCode> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("VENDER CODE");
            Styles styles = new Styles(workbook);

            writeHeader(sheet, styles.header, 0,
                    "Key", "Action", "Short name supplier", "Vendor Code", "Vendor Name", "MAT\nCHARGER", "Remark");
            sheet.createFreezePane(0, 1);

            List<VendorCode> sorted = rows == null ? Collections.emptyList() : rows.stream()
                    .sorted(Comparator.comparing(VendorCode::getMasterKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .collect(Collectors.toList());

            int rowIndex = 1;
            for (VendorCode item : sorted) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, item.getMasterKey(), styles.lockedText);
                write(row, 1, "UPDATE", styles.text);
                write(row, 2, item.getShortNameSupplier(), styles.text);
                write(row, 3, item.getVendorCode(), styles.text);
                write(row, 4, item.getVendorName(), styles.text);
                write(row, 5, item.getMatCharger(), styles.text);
                write(row, 6, item.getRemark(), styles.text);
            }

            setWidths(sheet, 16, 12, 28, 18, 30, 18, 34);
            addActionValidation(sheet, 1, Math.max(5000, rowIndex + 100));
            protectIdentityColumns(sheet, 0);
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot export Vendor Code edit workbook", ex);
        }
    }

    public static byte[] matInfos(List<MatInfo> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("MAT_INFO");
            Styles styles = new Styles(workbook);

            writeHeader(sheet, styles.header, 0,
                    "Key", "Action", "FLEX ID", "Material type", "MAT FULL DESCRIPTION", "MAT COLOR", "MAT UNIT",
                    "CUR", "MAT\nPRICE\n(W/O TAX)", "Short name supplier", "Remark", "Updated Date",
                    "Updated PIC", "Style Desc");
            sheet.createFreezePane(0, 1);

            List<MatInfo> sorted = rows == null ? Collections.emptyList() : rows.stream()
                    .sorted(Comparator.comparing(MatInfo::getMasterKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .collect(Collectors.toList());

            int rowIndex = 1;
            for (MatInfo item : sorted) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, item.getMasterKey(), styles.lockedText);
                write(row, 1, "UPDATE", styles.text);
                write(row, 2, item.getFlexId(), styles.text);
                write(row, 3, item.getMaterialType(), styles.text);
                write(row, 4, item.getMatFullDescription(), styles.textWrap);
                write(row, 5, item.getMatColor(), styles.textWrap);
                write(row, 6, item.getMatUnit(), styles.text);
                write(row, 7, item.getCurrency(), styles.text);
                CellStyle moneyStyle = "VND".equalsIgnoreCase(item.getCurrency()) ? styles.vnd : styles.decimal;
                write(row, 8, item.getMatPriceWithoutTax(), moneyStyle);
                write(row, 9, item.getShortNameSupplier(), styles.text);
                write(row, 10, item.getRemark(), styles.textWrap);
                write(row, 11, item.getUpdatedDate() == null ? null : DATE_FORMAT.format(item.getUpdatedDate()), styles.text);
                write(row, 12, item.getUpdatedPic(), styles.text);
                write(row, 13, item.getStyleDesc(), styles.textWrap);
            }

            setWidths(sheet, 16, 12, 13, 18, 46, 28, 12, 10, 18, 24, 34, 16, 16, 30);
            addActionValidation(sheet, 1, Math.max(10000, rowIndex + 100));
            protectIdentityColumns(sheet, 0);
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot export MAT_INFO edit workbook", ex);
        }
    }

    public static byte[] shipTos(List<ShipTo> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("SHIP TO");
            Styles styles = new Styles(workbook);
            writeHeader(sheet, styles.header, 0,
                    "Key", "Action", "Ship To Code", "Ship To Name", "Active", "Remark");
            sheet.createFreezePane(0, 1);

            List<ShipTo> sorted = rows == null ? Collections.emptyList() : rows.stream()
                    .sorted(Comparator.comparing(ShipTo::getMasterKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .collect(Collectors.toList());

            int rowIndex = 1;
            for (ShipTo item : sorted) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, item.getMasterKey(), styles.lockedText);
                write(row, 1, "UPDATE", styles.text);
                write(row, 2, item.getShipToCode(), styles.text);
                write(row, 3, item.getShipToName(), styles.text);
                write(row, 4, item.isActive() ? "TRUE" : "FALSE", styles.text);
                write(row, 5, item.getRemark(), styles.textWrap);
            }
            setWidths(sheet, 16, 12, 20, 36, 12, 40);
            addActionValidation(sheet, 1, Math.max(5000, rowIndex + 100));
            protectIdentityColumns(sheet, 0);
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot export Ship To edit workbook", ex);
        }
    }

    public static byte[] materialShipToMappings(List<MaterialShipToMapping> rows, List<ShipTo> shipTos) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("MATERIAL SHIP TO");
            Styles styles = new Styles(workbook);
            writeHeader(sheet, styles.header, 0,
                    "Key", "Action", "SAP Code", "Material Type", "MAT FULL DESCRIPTION", "MAT COLOR", "MAT UNIT",
                    "Ship To Name", "Active", "Remark");
            sheet.createFreezePane(0, 1);

            List<MaterialShipToMapping> sorted = rows == null ? Collections.emptyList() : rows.stream()
                    .sorted(Comparator.comparing(MaterialShipToMapping::getMasterKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .collect(Collectors.toList());

            int rowIndex = 1;
            for (MaterialShipToMapping item : sorted) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, item.getMasterKey(), styles.lockedText);
                write(row, 1, "UPDATE", styles.text);
                write(row, 2, item.getSapCode(), styles.text);
                write(row, 3, item.getMaterialType(), styles.text);
                write(row, 4, item.getMatFullDescription(), styles.textWrap);
                write(row, 5, item.getMatColor(), styles.textWrap);
                write(row, 6, item.getMatUnit(), styles.text);
                write(row, 7, item.getShipToName(), styles.text);
                write(row, 8, item.isActive() ? "TRUE" : "FALSE", styles.text);
                write(row, 9, item.getRemark(), styles.textWrap);
            }
            setWidths(sheet, 16, 12, 18, 20, 48, 28, 12, 34, 12, 40);
            int lastInputRow = Math.max(MATERIAL_SHIP_TO_MIN_INPUT_ROWS, rowIndex + 100);
            addActionValidation(sheet, 1, lastInputRow);
            addMaterialShipToControls(workbook, sheet, styles, shipTos, 7, 8, lastInputRow);
            protectIdentityColumns(sheet, 0);
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot export Material Ship To edit workbook", ex);
        }
    }

    public static byte[] materialShipToTemplate(List<ShipTo> shipTos) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            Sheet sheet = workbook.createSheet("MATERIAL SHIP TO");
            writeHeader(sheet, styles.header, 0,
                    "SAP Code", "Material Type", "MAT FULL DESCRIPTION", "MAT COLOR", "MAT UNIT",
                    "Ship To Name", "Active", "Remark");
            sheet.createFreezePane(0, 1);
            setWidths(sheet, 18, 20, 48, 28, 12, 34, 12, 40);
            addMaterialShipToControls(
                    workbook,
                    sheet,
                    styles,
                    shipTos,
                    5,
                    6,
                    MATERIAL_SHIP_TO_MIN_INPUT_ROWS
            );
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot export Material Ship To template", ex);
        }
    }

    public static byte[] losses(List<Loss> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Loss");
            Styles styles = new Styles(workbook);

            writeHeader(sheet, styles.header, 0, "Key", "Order Q'ty", "<501", "<1501", "<3001", ">=3001");
            writeHeader(sheet, styles.header, 7, "Order Q'ty", "<501", "<1501", "<3001", ">=3001");
            sheet.createFreezePane(0, 1);

            List<Loss> sorted = rows == null ? Collections.emptyList() : rows.stream()
                    .sorted(Comparator.comparing(Loss::getMasterKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .collect(Collectors.toList());

            int rowIndex = 1;
            for (Loss item : sorted) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, item.getMasterKey(), styles.text);
                write(row, 1, item.getMaterialGroup(), styles.text);
                write(row, 2, item.getLossLt501(), styles.percentage);
                write(row, 3, item.getLossLt1501(), styles.percentage);
                write(row, 4, item.getLossLt3001(), styles.percentage);
                write(row, 5, item.getLossGte3001(), styles.percentage);
                write(row, 7, item.getMaterialGroup(), styles.text);
                write(row, 8, item.getFactorLt501(), styles.decimal);
                write(row, 9, item.getFactorLt1501(), styles.decimal);
                write(row, 10, item.getFactorLt3001(), styles.decimal);
                write(row, 11, item.getFactorGte3001(), styles.decimal);
            }

            setWidths(sheet, 16, 18, 12, 12, 12, 12, 4, 18, 12, 12, 12, 12);
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot export Loss edit workbook", ex);
        }
    }

    private static void addActionValidation(Sheet sheet, int actionColumn, int lastRow) {
        addExplicitListValidation(
                sheet,
                actionColumn,
                lastRow,
                new String[]{"CREATE", "UPDATE", "DELETE"},
                "Select Action",
                "Choose CREATE, UPDATE or DELETE from the list.",
                "Invalid Action",
                "Use CREATE, UPDATE or DELETE."
        );
    }

    private static void addMaterialShipToControls(
            Workbook workbook,
            Sheet sheet,
            Styles styles,
            List<ShipTo> shipTos,
            int shipToNameColumn,
            int activeColumn,
            int lastRow
    ) {
        List<ShipTo> options = shipTos == null
                ? Collections.emptyList()
                : shipTos.stream()
                .filter(item -> item != null && item.isActive() && item.getShipToName() != null
                        && !item.getShipToName().trim().isEmpty())
                .sorted(Comparator.comparing(ShipTo::getShipToName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        if (options.isEmpty()) {
            throw new IllegalArgumentException("At least one active Ship To is required to create this workbook");
        }

        Sheet reference = workbook.createSheet(SHIP_TO_REFERENCE_SHEET);
        writeHeader(reference, styles.header, 0, "Ship To Name");
        int referenceRow = 1;
        for (ShipTo item : options) {
            Row row = reference.createRow(referenceRow++);
            write(row, 0, item.getShipToName(), styles.text);
        }
        setWidths(reference, 40);
        reference.createFreezePane(0, 1);

        int lastReferenceRow = referenceRow;
        var nameRange = workbook.createName();
        nameRange.setNameName(SHIP_TO_NAME_RANGE);
        nameRange.setRefersToFormula("'" + SHIP_TO_REFERENCE_SHEET + "'!$A$2:$A$" + lastReferenceRow);

        var helper = sheet.getDataValidationHelper();
        var shipToConstraint = helper.createFormulaListConstraint(SHIP_TO_NAME_RANGE);
        var shipToRange = new CellRangeAddressList(1, lastRow, shipToNameColumn, shipToNameColumn);
        var shipToValidation = helper.createValidation(shipToConstraint, shipToRange);
        shipToValidation.setShowErrorBox(true);
        shipToValidation.setShowPromptBox(true);
        shipToValidation.createPromptBox(
                "Select Ship To",
                "Choose an active Ship To for this Buyer. Add new values in Ship To Master first."
        );
        shipToValidation.createErrorBox(
                "Invalid Ship To",
                "Select a Ship To from the list. Add new values in Ship To Master first."
        );
        sheet.addValidationData(shipToValidation);

        addExplicitListValidation(
                sheet,
                activeColumn,
                lastRow,
                new String[]{"TRUE", "FALSE"},
                "Select Status",
                "Choose TRUE for Active or FALSE for Inactive.",
                "Invalid Active value",
                "Use TRUE or FALSE."
        );
        workbook.setSheetHidden(workbook.getSheetIndex(reference), true);
    }

    private static void addExplicitListValidation(
            Sheet sheet,
            int column,
            int lastRow,
            String[] values,
            String promptTitle,
            String prompt,
            String errorTitle,
            String error
    ) {
        var helper = sheet.getDataValidationHelper();
        var constraint = helper.createExplicitListConstraint(values);
        var range = new CellRangeAddressList(1, lastRow, column, column);
        var validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        validation.setShowPromptBox(true);
        validation.createPromptBox(promptTitle, prompt);
        validation.createErrorBox(errorTitle, error);
        sheet.addValidationData(validation);
    }

    private static void protectIdentityColumns(Sheet sheet, int... columns) {
        // The workbook intentionally remains editable without a password. Identity cells are visually locked
        // and protected when users choose Review > Protect Sheet.
        for (int column : columns) {
            sheet.setColumnHidden(column, false);
        }
    }

    private static void writeHeader(Sheet sheet, CellStyle style, int startColumn, String... headers) {
        Row row = sheet.getRow(0);
        if (row == null) {
            row = sheet.createRow(0);
            row.setHeightInPoints(28);
        }
        for (int index = 0; index < headers.length; index++) {
            write(row, startColumn + index, headers[index], style);
        }
    }

    private static void write(Row row, int columnIndex, Object value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value != null) {
            cell.setCellValue(String.valueOf(value));
        }
        if (style != null) cell.setCellStyle(style);
    }

    private static void setWidths(Sheet sheet, int... widths) {
        for (int index = 0; index < widths.length; index++) {
            sheet.setColumnWidth(index, Math.max(4, widths[index]) * 256);
        }
    }

    private static byte[] toBytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    private static final class Styles {
        private final CellStyle header;
        private final CellStyle text;
        private final CellStyle lockedText;
        private final CellStyle textWrap;
        private final CellStyle decimal;
        private final CellStyle percentage;
        private final CellStyle vnd;

        private Styles(Workbook workbook) {
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            applyBorder(header);

            text = workbook.createCellStyle();
            text.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(text);

            lockedText = workbook.createCellStyle();
            lockedText.cloneStyleFrom(text);
            lockedText.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            lockedText.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            lockedText.setLocked(true);

            textWrap = workbook.createCellStyle();
            textWrap.cloneStyleFrom(text);
            textWrap.setWrapText(true);

            decimal = workbook.createCellStyle();
            decimal.cloneStyleFrom(text);
            decimal.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("#,##0.######"));

            percentage = workbook.createCellStyle();
            percentage.cloneStyleFrom(text);
            percentage.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("0.##%"));

            vnd = workbook.createCellStyle();
            vnd.cloneStyleFrom(text);
            vnd.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("#,##0"));
        }

        private void applyBorder(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
