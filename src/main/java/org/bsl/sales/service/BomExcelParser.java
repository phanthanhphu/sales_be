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
 * Supports both the legacy BOM Details layout and the new layout.
 * The layout is detected from the detail header, then every material value is
 * mapped to stable model fields. A material-line color value links to its
 * Product Color item by productColorId.
 */
@Service
public class BomExcelParser {

    private static final long MAX_BOM_EXCEL_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_BOM_ROWS = 50_000;
    private static final int MAX_BOM_SHEETS = 20;

    public ParsedBom parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new OrderBomMprValidationException("BOM Excel file is required");
        }
        if (file.getSize() > MAX_BOM_EXCEL_BYTES) {
            throw new OrderBomMprValidationException("BOM Excel file must not exceed 50 MB");
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            throw new OrderBomMprValidationException("Only .xlsx or .xls BOM files are supported. Macro-enabled files are not allowed.");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() > MAX_BOM_SHEETS) {
                throw new OrderBomMprValidationException("BOM workbook contains too many sheets (maximum 20)");
            }
            Sheet sheet = findBomSheet(workbook);
            if (sheet.getLastRowNum() + 1 > MAX_BOM_ROWS) {
                throw new OrderBomMprValidationException("BOM sheet contains too many rows (maximum 50,000)");
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.US);

            int headerRow = findColumnHeaderRow(sheet, formatter, evaluator);
            if (headerRow < 0) {
                throw new OrderBomMprValidationException("Cannot find BOM detail header (MTR / SAP CODE or FLEX ID / CONSUMPTION) in Excel file");
            }

            BomExcelLayout layout = BomExcelLayout.detect(sheet, headerRow, formatter, evaluator);
            BomHeader header = parseHeader(sheet, formatter, evaluator);
            Map<Integer, BomProductColor> productColorColumns = findProductColorColumns(sheet, headerRow, layout, formatter, evaluator);
            List<BomProductColor> productColors = distinctProductColors(productColorColumns.values());

            List<BomLine> coreLines = new ArrayList<>();
            List<BomPacking> packings = new ArrayList<>();
            Map<Integer, BomLine> linesByExcelRow = new LinkedHashMap<>();
            List<PackingBoundary> packingBoundaries = new ArrayList<>();

            BomPacking currentPacking = null;
            PackingBoundary currentBoundary = null;
            Integer currentGroupNo = null;
            String inheritedMaterialType = "";

            for (int rowIndex = layout.dataStartRow(headerRow); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
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

                BomLine line = parseLine(row, rowIndex + 1, productColorColumns, layout, formatter, evaluator);
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

                PackingBoundary defaultBoundary = new PackingBoundary(firstPacking.getId(), layout.dataStartRow(headerRow) + 1);
                defaultBoundary.endRow = sheet.getLastRowNum() + 1;
                packingBoundaries.add(defaultBoundary);
            }

            List<ParsedAttachment> importedAttachments = extractImages(
                    sheet,
                    headerRow,
                    layout,
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
            if (isBomDetailHeader(value)) return r;
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
        header.setMarkerDate(findValueAfterLabel(sheet, "MARKER DATE", formatter, evaluator));
        header.setMarkerMaker(findValueAfterLabel(sheet, "MARKER MAKER", formatter, evaluator));
        header.setFactoryProduct(findValueAfterLabel(sheet, "FACTORY PRODUCT", formatter, evaluator));
        header.setBomMaker(findValueAfterLabel(sheet, "BOM MAKER", formatter, evaluator));
        header.setSize(findValueAfterLabel(sheet, "SIZE", formatter, evaluator));
        header.setBomDate(findValueAfterLabel(sheet, "BOM DATE", formatter, evaluator));
        return header;
    }

    private String findValueAfterLabel(Sheet sheet, String wantedLabel, DataFormatter formatter, FormulaEvaluator evaluator) {
        String expected = normalize(wantedLabel);
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 18); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < Math.min(Math.max(row.getLastCellNum(), 0), 30); c++) {
                String candidate = normalize(text(row, c, formatter, evaluator));
                if (!candidate.contains(expected)) continue;

                // Most header values are placed one to four cells to the right.
                // Stop as soon as the next header label starts. Without this
                // boundary, an empty Rev. Stage cell can incorrectly read the
                // first Comments value as its own value.
                for (int offset = 1; offset <= 4; offset++) {
                    String value = text(row, c + offset, formatter, evaluator);
                    if (!hasText(value)) continue;
                    if (looksLikeHeaderLabel(value)) break;
                    return value;
                }

                // COMMENTS in the new/old templates is placed directly below its label.
                for (int offset = 1; offset <= 2; offset++) {
                    String value = text(sheet.getRow(r + offset), c, formatter, evaluator);
                    if (hasText(value) && !looksLikeHeaderLabel(value)) return value;
                }
            }
        }
        return "";
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

    /** Reads Product Color columns according to the detected legacy/new BOM layout. */
    private Map<Integer, BomProductColor> findProductColorColumns(
            Sheet sheet,
            int headerRow,
            BomExcelLayout layout,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        Map<Integer, BomProductColor> result = new LinkedHashMap<>();
        Map<String, BomProductColor> canonicalByIdentity = new LinkedHashMap<>();

        for (int column = layout.firstColorColumn(); column <= layout.lastColorColumn(); column++) {
            String colorName = text(sheet.getRow(layout.colorNameRow(headerRow)), column, formatter, evaluator);
            if (!hasText(colorName)) continue;

            String patternNumber = text(sheet.getRow(layout.patternNumberRow(headerRow)), column, formatter, evaluator);
            String season = text(sheet.getRow(layout.seasonRow(headerRow)), column, formatter, evaluator);
            String styleNumber = layout.styleNumberRow(headerRow) < 0
                    ? ""
                    : text(sheet.getRow(layout.styleNumberRow(headerRow)), column, formatter, evaluator);
            Integer sequence = layout.sequenceRow(headerRow) < 0
                    ? null
                    : integer(sheet.getRow(layout.sequenceRow(headerRow)), column, formatter, evaluator);

            /*
             * A Product Color is identical only when Pattern Number, Color,
             * Season and Style Number all match. Duplicate Excel columns reuse
             * the first Product Color id instead of creating a second item.
             * Columns with the same visible color but a different identity are
             * still kept as separate Product Colors.
             */
            String identity = productColorIdentityKey(patternNumber, colorName, season, styleNumber);
            BomProductColor existing = canonicalByIdentity.get(identity);
            if (existing != null) {
                if (existing.getSequence() == null && sequence != null) existing.setSequence(sequence);
                result.put(column, existing);
                continue;
            }

            BomProductColor productColor = new BomProductColor();
            productColor.setId(UUID.randomUUID().toString());
            productColor.setColorName(colorName.trim());
            productColor.setPatternNumber(patternNumber);
            productColor.setSeason(season);
            productColor.setStyleNumber(styleNumber);
            productColor.setSequence(sequence);
            productColor.setSourceColumnIndex(column);
            canonicalByIdentity.put(identity, productColor);
            result.put(column, productColor);
        }
        return result;
    }

    private List<BomProductColor> distinctProductColors(Collection<BomProductColor> productColors) {
        LinkedHashMap<String, BomProductColor> uniqueById = new LinkedHashMap<>();
        for (BomProductColor productColor : productColors) {
            if (productColor == null || !hasText(productColor.getId())) continue;
            uniqueById.putIfAbsent(productColor.getId(), productColor);
        }
        return new ArrayList<>(uniqueById.values());
    }

    private String productColorIdentityKey(
            String patternNumber,
            String colorName,
            String season,
            String styleNumber
    ) {
        return String.join("\u001F",
                normalize(patternNumber),
                normalize(colorName),
                normalize(season),
                normalize(styleNumber)
        );
    }

    private BomLine parseLine(
            Row row,
            int excelRow,
            Map<Integer, BomProductColor> productColorColumns,
            BomExcelLayout layout,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        String materialType = text(row, layout.materialTypeColumn(), formatter, evaluator);
        String sapCode = text(row, layout.sapCodeColumn(), formatter, evaluator);
        String detailNo = text(row, layout.detailNoColumn(), formatter, evaluator);
        String position = text(row, layout.positionColumn(), formatter, evaluator);
        String positionDescription = text(row, layout.positionDescriptionColumn(), formatter, evaluator);
        String positionDescriptionExtra = text(row, layout.positionDescriptionExtraColumn(), formatter, evaluator);
        String piece = text(row, layout.pieceCodeColumn(), formatter, evaluator);
        String direction = text(row, layout.directionColumn(), formatter, evaluator);
        String costingUnit = text(row, layout.costingUnitColumn(), formatter, evaluator);
        String consumptionUnit = text(row, layout.consumptionUnitColumn(), formatter, evaluator);
        String remark = text(row, layout.bomRemarkColumn(), formatter, evaluator);
        String additionalRemark = text(row, layout.additionalRemarkColumn(), formatter, evaluator);

        Integer materialGroupNo = integer(row, layout.materialGroupColumn(), formatter, evaluator);
        BigDecimal x = decimal(row, layout.dimensionXColumn(), formatter, evaluator);
        BigDecimal y = decimal(row, layout.dimensionYColumn(), formatter, evaluator);
        BigDecimal qty = decimal(row, layout.quantityColumn(), formatter, evaluator);
        BigDecimal costing = decimal(row, layout.costingColumn(), formatter, evaluator);
        BigDecimal detailConsumption = decimal(row, layout.detailConsumptionColumn(), formatter, evaluator);
        BigDecimal consumptionMpr = decimal(row, layout.consumptionMprColumn(), formatter, evaluator);

        LinkedHashMap<String, BomLineColorValue> linkedValueByProductColorId = new LinkedHashMap<>();
        Map<String, String> legacyColorValues = new LinkedHashMap<>();
        for (Map.Entry<Integer, BomProductColor> entry : productColorColumns.entrySet()) {
            String value = text(row, entry.getKey(), formatter, evaluator);
            if (!hasText(value)) continue;

            String productColorId = entry.getValue().getId();
            if (!linkedValueByProductColorId.containsKey(productColorId)) {
                BomLineColorValue linkedValue = new BomLineColorValue();
                linkedValue.setProductColorId(productColorId);
                linkedValue.setValue(value);
                linkedValueByProductColorId.put(productColorId, linkedValue);
            }
            legacyColorValues.putIfAbsent(entry.getValue().getColorName(), value);
        }
        List<BomLineColorValue> linkedValues = new ArrayList<>(linkedValueByProductColorId.values());

        boolean hasBusinessData = materialGroupNo != null
                || hasText(materialType) || hasText(sapCode) || hasText(detailNo)
                || hasText(position) || hasText(positionDescription) || hasText(positionDescriptionExtra)
                || hasText(piece) || hasText(direction) || x != null || y != null || qty != null
                || costing != null || hasText(costingUnit)
                || detailConsumption != null || consumptionMpr != null || hasText(consumptionUnit)
                || hasText(remark) || hasText(additionalRemark) || !linkedValues.isEmpty();
        if (!hasBusinessData) return null;

        BomLine line = new BomLine();
        line.setId(UUID.randomUUID().toString());
        line.setSourceRowNumber(excelRow);
        line.setMaterialGroupNo(materialGroupNo);
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
        line.setCostingUnit(costingUnit);
        line.setDetailConsumption(detailConsumption);
        line.setConsumptionNet(consumptionMpr);
        line.setConsumptionUnit(consumptionUnit);
        line.setBomRemark(remark);
        line.setAdditionalRemark(additionalRemark);
        line.setProductColorValues(linkedValues);
        line.setColorValues(legacyColorValues);
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
            BomExcelLayout layout,
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

        /*
         * A cropped Excel collage normally reuses the same source picture for several Product Color
         * columns. A real standalone Product Color picture normally has its own source bytes, even
         * when its canvas is wider than it is tall. Count source reuse so standalone landscape PNG/JPG
         * images are not incorrectly discarded as collages.
         */
        Map<Integer, Integer> pictureSourceUseCounts = new HashMap<>();
        for (XSSFShape shape : drawing.getShapes()) {
            if (!(shape instanceof XSSFPicture picture)) continue;
            XSSFPictureData pictureData = picture.getPictureData();
            if (pictureData == null || pictureData.getData() == null || pictureData.getData().length == 0) continue;
            pictureSourceUseCounts.merge(Arrays.hashCode(pictureData.getData()), 1, Integer::sum);
        }

        int imageIndex = 1;

        for (XSSFShape shape : drawing.getShapes()) {
            if (!(shape instanceof XSSFPicture picture)) continue;

            XSSFClientAnchor anchor = picture.getClientAnchor();
            int anchorRowIndex = anchor == null ? headerRow : anchor.getRow1();
            int excelRow = anchorRowIndex + 1;
            int excelColumn = anchor == null ? -1 : anchor.getCol1();
            XSSFPictureData data = picture.getPictureData();
            if (data == null || data.getData() == null || data.getData().length == 0) continue;

            BomProductColor productColor = productColorColumns.get(excelColumn);
            if (anchorRowIndex < layout.dataStartRow(headerRow) && productColor != null) {
                int sourceUseCount = pictureSourceUseCounts.getOrDefault(Arrays.hashCode(data.getData()), 1);
                boolean anchoredInsideOneColumn = anchor != null && anchor.getCol1() == anchor.getCol2();
                boolean standaloneProductImage = sourceUseCount == 1 || anchoredInsideOneColumn;
                RenderedImage rendered = renderProductColorImage(picture, data, standaloneProductImage);
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

            boolean dedicatedLineImage = linesByExcelRow.containsKey(excelRow)
                    && (!layout.hasImageColumn() || excelColumn == layout.imageColumn());
            if (dedicatedLineImage) {
                scope = "LINE";
                lineId = linesByExcelRow.get(excelRow).getId();
            } else {
                PackingBoundary boundary = findPackingBoundary(packingBoundaries, excelRow);
                if (boundary != null) {
                    scope = "PACKING";
                    packingId = boundary.packingId;
                }
            }

            String extension = pictureExtension(data);
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
    private RenderedImage renderProductColorImage(
            XSSFPicture picture,
            XSSFPictureData data,
            boolean standaloneProductImage
    ) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(data.getData());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            BufferedImage original = ImageIO.read(input);
            if (original == null || original.getWidth() <= 0 || original.getHeight() <= 0) {
                return null;
            }

            CropRect crop = cropRectFor(picture, original.getWidth(), original.getHeight());

            /*
             * Keep rejecting an un-cropped landscape image only when the same source is reused by
             * several Excel shapes (the normal collage pattern). A standalone image anchored in one
             * Product Color column is valid even when its transparent/white canvas is landscape.
             */
            if (!crop.hasCrop() && original.getHeight() <= original.getWidth() && !standaloneProductImage) {
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

    private String pictureExtension(XSSFPictureData data) {
        if (data == null) return "png";
        return switch (data.getPictureType()) {
            case Workbook.PICTURE_TYPE_EMF -> "emf";
            case Workbook.PICTURE_TYPE_WMF -> "wmf";
            case Workbook.PICTURE_TYPE_JPEG -> "jpg";
            case Workbook.PICTURE_TYPE_PNG -> "png";
            case Workbook.PICTURE_TYPE_DIB -> "bmp";
            default -> safeExtension(data.suggestFileExtension());
        };
    }

    private String safeExtension(String extension) {
        if (extension == null || extension.isBlank()) return "png";
        String clean = extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return clean.isBlank() ? "png" : clean;
    }

    private String contentType(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "emf" -> "image/x-emf";
            case "wmf" -> "image/x-wmf";
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
        String visible = text(row, column, formatter, evaluator).trim();
        if (!hasText(visible) || "-".equals(visible)) return null;
        String raw = visible.replace(" ", "");
        if (raw.contains(",") && raw.contains(".")) raw = raw.replace(",", "");
        else if (raw.contains(",")) raw = raw.replace(",", ".");
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw new OrderBomMprValidationException(
                    "Invalid number '" + visible + "' at row " + (row == null ? "?" : row.getRowNum() + 1)
                            + ", column " + excelColumn(column)
            );
        }
    }

    private Integer integer(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        BigDecimal value = decimal(row, column, formatter, evaluator);
        if (value == null) return null;
        try {
            return value.intValueExact();
        } catch (ArithmeticException ex) {
            throw new OrderBomMprValidationException(
                    "Expected a whole number at row " + (row == null ? "?" : row.getRowNum() + 1)
                            + ", column " + excelColumn(column)
            );
        }
    }

    private String excelColumn(int zeroBasedIndex) {
        if (zeroBasedIndex < 0) return "?";
        int value = zeroBasedIndex + 1;
        StringBuilder result = new StringBuilder();
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.toString();
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
