package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bsl.sales.exception.BomExcelDuplicateProductColorException;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomProductColor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BomExcelDuplicateProductColorTest {

    @Test
    void duplicateFourKeyRequiresConfirmationBeforeImport() throws Exception {
        BomExcelParser parser = new BomExcelParser();
        MockMultipartFile file = duplicateProductColorWorkbook();

        BomExcelDuplicateProductColorException error = assertThrows(
                BomExcelDuplicateProductColorException.class,
                () -> parser.parse(file)
        );

        assertEquals(1, error.getDuplicates().size());
        BomExcelDuplicateProductColorException.DuplicateProductColor duplicate = error.getDuplicates().get(0);
        assertEquals("Q", duplicate.keptColumn());
        assertEquals(java.util.List.of("R"), duplicate.duplicateColumns());
        assertEquals("BLACK", duplicate.colorName());
        assertEquals("PN-001", duplicate.patternNumber());
        assertEquals("F26", duplicate.season());
        assertEquals("ST-100", duplicate.styleNumber());
    }

    @Test
    void explicitContinueKeepsOnlyFirstProductColor() throws Exception {
        BomExcelParser parser = new BomExcelParser();

        BomExcelParser.ParsedBom parsed = parser.parse(duplicateProductColorWorkbook(), true);

        assertEquals(1, parsed.productColors().size());
        BomProductColor kept = parsed.productColors().get(0);
        assertEquals("BLACK", kept.getColorName());
        assertEquals(16, kept.getSourceColumnIndex());
        assertEquals(java.util.List.of(17), parsed.ignoredProductColorSourceColumns());

        assertEquals(1, parsed.coreLines().size());
        BomLine line = parsed.coreLines().get(0);
        assertEquals(1, line.getProductColorValues().size());
        assertEquals(kept.getId(), line.getProductColorValues().get(0).getProductColorId());
        assertEquals("FIRST VALUE", line.getProductColorValues().get(0).getValue());
    }

    @Test
    void differentAnyOneOfFourKeysIsNotDuplicate() throws Exception {
        BomExcelParser parser = new BomExcelParser();

        BomExcelParser.ParsedBom parsed = parser.parse(productColorWorkbook("ST-200"));

        assertEquals(2, parsed.productColors().size());
        assertEquals("ST-100", parsed.productColors().get(0).getStyleNumber());
        assertEquals("ST-200", parsed.productColors().get(1).getStyleNumber());
        assertEquals(0, parsed.ignoredProductColorSourceColumns().size());
    }

    private MockMultipartFile duplicateProductColorWorkbook() throws Exception {
        return productColorWorkbook(" st-100 ");
    }

    private MockMultipartFile productColorWorkbook(String secondStyleNumber) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("BOM Details");
            int headerRowIndex = 10;

            Row header = sheet.createRow(headerRowIndex);
            header.createCell(0).setCellValue("No.");
            header.createCell(1).setCellValue("MTR");
            header.createCell(2).setCellValue("SAP CODE");
            header.createCell(12).setCellValue("CONS.");
            header.createCell(13).setCellValue("NET CONSUMPTION");
            header.createCell(15).setCellValue("REMARKS ON BOM");
            header.createCell(16).setCellValue("PN-001");
            header.createCell(17).setCellValue(" pn-001 ");

            Row season = sheet.createRow(headerRowIndex + 1);
            season.createCell(16).setCellValue("F26");
            season.createCell(17).setCellValue(" f26 ");

            Row style = sheet.createRow(headerRowIndex + 2);
            style.createCell(16).setCellValue("ST-100");
            style.createCell(17).setCellValue(secondStyleNumber);

            Row sequence = sheet.createRow(headerRowIndex + 3);
            sequence.createCell(16).setCellValue(1);
            sequence.createCell(17).setCellValue(2);

            Row color = sheet.createRow(headerRowIndex + 4);
            color.createCell(16).setCellValue("BLACK");
            color.createCell(17).setCellValue(" black ");

            Row material = sheet.createRow(headerRowIndex + 5);
            material.createCell(0).setCellValue(1);
            material.createCell(1).setCellValue("FABRIC");
            material.createCell(2).setCellValue("SAP-1");
            material.createCell(13).setCellValue(0.5);
            material.createCell(14).setCellValue("M");
            material.createCell(16).setCellValue("FIRST VALUE");
            material.createCell(17).setCellValue("SECOND VALUE");

            workbook.write(output);
            return new MockMultipartFile(
                    "file",
                    "duplicate-product-colors.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray()
            );
        }
    }
}
