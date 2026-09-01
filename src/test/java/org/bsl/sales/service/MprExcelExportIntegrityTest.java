package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.bsl.sales.model.MprDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression test for the exact MPR template/export path used by the API. */
class MprExcelExportIntegrityTest {

    @Test
    void exportedMprReopensWithoutBrokenWorkbookStructure() throws Exception {
        MprExcelExportValidator validator = new MprExcelExportValidator();
        OrderBomMprExcelExporter exporter = new OrderBomMprExcelExporter(null, validator);

        MprDocument mpr = new MprDocument();
        mpr.setLines(new ArrayList<>());

        byte[] exported = exporter.exportMpr(mpr);
        assertTrue(exported.length > 0);

        // Independent reopen after exporter runtime validation. This protects
        // against regressions in the real MPR_Template.xlsx, not a mock file.
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(exported))) {
            Sheet mprSheet = workbook.getSheet("MPR");
            assertNotNull(mprSheet);
            assertNotNull(workbook.getSheet(MprExcelImportService.META_SHEET));
            assertEquals(
                    "Sales comment",
                    mprSheet.getRow(1).getCell(4).getStringCellValue().trim()
            );

            // The template stores N1:T1 as one shared formula. Export must
            // rewrite every cell independently; otherwise changing N1 leaves
            // O1:T1 orphaned and POI fails first at MPR!O1.
            Row totals = mprSheet.getRow(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int column = 13; column <= 19; column++) {
                Cell total = totals.getCell(column);
                String excelColumn = CellReference.convertNumToColString(column);
                assertEquals(CellType.FORMULA, total.getCellType());
                assertEquals(
                        "IFERROR(SUBTOTAL(9," + excelColumn + "3:" + excelColumn + "3),0)",
                        total.getCellFormula()
                );
                assertNotNull(evaluator.evaluate(total));
            }
        }
    }
}
