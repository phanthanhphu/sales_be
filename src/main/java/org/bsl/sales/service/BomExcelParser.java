package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.BomHeader;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomLineColorValue;
import org.bsl.sales.model.BomPacking;
import org.bsl.sales.model.BomProductColor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/**
 * Imports THE BOM DETAILS workbook.
 *
 * Product-color columns start at Excel column R. Each column is saved as one
 * Product Color item with exactly three header values:
 *   Row 1: Color Name      (BLACK)
 *   Row 2: Pattern Number  (LLB 352 A)
 *   Row 3: Season          (F26)
 *
 * A material-line value links to the Product Color item by productColorId.
 */
@Service
public class BomExcelParser {

    public ParsedBom parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new OrderBomMprValidationException("BOM Excel file is required");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = findBomSheet(workbook);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.US);

            int headerRow = findColumnHeaderRow(sheet, formatter, evaluator);
            if (headerRow < 0) {
                throw new OrderBomMprValidationException("Cannot find BOM detail header (MTR / SAP CODE / CONSUMPTION) in Excel file");
            }

            BomHeader header = parseHeader(sheet, formatter, evaluator);
            Map<Integer, BomProductColor> productColorColumns = findProductColorColumns(sheet, headerRow, formatter, evaluator);
            List<BomProductColor> productColors = new ArrayList<>(productColorColumns.values());

            List<BomLine> coreLines = new ArrayList<>();
            List<BomPacking> packings = new ArrayList<>();
            Map<Integer, BomLine> linesByExcelRow = new LinkedHashMap<>();
            List<PackingBoundary> packingBoundaries = new ArrayList<>();

            BomPacking currentPacking = null;
            PackingBoundary currentBoundary = null;
            Integer currentGroupNo = null;
            String inheritedMaterialType = "";

            for (int rowIndex = headerRow + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;

                String first = text(row, 0, formatter, evaluator);
                if (isPackingTitle(first)) {
                    if (currentBoundary != null) currentBoundary.endRow = rowIndex;

                    currentPacking = new BomPacking();
                    currentPacking.setId(UUID.randomUUID().toString());
                    currentPacking.setPackingName(cleanPackingName(first));
                    currentPacking.setSequence(packings.size() + 1);
                    packings.add(currentPacking);

                    currentBoundary = new PackingBoundary(currentPacking.getId(), rowIndex + 1);
                    packingBoundaries.add(currentBoundary);
                    currentGroupNo = null;
                    inheritedMaterialType = "Packing";
                    continue;
                }

                BomLine line = parseLine(row, rowIndex + 1, productColorColumns, formatter, evaluator);
                if (line == null) continue;

                if (line.getMaterialGroupNo() != null && hasText(line.getMaterialType())) {
                    currentGroupNo = line.getMaterialGroupNo();
                    inheritedMaterialType = line.getMaterialType();
                    line.setDetailLine(false);
                } else {
                    line.setDetailLine(true);
                    if (line.getMaterialGroupNo() == null) line.setMaterialGroupNo(currentGroupNo);
                    if (!hasText(line.getMaterialType())) line.setMaterialType(inheritedMaterialType);
                }

                linesByExcelRow.put(line.getSourceRowNumber(), line);
                if (currentPacking == null) {
                    coreLines.add(line);
                } else {
                    currentPacking.getLines().add(line);
                }
            }

            if (currentBoundary != null) currentBoundary.endRow = sheet.getLastRowNum() + 1;

            /*
             * Some BOM Detail workbooks do not contain a PACKING title. MPR
             * generation always works from Packing rows, so move every parsed
             * line into one first/default Packing instead of leaving it in
             * Core Lines.
             */
            if (packings.isEmpty() && !coreLines.isEmpty()) {
                BomPacking firstPacking = new BomPacking();
                firstPacking.setId(UUID.randomUUID().toString());
                firstPacking.setPackingName("PACKING 1");
                firstPacking.setSequence(1);
                firstPacking.getLines().addAll(coreLines);
                packings.add(firstPacking);
                coreLines.clear();

                PackingBoundary defaultBoundary = new PackingBoundary(firstPacking.getId(), headerRow + 1);
                defaultBoundary.endRow = sheet.getLastRowNum() + 1;
                packingBoundaries.add(defaultBoundary);
            }

            List<ParsedAttachment> importedAttachments = extractImages(
                    sheet,
                    headerRow,
                    productColorColumns,
                    linesByExcelRow,
                    packingBoundaries
            );

            return new ParsedBom(
                    header,
                    productColors,
                    coreLines,
                    packings,
                    importedAttachments
            );
        } catch (IOException ex) {
            throw new OrderBomMprValidationException("Unable to read BOM Excel file: " + ex.getMessage());
        }
    }

    private Sheet findBomSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            String name = sheet.getSheetName().toUpperCase(Locale.ROOT);
            if (name.contains("BOM") && name.contains("DETAIL")) return sheet;
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (workbook.getSheetAt(i).getSheetName().toUpperCase(Locale.ROOT).contains("BOM")) {
                return workbook.getSheetAt(i);
            }
        }
        return workbook.getSheetAt(0);
    }

    private int findColumnHeaderRow(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        int upperBound = Math.min(sheet.getLastRowNum(), 80);
        for (int r = 0; r <= upperBound; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            StringBuilder joined = new StringBuilder();
            for (int c = 0; c < Math.min(Math.max(row.getLastCellNum(), 0), 30); c++) {
                joined.append(' ').append(text(row, c, formatter, evaluator).toUpperCase(Locale.ROOT));
            }
            String value = joined.toString();
            if (value.contains("MTR") && value.contains("SAP") && value.contains("CONSUMPTION")) return r;
        }
        return -1;
    }

    private BomHeader parseHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        BomHeader header = new BomHeader();
        header.setBuyer(findValueAfterLabel(sheet, "BUYER", formatter, evaluator));
        header.setRevStage(findValueAfterLabel(sheet, "REV. STAGE", formatter, evaluator));
        header.setComments(findValueAfterLabel(sheet, "COMMENTS", formatter, evaluator));
        header.setSeason(findValueAfterLabel(sheet, "SEASON", formatter, evaluator));
        header.setPatternDate(findValueAfterLabel(sheet, "PATTERN DATE", formatter, evaluator));
        header.setStyleNumber(findValueAfterLabel(sheet, "STYLE NUMBER", formatter, evaluator));
        header.setPatternRevisedDate(findValueAfterLabel(sheet, "PATTERN REVISED DATE", formatter, evaluator));
        header.setPatternNumber(findValueAfterLabel(sheet, "PATTERN NUMBER", formatter, evaluator));
        header.setPatternMaker(findValueAfterLabel(sheet, "PATTERN MAKER", formatter, evaluator));
        header.setStyleName(findValueAfterLabel(sheet, "STYLE NAME", formatter, evaluator));
        header.setFactoryProduct(findValueAfterLabel(sheet, "FACTORY PRODUCT", formatter, evaluator));
        header.setBomMaker(findValueAfterLabel(sheet, "BOM MAKER", formatter, evaluator));
        header.setSize(findValueAfterLabel(sheet, "SIZE", formatter, evaluator));
        header.setBomDate(findValueAfterLabel(sheet, "BOM DATE", formatter, evaluator));
        return header;
    }

    private String findValueAfterLabel(Sheet sheet, String wantedLabel, DataFormatter formatter, FormulaEvaluator evaluator) {
        String expected = normalize(wantedLabel);
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 15); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < Math.min(Math.max(row.getLastCellNum(), 0), 30); c++) {
                String candidate = normalize(text(row, c, formatter, evaluator));
                if (!candidate.contains(expected)) continue;

                for (int offset = 1; offset <= 4; offset++) {
                    String value = text(row, c + offset, formatter, evaluator);
                    if (hasText(value) && !normalize(value).equals(expected)) return value;
                }
            }
        }
        return "";
    }

    /** Reads columns R onward as Product Color items, using the three Excel header rows. */
    private Map<Integer, BomProductColor> findProductColorColumns(
            Sheet sheet,
            int headerRow,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        Map<Integer, BomProductColor> result = new LinkedHashMap<>();
        Row colorHeaderRow = sheet.getRow(headerRow);
        if (colorHeaderRow == null) return result;

        int maxColumn = Math.max(17, colorHeaderRow.getLastCellNum() - 1);
        Set<String> seenNames = new HashSet<>();
        for (int column = 17; column <= maxColumn; column++) {
            String colorName = text(colorHeaderRow, column, formatter, evaluator);
            if (!hasText(colorName)) continue;

            String normalized = normalize(colorName);
            if (!seenNames.add(normalized)) continue;

            BomProductColor productColor = new BomProductColor();
            productColor.setId(UUID.randomUUID().toString());
            productColor.setColorName(colorName.trim());
            productColor.setPatternNumber(text(sheet.getRow(headerRow + 1), column, formatter, evaluator));
            productColor.setSeason(text(sheet.getRow(headerRow + 2), column, formatter, evaluator));
            productColor.setSourceColumnIndex(column);
            result.put(column, productColor);
        }
        return result;
    }

    private BomLine parseLine(
            Row row,
            int excelRow,
            Map<Integer, BomProductColor> productColorColumns,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        String materialType = text(row, 1, formatter, evaluator);
        String sapCode = text(row, 2, formatter, evaluator);
        String detailNo = text(row, 3, formatter, evaluator);
        String position = text(row, 4, formatter, evaluator);
        String positionDescription = text(row, 5, formatter, evaluator);
        String positionDescriptionExtra = text(row, 6, formatter, evaluator);
        String piece = text(row, 7, formatter, evaluator);
        String direction = text(row, 11, formatter, evaluator);
        String remark = text(row, 16, formatter, evaluator);
        BigDecimal costing = decimal(row, 12, formatter, evaluator);
        BigDecimal net = decimal(row, 14, formatter, evaluator);
        BigDecimal x = decimal(row, 8, formatter, evaluator);
        BigDecimal y = decimal(row, 9, formatter, evaluator);
        BigDecimal qty = decimal(row, 10, formatter, evaluator);

        boolean hasBusinessData = hasText(materialType) || hasText(sapCode) || hasText(detailNo)
                || hasText(position) || hasText(positionDescription) || hasText(positionDescriptionExtra)
                || hasText(piece) || hasText(direction) || x != null || y != null || qty != null
                || costing != null || net != null || hasText(remark);
        if (!hasBusinessData) return null;

        BomLine line = new BomLine();
        line.setId(UUID.randomUUID().toString());
        line.setSourceRowNumber(excelRow);
        line.setMaterialGroupNo(integer(row, 0, formatter, evaluator));
        line.setMaterialType(materialType);
        line.setSapCode(sapCode);
        line.setDetailNo(detailNo);
        line.setPosition(position);
        line.setPositionDescription(positionDescription);
        line.setPositionDescriptionExtra(positionDescriptionExtra);
        line.setPieceCode(piece);
        line.setDimensionX(x);
        line.setDimensionY(y);
        line.setQuantity(qty);
        line.setDirection(direction);
        line.setCosting(costing);
        line.setCostingUnit(text(row, 13, formatter, evaluator));
        line.setConsumptionNet(net);
        line.setConsumptionUnit(text(row, 15, formatter, evaluator));
        line.setBomRemark(remark);

        for (Map.Entry<Integer, BomProductColor> entry : productColorColumns.entrySet()) {
            String value = text(row, entry.getKey(), formatter, evaluator);
            if (!hasText(value)) continue;

            BomLineColorValue linkedValue = new BomLineColorValue();
            linkedValue.setProductColorId(entry.getValue().getId());
            linkedValue.setValue(value);
            line.getProductColorValues().add(linkedValue);

            // Keep the old map populated to preserve existing consumers and older records.
            line.getColorValues().put(entry.getValue().getColorName(), value);
        }
        return line;
    }

    /**
     * Imports Excel images into the scope where they are visually placed.
     *
     * Product-color images are special: the workbook may reuse one collage source image and
     * display a different cropped backpack in each color column. Apache POI returns the original
     * source image, not the visual crop. This method applies Excel's srcRect crop and saves one
     * rendered image for the matching Product Color item.
     */
    private List<ParsedAttachment> extractImages(
            Sheet sheet,
            int headerRow,
            Map<Integer, BomProductColor> productColorColumns,
            Map<Integer, BomLine> linesByExcelRow,
            List<PackingBoundary> packingBoundaries
    ) {
        if (!(sheet instanceof XSSFSheet xssfSheet)) return List.of();
        XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
        if (drawing == null) return List.of();

        List<ParsedAttachment> result = new ArrayList<>();
        /*
         * One Product Color has one image slot. LinkedHashMap also preserves Excel drawing order.
         * When two shapes overlap in the same color column, the later Excel shape is the visible one.
         */
        Map<String, ParsedAttachment> productImageByColorId = new LinkedHashMap<>();
        int imageIndex = 1;

        for (XSSFShape shape : drawing.getShapes()) {
            if (!(shape instanceof XSSFPicture picture)) continue;

            XSSFClientAnchor anchor = picture.getClientAnchor();
            int excelRow = anchor == null ? nullSafeRow(headerRow + 1) : anchor.getRow1() + 1;
            int excelColumn = anchor == null ? -1 : anchor.getCol1();
            XSSFPictureData data = picture.getPictureData();
            if (data == null || data.getData() == null || data.getData().length == 0) continue;

            BomProductColor productColor = productColorColumns.get(excelColumn);
            if (excelRow <= headerRow && productColor != null) {
                RenderedImage rendered = renderProductColorImage(picture, data);
                if (rendered != null) {
                    String fileName = "imported-product-color-"
                            + safeFilePart(productColor.getColorName())
                            + "."
                            + rendered.extension();

                    productImageByColorId.put(
                            productColor.getId(),
                            new ParsedAttachment(
                                    rendered.bytes(),
                                    fileName,
                                    rendered.contentType(),
                                    "COLOR",
                                    productColor.getId(),
                                    productColor.getColorName(),
                                    null,
                                    null,
                                    excelRow
                            )
                    );
                }
                continue;
            }

            /*
             * Non-product images keep the previous rule: line images are linked to their source
             * material row, images inside a packing section are linked to that packing, and all
             * other images are retained as Whole BOM files.
             */
            String scope = "BOM";
            String packingId = null;
            String lineId = null;

            if (linesByExcelRow.containsKey(excelRow)) {
                scope = "LINE";
                lineId = linesByExcelRow.get(excelRow).getId();
            } else {
                PackingBoundary boundary = findPackingBoundary(packingBoundaries, excelRow);
                if (boundary != null) {
                    scope = "PACKING";
                    packingId = boundary.packingId;
                }
            }

            String extension = safeExtension(data.suggestFileExtension());
            String fileName = "imported-bom-image-" + imageIndex++ + "." + extension;
            result.add(new ParsedAttachment(
                    data.getData(),
                    fileName,
                    contentType(extension),
                    scope,
                    null,
                    null,
                    packingId,
                    lineId,
                    excelRow
            ));
        }

        result.addAll(productImageByColorId.values());
        return result;
    }

    /**
     * Produces the exact visible backpack image for a Product Color column.
     *
     * Excel stores crop values in 1/1000 percent units (0 to 100000). A collage crop is rendered
     * as a separate PNG before storage so it is never shown as a multi-color collage in the UI.
     */
    private RenderedImage renderProductColorImage(XSSFPicture picture, XSSFPictureData data) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(data.getData());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            BufferedImage original = ImageIO.read(input);
            if (original == null || original.getWidth() <= 0 || original.getHeight() <= 0) {
                return null;
            }

            CropRect crop = cropRectFor(picture, original.getWidth(), original.getHeight());

            /*
             * A full un-cropped image is accepted only when it looks like a portrait product image.
             * This prevents a whole multi-color collage from being linked to one Product Color.
             */
            if (!crop.hasCrop() && original.getHeight() <= original.getWidth()) {
                return null;
            }

            BufferedImage rendered = original.getSubimage(crop.x(), crop.y(), crop.width(), crop.height());
            ImageIO.write(rendered, "png", output);
            return new RenderedImage(output.toByteArray(), "png", "image/png");
        } catch (IOException ex) {
            /*
             * EMF/WMF or unsupported image types remain embedded in the original workbook for
             * export, but cannot be rendered safely as a Product Color preview.
             */
            return null;
        }
    }

    private CropRect cropRectFor(XSSFPicture picture, int imageWidth, int imageHeight) {
        int left = 0;
        int top = 0;
        int right = 0;
        int bottom = 0;

        try {
            var blipFill = picture.getCTPicture().getBlipFill();
            var srcRect = blipFill == null ? null : blipFill.getSrcRect();
            if (srcRect != null) {
                left = cropValue(srcRect.getL());
                top = cropValue(srcRect.getT());
                right = cropValue(srcRect.getR());
                bottom = cropValue(srcRect.getB());
            }
        } catch (RuntimeException ignored) {
            // Treat unreadable crop metadata as an uncropped picture.
        }

        int x = clamp((int) Math.round(imageWidth * (left / 100000.0d)), 0, Math.max(imageWidth - 1, 0));
        int y = clamp((int) Math.round(imageHeight * (top / 100000.0d)), 0, Math.max(imageHeight - 1, 0));
        int endX = clamp((int) Math.round(imageWidth * (1d - right / 100000.0d)), x + 1, imageWidth);
        int endY = clamp((int) Math.round(imageHeight * (1d - bottom / 100000.0d)), y + 1, imageHeight);

        return new CropRect(x, y, Math.max(1, endX - x), Math.max(1, endY - y), left > 0 || top > 0 || right > 0 || bottom > 0);
    }

    private int cropValue(Object value) {
        if (value == null) return 0;
        try {
            return clamp(Integer.parseInt(String.valueOf(value)), 0, 100000);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private String safeFilePart(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9]+", "-");
        return clean.isBlank() ? "color" : clean.replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT);
    }

    private record CropRect(int x, int y, int width, int height, boolean hasCrop) { }

    private record RenderedImage(byte[] bytes, String extension, String contentType) { }

    private PackingBoundary findPackingBoundary(List<PackingBoundary> boundaries, int excelRow) {
        for (PackingBoundary boundary : boundaries) {
            if (excelRow >= boundary.startRow && excelRow <= boundary.endRow) return boundary;
        }
        return null;
    }

    private int nullSafeRow(int value) {
        return Math.max(1, value);
    }

    private String safeExtension(String extension) {
        if (extension == null || extension.isBlank()) return "png";
        return extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String contentType(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "emf" -> "image/emf";
            case "wmf" -> "image/wmf";
            default -> "image/png";
        };
    }

    private String text(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || column < 0) return "";
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        return formatter.formatCellValue(cell, evaluator).replace('\n', ' ').trim();
    }

    private BigDecimal decimal(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        String raw = text(row, column, formatter, evaluator).replace(",", "").trim();
        if (!hasText(raw) || "-".equals(raw)) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer integer(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        BigDecimal value = decimal(row, column, formatter, evaluator);
        return value == null ? null : value.intValue();
    }

    private boolean isPackingTitle(String firstCell) {
        return normalize(firstCell).startsWith("PACKING");
    }

    private String cleanPackingName(String value) {
        return value == null ? "Packing" : value.trim().replaceAll("\\s+", " ");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class PackingBoundary {
        private final String packingId;
        private final int startRow;
        private int endRow = Integer.MAX_VALUE;

        private PackingBoundary(String packingId, int startRow) {
            this.packingId = packingId;
            this.startRow = startRow;
        }
    }

    public record ParsedAttachment(
            byte[] bytes,
            String originalFileName,
            String contentType,
            String scope,
            String productColorId,
            String colorKey,
            String packingId,
            String lineId,
            Integer sourceRowNumber
    ) { }

    public record ParsedBom(
            BomHeader header,
            List<BomProductColor> productColors,
            List<BomLine> coreLines,
            List<BomPacking> packings,
            List<ParsedAttachment> importedAttachments
    ) { }
}
