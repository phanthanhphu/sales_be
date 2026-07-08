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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bsl.sales.model.Loss;
import org.bsl.sales.model.MatInfo;
import org.bsl.sales.model.VendorCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Creates the Excel files users download, edit, then upload back.
 * The first column is always the system Key. Keeping the key updates the
 * existing row; clearing the key creates a new row during edited-file upload.
 */
public final class MasterDataEditWorkbookExporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private MasterDataEditWorkbookExporter() {
    }

    public static byte[] vendorCodes(List<VendorCode> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("VENDOR CODE");
            Styles styles = new Styles(workbook);

            writeHeader(sheet, styles.header, 0,
                    "Key", "Short name supplier", "Vendor Code", "Vendor name", "MAT\nCHARGER", "Remark");
            sheet.createFreezePane(0, 1);

            List<VendorCode> sorted = rows == null ? Collections.emptyList() : rows.stream()
                    .sorted(Comparator.comparing(VendorCode::getMasterKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .collect(Collectors.toList());

            int rowIndex = 1;
            for (VendorCode item : sorted) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, item.getMasterKey(), styles.text);
                write(row, 1, item.getShortNameSupplier(), styles.text);
                write(row, 2, item.getVendorCode(), styles.text);
                write(row, 3, item.getVendorName(), styles.text);
                write(row, 4, item.getMatCharger(), styles.text);
                write(row, 5, item.getRemark(), styles.text);
            }

            setWidths(sheet, 16, 28, 18, 30, 18, 34);
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
                    "Key", "FLEX ID", "Material type", "MAT FULL DESCRIPTION", "MAT COLOR", "MAT UNIT",
                    "CUR", "MAT\nPRICE\n(W/O TAX)", "Short name supplier", "Remark", "Updated Date",
                    "Updated PIC", "Style Desc");
            sheet.createFreezePane(0, 1);

            List<MatInfo> sorted = rows == null ? Collections.emptyList() : rows.stream()
                    .sorted(Comparator.comparing(MatInfo::getMasterKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .collect(Collectors.toList());

            int rowIndex = 1;
            for (MatInfo item : sorted) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, item.getMasterKey(), styles.text);
                write(row, 1, item.getFlexId(), styles.text);
                write(row, 2, item.getMaterialType(), styles.text);
                write(row, 3, item.getMatFullDescription(), styles.textWrap);
                write(row, 4, item.getMatColor(), styles.textWrap);
                write(row, 5, item.getMatUnit(), styles.text);
                write(row, 6, item.getCurrency(), styles.text);
                write(row, 7, item.getMatPriceWithoutTax(), styles.decimal);
                write(row, 8, item.getShortNameSupplier(), styles.text);
                write(row, 9, item.getRemark(), styles.textWrap);
                write(row, 10, item.getUpdatedDate() == null ? null : DATE_FORMAT.format(item.getUpdatedDate()), styles.text);
                write(row, 11, item.getUpdatedPic(), styles.text);
                write(row, 12, item.getStyleDesc(), styles.textWrap);
            }

            setWidths(sheet, 16, 13, 18, 46, 28, 12, 10, 18, 24, 34, 16, 16, 30);
            return toBytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot export MAT_INFO edit workbook", ex);
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
                write(row, 2, item.getLossLt501(), styles.decimal);
                write(row, 3, item.getLossLt1501(), styles.decimal);
                write(row, 4, item.getLossLt3001(), styles.decimal);
                write(row, 5, item.getLossGte3001(), styles.decimal);

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
        if (style != null) {
            cell.setCellStyle(style);
        }
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
        private final CellStyle textWrap;
        private final CellStyle decimal;

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

            textWrap = workbook.createCellStyle();
            textWrap.cloneStyleFrom(text);
            textWrap.setWrapText(true);

            decimal = workbook.createCellStyle();
            decimal.cloneStyleFrom(text);
            decimal.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("0.######"));
        }

        private void applyBorder(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
