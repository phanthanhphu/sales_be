package org.bsl.cartonloading.service;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.cartonloading.dto.ImportMode;
import org.bsl.cartonloading.dto.ImportRowError;
import org.bsl.cartonloading.dto.MasterDataImportResult;
import org.bsl.cartonloading.dto.PackingAllocationRequest;
import org.bsl.cartonloading.exception.MasterDataValidationException;
import org.bsl.cartonloading.exception.OrderBomMprNotFoundException;
import org.bsl.cartonloading.exception.OrderBomMprValidationException;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.PackingAllocationLine;
import org.bsl.cartonloading.model.PackingOrder;
import org.bsl.cartonloading.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.support.MasterDataExcelSupport;
import org.bsl.cartonloading.support.MasterDataTextNormalizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class PackingAllocationService {
    private static final String MASTER_NAME = "PACKING_ALLOCATION";
    private static final int BUSINESS_COLUMN_COUNT = 29;

    private final PackingAllocationLineRepository lineRepository;
    private final PackingOrderService orderService;
    private final MasterDataExcelSupport excelSupport;

    public PackingAllocationService(
            PackingAllocationLineRepository lineRepository,
            PackingOrderService orderService,
            MasterDataExcelSupport excelSupport
    ) {
        this.lineRepository = lineRepository;
        this.orderService = orderService;
        this.excelSupport = excelSupport;
    }

    public Page<PackingAllocationLine> list(
            String buyerCode,
            String orderId,
            String keyword,
            String poNumber,
            String articleNumber,
            String styleNumber,
            String color,
            String sizeValue,
            String shipmentMode,
            String status,
            int page,
            int pageSize
    ) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        String keywordKey = key(keyword);
        String poKey = key(poNumber);
        String articleKey = key(articleNumber);
        String styleKey = key(styleNumber);
        String colorKey = key(color);
        String sizeKey = key(sizeValue);
        String shipmentKey = key(shipmentMode);
        String statusKey = key(status);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(pageSize, 200)));

        List<PackingAllocationLine> rows = lineRepository
                .findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode())
                .stream()
                .filter(line -> keywordKey == null || matchesKeyword(line, keywordKey))
                .filter(line -> poKey == null || contains(line.getPoNumber(), poKey))
                .filter(line -> articleKey == null || contains(line.getArticleNumber(), articleKey))
                .filter(line -> styleKey == null || contains(line.getStyleNumber(), styleKey) || contains(line.getStyle(), styleKey))
                .filter(line -> colorKey == null || contains(line.getColor(), colorKey))
                .filter(line -> sizeKey == null || contains(line.getSize(), sizeKey))
                .filter(line -> shipmentKey == null || contains(line.getShipmentMode(), shipmentKey))
                .filter(line -> statusKey == null || contains(line.getStatus(), statusKey))
                .sorted(Comparator.comparing(PackingAllocationLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.toList());

        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public PackingAllocationLine get(String buyerCode, String orderId, String lineId) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        return lineRepository.findByIdAndOrderIdAndBuyerCode(lineId, order.getId(), order.getBuyerCode())
                .orElseThrow(() -> new OrderBomMprNotFoundException("Packing allocation row not found"));
    }

    public PackingAllocationLine create(String buyerCode, String orderId, PackingAllocationRequest request) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        validateBusinessRequired(request);
        PackingAllocationLine entity = new PackingAllocationLine();
        entity.setBuyerCode(order.getBuyerCode());
        entity.setOrderId(order.getId());
        entity.setLineNo(nextLineNo(order));
        apply(entity, request);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(RequestActor.current());
        entity.setUpdatedBy(RequestActor.current());
        PackingAllocationLine saved = lineRepository.save(entity);
        orderService.touchFromMaster(order);
        return saved;
    }

    public PackingAllocationLine update(
            String buyerCode,
            String orderId,
            String lineId,
            PackingAllocationRequest request
    ) {
        PackingAllocationLine entity = get(buyerCode, orderId, lineId);
        validateBusinessRequired(request);
        apply(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(RequestActor.current());
        PackingAllocationLine saved = lineRepository.save(entity);
        orderService.touchFromMaster(orderService.getEntity(buyerCode, orderId));
        return saved;
    }

    public void delete(String buyerCode, String orderId, String lineId) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        lineRepository.delete(get(buyerCode, orderId, lineId));
        orderService.touchFromMaster(order);
    }

    public MasterDataImportResult upload(
            String buyerCode,
            String orderId,
            MultipartFile file,
            ImportMode mode
    ) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        ImportMode effectiveMode = mode == null ? ImportMode.CREATE_ONLY : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<RowCandidate> candidates = new ArrayList<>();
        int totalRows = 0;
        boolean actionWorkbook = false;

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, "ALLOCATION");
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            actionWorkbook = header != null
                    && "ACTION".equals(MasterDataTextNormalizer.headerKey(excelSupport.text(header, 0, evaluator)))
                    && "KEY".equals(MasterDataTextNormalizer.headerKey(excelSupport.text(header, 1, evaluator)));
            int offset = actionWorkbook ? 2 : 0;
            validateHeaders(sheet, evaluator, offset);

            Map<String, Integer> fileKeys = new HashMap<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                int excelRow = rowIndex + 1;
                try {
                    String rawAction = actionWorkbook ? clean(text(row, 0, evaluator)) : null;
                    String entityKey = actionWorkbook ? clean(text(row, 1, evaluator)) : null;
                    boolean businessBlank = isBusinessBlank(row, offset, evaluator);
                    if (businessBlank && entityKey == null
                            && (rawAction == null || "CREATE".equalsIgnoreCase(rawAction))) continue;

                    totalRows++;
                    String action = actionWorkbook ? normalizeAction(rawAction) : null;

                    if (actionWorkbook) {
                        if (action == null) throw new OrderBomMprValidationException("ACTION is required: CREATE, UPDATE or DELETE");
                        if (("UPDATE".equals(action) || "DELETE".equals(action)) && entityKey == null) {
                            throw new OrderBomMprValidationException("KEY is required for " + action);
                        }
                        if (entityKey != null) {
                            Integer previous = fileKeys.putIfAbsent(entityKey, excelRow);
                            if (previous != null) throw new OrderBomMprValidationException("Duplicate KEY in file; first used at row " + previous);
                        }
                        PackingAllocationRequest request = null;
                        if (!"DELETE".equals(action)) {
                            request = toRequest(row, evaluator, offset);
                            validateBusinessRequired(request);
                        }
                        if (!"CREATE".equals(action)) {
                            lineRepository.findByIdAndOrderIdAndBuyerCode(entityKey, order.getId(), order.getBuyerCode())
                                    .orElseThrow(() -> new OrderBomMprValidationException("KEY does not belong to the selected Buyer/Order"));
                        }
                        candidates.add(new RowCandidate(excelRow, action, entityKey, request));
                    } else {
                        PackingAllocationRequest request = toRequest(row, evaluator, 0);
                        validateBusinessRequired(request);
                        candidates.add(new RowCandidate(excelRow, "LEGACY", null, request));
                    }
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (MasterDataValidationException ex) {
            errors.add(new ImportRowError(1, "file", cleanMessage(ex)));
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import ALLOCATION: " + cleanMessage(ex)));
        }

        if (candidates.isEmpty() && errors.isEmpty()) {
            errors.add(new ImportRowError(1, "file", "Sheet ALLOCATION does not contain actionable data rows"));
        }
        if (!errors.isEmpty()) {
            return MasterDataImportResult.rejected(MASTER_NAME, effectiveMode, totalRows, errors);
        }

        MasterDataImportResult result = new MasterDataImportResult();
        result.setMasterData(MASTER_NAME);
        result.setMode(effectiveMode);
        result.setApplied(true);
        result.setTotalRows(totalRows);
        result.setValidRows(candidates.size());

        if (actionWorkbook) {
            int lineNo = nextLineNo(order);
            for (RowCandidate candidate : candidates) {
                switch (candidate.action()) {
                    case "CREATE" -> {
                        PackingAllocationLine entity = newLine(order, candidate.request(), lineNo++);
                        lineRepository.save(entity);
                        result.setCreated(result.getCreated() + 1);
                    }
                    case "UPDATE" -> {
                        PackingAllocationLine entity = lineRepository
                                .findByIdAndOrderIdAndBuyerCode(candidate.entityKey(), order.getId(), order.getBuyerCode())
                                .orElseThrow(() -> new OrderBomMprNotFoundException("Packing allocation row not found"));
                        apply(entity, candidate.request());
                        entity.setUpdatedAt(LocalDateTime.now());
                        entity.setUpdatedBy(RequestActor.current());
                        lineRepository.save(entity);
                        result.setUpdated(result.getUpdated() + 1);
                    }
                    case "DELETE" -> {
                        PackingAllocationLine entity = lineRepository
                                .findByIdAndOrderIdAndBuyerCode(candidate.entityKey(), order.getId(), order.getBuyerCode())
                                .orElseThrow(() -> new OrderBomMprNotFoundException("Packing allocation row not found"));
                        lineRepository.delete(entity);
                        result.setDeleted(result.getDeleted() + 1);
                    }
                    default -> throw new IllegalStateException("Unsupported ACTION " + candidate.action());
                }
            }
        } else {
            if (effectiveMode == ImportMode.REPLACE_ALL) {
                lineRepository.deleteByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode());
            }
            if (effectiveMode == ImportMode.UPSERT) {
                applyUpsert(order, candidates, result);
            } else {
                int lineNo = nextLineNo(order);
                for (RowCandidate candidate : candidates) {
                    PackingAllocationLine entity = newLine(order, candidate.request(), lineNo++);
                    lineRepository.save(entity);
                    result.setCreated(result.getCreated() + 1);
                }
            }
        }

        orderService.touchFromMaster(order);
        return result;
    }

    private void applyUpsert(PackingOrder order, List<RowCandidate> candidates, MasterDataImportResult result) {
        Map<String, PackingAllocationLine> existing = new LinkedHashMap<>();
        for (PackingAllocationLine line : lineRepository.findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode())) {
            existing.putIfAbsent(identityKey(line), line);
        }

        int nextLine = nextLineNo(order);
        for (RowCandidate candidate : candidates) {
            String identity = identityKey(candidate.request());
            PackingAllocationLine entity = existing.get(identity);
            if (entity == null) {
                entity = newLine(order, candidate.request(), nextLine++);
                PackingAllocationLine saved = lineRepository.save(entity);
                existing.put(identity, saved);
                result.setCreated(result.getCreated() + 1);
            } else {
                apply(entity, candidate.request());
                entity.setUpdatedAt(LocalDateTime.now());
                entity.setUpdatedBy(RequestActor.current());
                lineRepository.save(entity);
                result.setUpdated(result.getUpdated() + 1);
            }
        }
    }

    private PackingAllocationLine newLine(PackingOrder order, PackingAllocationRequest request, int lineNo) {
        PackingAllocationLine entity = new PackingAllocationLine();
        entity.setBuyerCode(order.getBuyerCode());
        entity.setOrderId(order.getId());
        entity.setLineNo(lineNo);
        apply(entity, request);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(RequestActor.current());
        entity.setUpdatedBy(RequestActor.current());
        return entity;
    }

    private PackingAllocationRequest toRequest(Row row, FormulaEvaluator evaluator, int offset) {
        return new PackingAllocationRequest(
                text(row, offset + 0, evaluator),
                text(row, offset + 1, evaluator),
                text(row, offset + 2, evaluator),
                text(row, offset + 3, evaluator),
                text(row, offset + 4, evaluator),
                excelSupport.localDate(row, offset + 5, evaluator),
                excelSupport.localDate(row, offset + 6, evaluator),
                text(row, offset + 7, evaluator),
                text(row, offset + 8, evaluator),
                text(row, offset + 9, evaluator),
                text(row, offset + 10, evaluator),
                text(row, offset + 11, evaluator),
                text(row, offset + 12, evaluator),
                excelSupport.decimal(row, offset + 13, evaluator),
                text(row, offset + 14, evaluator),
                excelSupport.decimal(row, offset + 15, evaluator),
                excelSupport.decimal(row, offset + 16, evaluator),
                excelSupport.decimal(row, offset + 17, evaluator),
                excelSupport.decimal(row, offset + 18, evaluator),
                excelSupport.decimal(row, offset + 19, evaluator),
                excelSupport.decimal(row, offset + 20, evaluator),
                excelSupport.decimal(row, offset + 21, evaluator),
                excelSupport.decimal(row, offset + 22, evaluator),
                text(row, offset + 23, evaluator),
                excelSupport.decimal(row, offset + 24, evaluator),
                text(row, offset + 25, evaluator),
                text(row, offset + 26, evaluator),
                excelSupport.decimal(row, offset + 27, evaluator),
                excelSupport.decimal(row, offset + 28, evaluator)
        );
    }

    private void validateHeaders(Sheet sheet, FormulaEvaluator evaluator, int offset) {
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) throw new MasterDataValidationException("Sheet ALLOCATION does not have a header row");

        Map<Integer, String[]> expected = new HashMap<>();
        expected.put(0, new String[]{"SUPPLIERNAME"});
        expected.put(1, new String[]{"ESSUPPLIER"});
        expected.put(2, new String[]{"PRODUCTIONFACILITY"});
        expected.put(3, new String[]{"CONTAINER"});
        expected.put(4, new String[]{"MODEOFSHIPMENT"});
        expected.put(5, new String[]{"ETD"});
        expected.put(6, new String[]{"ETA"});
        expected.put(7, new String[]{"ESPO"});
        expected.put(8, new String[]{"ESARTICLE"});
        expected.put(9, new String[]{"STYLE"});
        expected.put(10, new String[]{"STYLE"});
        expected.put(11, new String[]{"COLOR"});
        expected.put(12, new String[]{"SIZE"});
        expected.put(13, new String[]{"QTYPERCTN"});
        expected.put(14, new String[]{"INVOICE"});
        expected.put(15, new String[]{"TOTALPCS"});
        expected.put(16, new String[]{"TOTALCTNS"});
        expected.put(17, new String[]{"PCSAIR"});
        expected.put(18, new String[]{"CTNSAIR"});
        expected.put(19, new String[]{"PCSSEA"});
        expected.put(20, new String[]{"CTNSSEA"});
        expected.put(21, new String[]{"CBMAIR"});
        expected.put(22, new String[]{"KGAIR"});
        expected.put(23, new String[]{"STATUS"});
        expected.put(24, new String[]{"OPENPOQTYOVERDEL"});
        expected.put(25, new String[]{"REMARKS"});
        expected.put(26, new String[]{"YOLOT"});
        expected.put(27, new String[]{"HCTN"});
        expected.put(28, new String[]{"CBMCTN"});

        for (Map.Entry<Integer, String[]> entry : expected.entrySet()) {
            int column = offset + entry.getKey();
            String actual = MasterDataTextNormalizer.headerKey(excelSupport.text(header, column, evaluator));
            boolean matched = false;
            for (String prefix : entry.getValue()) {
                if (actual.startsWith(prefix)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw new MasterDataValidationException(
                        "Invalid ALLOCATION header at column " + excelSupport.excelColumn(column)
                                + ". Received '" + excelSupport.text(header, column, evaluator) + "'"
                );
            }
        }
    }

    private void validateBusinessRequired(PackingAllocationRequest request) {
        required(request.poNumber(), "e.s. PO # is required");
        required(request.articleNumber(), "e.s. Article # is required");
        required(request.styleNumber(), "STYLE# is required");
        required(request.style(), "STYLE is required");
        required(request.color(), "Color is required");
        required(request.size(), "Size is required");
        requiredNumber(request.qtyPerCarton(), "Qty Per Ctn is required");
        requiredNumber(request.totalPcs(), "Total pcs is required");
        requiredNumber(request.totalCartons(), "Total ctns is required");
    }

    private void apply(PackingAllocationLine entity, PackingAllocationRequest request) {
        entity.setSupplierName(clean(request.supplierName()));
        entity.setSupplierNumber(clean(request.supplierNumber()));
        entity.setProductionFacility(clean(request.productionFacility()));
        entity.setContainerNumber(clean(request.containerNumber()));
        entity.setShipmentMode(clean(request.shipmentMode()));
        entity.setEtd(request.etd());
        entity.setEta(request.eta());
        entity.setPoNumber(clean(request.poNumber()));
        entity.setArticleNumber(clean(request.articleNumber()));
        entity.setStyleNumber(clean(request.styleNumber()));
        entity.setStyle(clean(request.style()));
        entity.setColor(clean(request.color()));
        entity.setSize(clean(request.size()));
        entity.setQtyPerCarton(request.qtyPerCarton());
        entity.setInvoiceNumber(clean(request.invoiceNumber()));
        entity.setTotalPcs(request.totalPcs());
        entity.setTotalCartons(request.totalCartons());
        entity.setPcsAir(zero(request.pcsAir()));
        entity.setCartonsAir(zero(request.cartonsAir()));
        entity.setPcsSea(zero(request.pcsSea()));
        entity.setCartonsSea(zero(request.cartonsSea()));
        entity.setCbmAir(zero(request.cbmAir()));
        entity.setKgAir(zero(request.kgAir()));
        entity.setStatus(clean(request.status()));
        entity.setOpenPoQtyOverdel(zero(request.openPoQtyOverdel()));
        entity.setRemarks(clean(request.remarks()));
        entity.setYoLotNumber(clean(request.yoLotNumber()));
        entity.setHeightCarton(request.hCtn());
        entity.setCbmCtn(request.cbmCtn());
    }

    private int nextLineNo(PackingOrder order) {
        return lineRepository.findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode()).stream()
                .map(PackingAllocationLine::getLineNo)
                .filter(value -> value != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private boolean matchesKeyword(PackingAllocationLine line, String keyword) {
        return contains(line.getSupplierName(), keyword)
                || contains(line.getSupplierNumber(), keyword)
                || contains(line.getProductionFacility(), keyword)
                || contains(line.getContainerNumber(), keyword)
                || contains(line.getPoNumber(), keyword)
                || contains(line.getArticleNumber(), keyword)
                || contains(line.getStyleNumber(), keyword)
                || contains(line.getStyle(), keyword)
                || contains(line.getColor(), keyword)
                || contains(line.getSize(), keyword)
                || contains(line.getInvoiceNumber(), keyword)
                || contains(line.getStatus(), keyword)
                || contains(line.getRemarks(), keyword)
                || contains(line.getYoLotNumber(), keyword);
    }

    private String identityKey(PackingAllocationLine line) {
        return String.join("|",
                keyOrBlank(line.getPoNumber()), keyOrBlank(line.getArticleNumber()), keyOrBlank(line.getStyleNumber()),
                keyOrBlank(line.getColor()), keyOrBlank(line.getSize()), keyOrBlank(line.getContainerNumber()),
                keyOrBlank(line.getShipmentMode()), keyOrBlank(line.getInvoiceNumber()), keyOrBlank(line.getYoLotNumber())
        );
    }

    private String identityKey(PackingAllocationRequest request) {
        return String.join("|",
                keyOrBlank(request.poNumber()), keyOrBlank(request.articleNumber()), keyOrBlank(request.styleNumber()),
                keyOrBlank(request.color()), keyOrBlank(request.size()), keyOrBlank(request.containerNumber()),
                keyOrBlank(request.shipmentMode()), keyOrBlank(request.invoiceNumber()), keyOrBlank(request.yoLotNumber())
        );
    }

    private String text(Row row, int column, FormulaEvaluator evaluator) {
        return clean(excelSupport.text(row, column, evaluator));
    }

    private String required(String value, String message) {
        String clean = clean(value);
        if (clean == null) throw new OrderBomMprValidationException(message);
        return clean;
    }

    private void requiredNumber(BigDecimal value, String message) {
        if (value == null) throw new OrderBomMprValidationException(message);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String clean(String value) {
        return MasterDataTextNormalizer.trimToNull(value);
    }

    private String key(String value) {
        return MasterDataTextNormalizer.key(value);
    }

    private String keyOrBlank(String value) {
        String result = key(value);
        return result == null ? "" : result;
    }

    private boolean contains(String value, String keyword) {
        String source = key(value);
        return source != null && source.contains(keyword);
    }

    private String cleanMessage(Throwable ex) {
        String message = ex == null ? null : ex.getMessage();
        return message == null || message.isBlank() ? "Invalid data" : message;
    }

    private String normalizeAction(String value) {
        String action = clean(value);
        if (action == null) return null;
        action = action.toUpperCase(Locale.ROOT);
        return switch (action) {
            case "CREATE", "UPDATE", "DELETE" -> action;
            default -> throw new OrderBomMprValidationException("Invalid ACTION '" + value + "'. Use CREATE, UPDATE or DELETE");
        };
    }

    private boolean isBusinessBlank(Row row, int offset, FormulaEvaluator evaluator) {
        if (row == null) return true;
        for (int i = 0; i < BUSINESS_COLUMN_COUNT; i++) {
            if (clean(excelSupport.text(row, offset + i, evaluator)) != null) return false;
        }
        return true;
    }

    private record RowCandidate(int excelRow, String action, String entityKey, PackingAllocationRequest request) {
    }
}
