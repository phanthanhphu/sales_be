package org.bsl.sales.service;

import org.bsl.sales.dto.MprGenerateRequest;
import org.bsl.sales.dto.MprSelectionRequest;
import org.bsl.sales.dto.MprLineUpdateRequest;
import org.bsl.sales.dto.MprBatchDeleteResult;
import org.bsl.sales.dto.MprBatchUpdateRequest;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.*;
import org.bsl.sales.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 1 MPR generator.
 *
 * It creates the full MPR table structure (without POUCH) and populates the
 * fields that are deterministically available from BOM, MAT_INFO, LOSS,
 * Vendor Code, and Currency Master. Stock, Ship To, Sales Comment, Sample,
 * Due Date, and purchase calculations are intentionally left for the next
 * phase because their input sources have not been finalised.
 */
@Service
public class MprService {
    private final MprDocumentRepository mprRepository;
    private final BomDocumentRepository bomRepository;
    private final SalesOrderRepository orderRepository;
    private final MatInfoRepository matInfoRepository;
    private final LossRepository lossRepository;
    private final VendorCodeRepository vendorCodeRepository;
    private final ShipToRepository shipToRepository;
    private final CurrencyMasterService currencyMasterService;
    private final OrderService orderService;
    private final MprBomReviewService bomReviewService;

    public MprService(
            MprDocumentRepository mprRepository,
            BomDocumentRepository bomRepository,
            SalesOrderRepository orderRepository,
            MatInfoRepository matInfoRepository,
            LossRepository lossRepository,
            VendorCodeRepository vendorCodeRepository,
            ShipToRepository shipToRepository,
            CurrencyMasterService currencyMasterService,
            OrderService orderService,
            MprBomReviewService bomReviewService
    ) {
        this.mprRepository = mprRepository;
        this.bomRepository = bomRepository;
        this.orderRepository = orderRepository;
        this.matInfoRepository = matInfoRepository;
        this.lossRepository = lossRepository;
        this.vendorCodeRepository = vendorCodeRepository;
        this.shipToRepository = shipToRepository;
        this.currencyMasterService = currencyMasterService;
        this.orderService = orderService;
        this.bomReviewService = bomReviewService;
    }

    public MprDocument getByOrder(String orderId) {
        orderService.get(orderId);
        return mprRepository.findByOrderId(orderId)
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR has not been created for this order"));
    }

    /**
     * Preview retains the rows that are already saved in the current MPR and
     * places the newly selected rows after them. The preview is not persisted.
     */
    public MprDocument preview(String orderId, MprGenerateRequest request) {
        MprDocument candidate = build(orderId, request);
        Optional<MprDocument> current = mprRepository.findByOrderId(orderId);
        return current.map(existing -> mergeForPreview(existing, candidate)).orElse(candidate);
    }

    /**
     * Create MPR is cumulative.
     *
     * The first action creates the MPR. Every later action adds only genuinely
     * new material rows for the selected BOM + Product Color. Core BOM rows are
     * always included; selected Packing rows are added after Core rows. It never
     * replaces or removes rows created in an earlier action.
     */
    public MprDocument generate(String orderId, MprGenerateRequest request) {
        MprDocument candidate = build(orderId, request);
        if (safeList(candidate.getLines()).isEmpty()) {
            throw new OrderBomMprValidationException(
                    "No MPR lines were created. Check BOM Core/Packing lines with a Consumption Unit."
            );
        }

        Optional<MprDocument> current = mprRepository.findByOrderId(orderId);
        LocalDateTime now = LocalDateTime.now();

        if (current.isPresent()) {
            MprDocument entity = current.get();

            List<MprLine> newLines = onlyNewLines(entity.getLines(), candidate.getLines());
            if (newLines.isEmpty()) {
                throw new OrderBomMprValidationException(
                        "All selected Product Color / Core / Packing material rows already exist in this MPR. "
                                + "Choose a different Product Color or Packing."
                );
            }

            List<MprSelection> newSelections = selectionsForGeneratedLines(candidate.getSelections(), newLines);
            List<MprSelection> allSelections = new ArrayList<>(safeList(entity.getSelections()));
            allSelections.addAll(newSelections);

            List<MprLine> allLines = new ArrayList<>(safeList(entity.getLines()));
            allLines.addAll(newLines);

            entity.setMprNo(firstNonBlank(entity.getMprNo(), candidate.getMprNo()));
            entity.setStatus("DRAFT");
            entity.setPoQuantity(totalPoQuantity(allSelections));
            entity.setSampleQuantity(firstNonNull(entity.getSampleQuantity(), candidate.getSampleQuantity()));
            entity.setSelections(allSelections);
            entity.setLines(allLines);
            entity.setUpdatedAt(now);
            entity.setUpdatedBy(RequestActor.current());
            candidate = mprRepository.save(entity);
        } else {
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            candidate.setCreatedBy(RequestActor.current());
            candidate.setUpdatedBy(RequestActor.current());
            candidate.setStatus("DRAFT");
            candidate = mprRepository.save(candidate);
        }

        orderService.markMprDraft(orderId);
        return candidate;
    }

    /**
     * Returns a non-persisted combined view: current saved lines + genuinely
     * new lines from the selection. Duplicate material rows are not shown twice.
     */
    private MprDocument mergeForPreview(MprDocument existing, MprDocument candidate) {
        List<MprLine> newLines = onlyNewLines(existing.getLines(), candidate.getLines());
        List<MprSelection> newSelections = selectionsForGeneratedLines(candidate.getSelections(), newLines);

        List<MprSelection> selections = new ArrayList<>(safeList(existing.getSelections()));
        selections.addAll(newSelections);

        List<MprLine> lines = new ArrayList<>(safeList(existing.getLines()));
        lines.addAll(newLines);

        // Keep candidate without an id. The FE uses that to know this is still
        // a preview and should not allow editing the newly previewed rows.
        candidate.setMprNo(firstNonBlank(existing.getMprNo(), candidate.getMprNo()));
        candidate.setStatus(existing.getStatus());
        candidate.setPoQuantity(totalPoQuantity(selections));
        candidate.setSampleQuantity(firstNonNull(existing.getSampleQuantity(), candidate.getSampleQuantity()));
        candidate.setSelections(selections);
        candidate.setLines(lines);
        return candidate;
    }

    /**
     * Keeps the existing MPR item when Core and/or a later Packing points to the
     * same material for the same Product Color. This also prevents a later Add
     * To MPR action from adding the same procurement item a second time.
     */
    private List<MprLine> onlyNewLines(List<MprLine> existing, List<MprLine> incoming) {
        Map<String, BomDocument> bomCache = new HashMap<>();
        Set<String> existingKeys = safeList(existing).stream()
                .filter(Objects::nonNull)
                .map(line -> mprMaterialKeyForComparison(line, bomCache))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MprLine> result = new ArrayList<>();
        for (MprLine line : safeList(incoming)) {
            if (line == null) continue;
            if (existingKeys.add(mprMaterialKeyForComparison(line, bomCache))) {
                result.add(line);
            }
        }
        return result;
    }

    /**
     * Supports MPR records created before sourceBomDedupKey was introduced.
     * When their original BOM line still exists, reconstruct the same full BOM
     * key so an Add To MPR action will not re-add an identical old Core/Packing
     * row. If the source no longer exists, safely fall back to the legacy MPR key.
     */
    private String mprMaterialKeyForComparison(MprLine line, Map<String, BomDocument> bomCache) {
        if (line == null) return "";
        if (hasText(line.getSourceBomDedupKey())) return line.getSourceBomDedupKey();

        String bomId = trim(line.getBomId());
        String sourceLineId = trim(line.getSourceLineId());
        if (hasText(bomId) && hasText(sourceLineId)) {
            BomDocument bom = bomCache.computeIfAbsent(bomId, this::findBomForDeduplication);
            BomLine source = findBomLineById(bom, sourceLineId);
            if (bom != null && source != null) {
                String selectedColor = firstNonBlank(line.getStyleColor(), resolveProductColorName(bom, line.getProductColorId()));
                String materialColor = firstNonBlank(line.getMatColor(), materialColorFor(source, bom, selectedColor));
                return bomDuplicateKey(bom, source, selectedColor, materialColor);
            }
        }
        return mprMaterialKey(line);
    }

    private BomDocument findBomForDeduplication(String bomId) {
        return bomRepository.findById(bomId).orElse(null);
    }

    private BomLine findBomLineById(BomDocument bom, String lineId) {
        if (bom == null || blank(lineId)) return null;
        for (BomLine line : safeList(bom.getCoreLines())) {
            if (line != null && lineId.equals(line.getId())) return line;
        }
        for (BomPacking packing : safeList(bom.getPackings())) {
            for (BomLine line : safeList(packing == null ? null : packing.getLines())) {
                if (line != null && lineId.equals(line.getId())) return line;
            }
        }
        return null;
    }

    /**
     * Duplicate identity is based on the full set of BOM fields that define a
     * material row. Packing and source row are intentionally excluded: when a
     * Core row and a selected Packing row have the same BOM data, Core wins
     * because it is appended first. Product Color (and its resolved Child/MAT
     * Color) are included, so rows for different colors are never merged.
     *
     * New MPR lines persist sourceBomDedupKey. Older saved MPRs do not have that
     * field, so their legacy MPR fields are used as a safe fallback.
     */
    private String mprMaterialKey(MprLine line) {
        if (line != null && hasText(line.getSourceBomDedupKey())) {
            return line.getSourceBomDedupKey();
        }
        String color = firstNonBlank(line == null ? null : line.getProductColorId(),
                line == null ? null : line.getStyleColor());
        return normalize(line == null ? null : line.getBomId())
                + "|" + normalize(color)
                + "|" + normalize(line == null ? null : line.getMaterialType())
                + "|" + normalize(line == null ? null : line.getSapCode())
                + "|" + normalize(line == null ? null : line.getMatFullDescription())
                + "|" + normalize(line == null ? null : line.getMatColor())
                + "|" + normalize(line == null ? null : line.getMatUnit())
                + "|" + decimalKey(line == null ? null : line.getYield());
    }

    /** Builds the stable duplicate key directly from the source BOM row. */
    private String bomDuplicateKey(BomDocument bom, BomLine source, String selectedColor, String materialColor) {
        return normalize(bom == null ? null : bom.getId())
                + "|" + normalize(selectedColor)                         // Product Colors / selected Style Color
                + "|" + normalize(materialColor)                          // resolved Child Color / MAT Color
                + "|" + normalize(source == null ? null : source.getMaterialType())
                + "|" + normalize(source == null ? null : source.getSapCode())
                + "|" + normalize(source == null ? null : source.getDetailNo())
                + "|" + normalize(source == null ? null : source.getPosition())
                + "|" + normalize(source == null ? null : source.getPositionDescription())
                + "|" + normalize(source == null ? null : source.getPositionDescriptionExtra())
                + "|" + normalize(source == null ? null : source.getPieceCode())
                + "|" + decimalKey(source == null ? null : source.getDimensionX())
                + "|" + decimalKey(source == null ? null : source.getDimensionY())
                + "|" + decimalKey(source == null ? null : source.getQuantity())
                + "|" + normalize(source == null ? null : source.getDirection())
                + "|" + decimalKey(source == null ? null : source.getCosting())
                + "|" + normalize(source == null ? null : source.getCostingUnit())
                + "|" + decimalKey(source == null ? null : source.getConsumptionNet())
                + "|" + normalize(source == null ? null : source.getConsumptionUnit())
                + "|" + normalize(source == null ? null : source.getBomRemark());
    }

    /** Makes 1, 1.0 and 1.000 equivalent for duplicate comparison. */
    private String decimalKey(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** Adds only selection batches that produced at least one persisted row. */
    private List<MprSelection> selectionsForGeneratedLines(
            List<MprSelection> selections,
            List<MprLine> generatedLines
    ) {
        Set<String> batchIds = safeList(generatedLines).stream()
                .filter(Objects::nonNull)
                .map(MprLine::getGenerationBatchId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MprSelection> result = new ArrayList<>();
        for (MprSelection selection : safeList(selections)) {
            if (selection != null && batchIds.contains(selection.getBatchId())) {
                result.add(selection);
            }
        }
        return result;
    }

    /**
     * MPR header PO Qty is only a summary. One Color may be added with more
     * than one Packing, but its PO Qty must be counted once per BOM + Color.
     */
    private BigDecimal totalPoQuantity(List<MprSelection> selections) {
        Map<String, BigDecimal> quantityByBomColor = new LinkedHashMap<>();

        for (MprSelection selection : safeList(selections)) {
            if (selection == null) continue;
            for (Map.Entry<String, BigDecimal> entry : (selection.getPoQtyByColor() == null
                    ? Map.<String, BigDecimal>of()
                    : selection.getPoQtyByColor()).entrySet()) {
                String key = normalize(selection.getBomId()) + "|" + normalize(entry.getKey());
                quantityByBomColor.putIfAbsent(key, safe(entry.getValue()));
            }
        }

        return quantityByBomColor.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
    }

    public void delete(String orderId) {
        MprDocument mpr = getByOrder(orderId);
        mprRepository.delete(mpr);
    }

    /**
     * Deletes everything created in one Create / Add To MPR action.
     *
     * Only the rows that have the requested generationBatchId are removed.
     * All other Product Color / Packing batches remain unchanged.
     *
     * When the removed batch was the last remaining batch, the now-empty MPR
     * document is removed as well, so the next action starts as Create MPR.
     */
    public MprBatchDeleteResult deleteBatch(String orderId, String batchId) {
        if (blank(batchId)) {
            throw new OrderBomMprValidationException("MPR generation batch id is required");
        }

        MprDocument mpr = getByOrder(orderId);
        MprSelection batch = safeList(mpr.getSelections()).stream()
                .filter(item -> item != null && batchId.equals(item.getBatchId()))
                .findFirst()
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR generation batch not found"));

        List<MprLine> existingLines = new ArrayList<>(safeList(mpr.getLines()));
        List<MprLine> remainingLines = existingLines.stream()
                .filter(line -> line == null || !batchId.equals(line.getGenerationBatchId()))
                .collect(Collectors.toCollection(ArrayList::new));
        int removedLineCount = existingLines.size() - remainingLines.size();

        if (removedLineCount == 0) {
            throw new OrderBomMprNotFoundException(
                    "No saved MPR lines were found for this generation batch"
            );
        }

        List<MprSelection> remainingSelections = safeList(mpr.getSelections()).stream()
                .filter(item -> item == null || !batchId.equals(item.getBatchId()))
                .collect(Collectors.toCollection(ArrayList::new));

        if (remainingLines.isEmpty()) {
            mprRepository.delete(mpr);
            return new MprBatchDeleteResult(true, removedLineCount, 0, null);
        }

        mpr.setLines(remainingLines);
        mpr.setSelections(remainingSelections);
        mpr.setPoQuantity(totalPoQuantity(remainingSelections));
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());

        MprDocument saved = mprRepository.save(mpr);
        return new MprBatchDeleteResult(
                false,
                removedLineCount,
                safeList(saved.getLines()).size(),
                saved
        );
    }

    /**
     * Applies new PO Qty and Ship To values to every MPR line from one saved
     * Create/Add batch. Values are edited per Product Color and are applied to
     * every selected packing line of that color in the batch.
     */
    public MprDocument updateBatch(String orderId, String batchId, MprBatchUpdateRequest request) {
        if (blank(batchId)) {
            throw new OrderBomMprValidationException("MPR generation batch id is required");
        }
        if (request == null) {
            throw new OrderBomMprValidationException("MPR batch data is required");
        }

        MprDocument mpr = getByOrder(orderId);
        MprSelection batch = safeList(mpr.getSelections()).stream()
                .filter(item -> item != null && batchId.equals(item.getBatchId()))
                .findFirst()
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR generation batch not found"));

        Map<String, ShipTo> shipToById = buildShipToCache();
        Map<String, Loss> lossByKey = lossRepository.findAll().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        item -> normalize(item.getMaterialGroup()),
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        Map<String, BigDecimal> quantities = new LinkedHashMap<>(
                batch.getPoQtyByColor() == null ? Map.of() : batch.getPoQtyByColor()
        );
        Map<String, List<String>> shipToIdsByColor = new LinkedHashMap<>();
        Map<String, String> shipToByColor = new LinkedHashMap<>();

        for (String colorName : safeList(batch.getColors())) {
            BigDecimal suppliedQty = mapValueForColor(request.poQtyByColor(), colorName, "");
            BigDecimal poQty = suppliedQty == null ? safe(quantities.get(colorName)) : suppliedQty;
            if (poQty.signum() < 0) {
                throw new OrderBomMprValidationException("PO Qty cannot be negative for Product Color " + colorName);
            }
            quantities.put(colorName, poQty);

            List<String> suppliedShipToIds = mapValueForColor(request.shipToIdsByColor(), colorName, "");
            List<String> existingShipToIds = batch.getShipToIdsByColor() == null
                    ? List.of()
                    : batch.getShipToIdsByColor().getOrDefault(colorName, List.of());
            List<String> ids = suppliedShipToIds == null
                    ? normalizeShipToIds(existingShipToIds, shipToById)
                    : normalizeShipToIds(suppliedShipToIds, shipToById);
            if (ids.isEmpty()) {
                throw new OrderBomMprValidationException("Select at least one Ship To for Product Color " + colorName);
            }
            shipToIdsByColor.put(colorName, ids);
            shipToByColor.put(colorName, shipToDisplay(ids, shipToById));
        }

        batch.setPoQtyByColor(quantities);
        batch.setShipToIdsByColor(shipToIdsByColor);
        batch.setShipToByColor(shipToByColor);

        for (MprLine line : safeList(mpr.getLines())) {
            if (line == null || !batchId.equals(line.getGenerationBatchId())) continue;
            String colorName = firstNonBlank(line.getStyleColor(), resolveBatchColor(batch, line.getProductColorId()));
            BigDecimal poQty = mapValueForColor(quantities, colorName, line.getProductColorId());
            if (poQty == null) continue;
            List<String> ids = mapValueForColor(shipToIdsByColor, colorName, line.getProductColorId());
            String shipTo = mapValueForColor(shipToByColor, colorName, line.getProductColorId());

            line.setPoQuantity(poQty);
            line.setShipToIds(ids == null ? new ArrayList<>() : new ArrayList<>(ids));
            line.setShipTo(trim(shipTo));
            BigDecimal factor = lossFactor(
                    lossByKey.get(normalize(line.getMaterialType())),
                    poQty.add(safe(mpr.getSampleQuantity()))
            );
            line.setLossFactor(factor);
            line.setTotalYield(multiply(line.getYield(), factor));
            line.setMatRequiredQuantity(multiply(line.getTotalYield(), poQty));
        }

        mpr.setPoQuantity(totalPoQuantity(mpr.getSelections()));
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());
        return mprRepository.save(mpr);
    }

    /**
     * Updates one saved MPR row. Source ids are never changed; only display and
     * commercial values are editable. The Phase 1 calculation fields are
     * recalculated after every update.
     */
    public MprDocument updateLine(String orderId, String lineId, MprLineUpdateRequest request) {
        if (request == null) {
            throw new OrderBomMprValidationException("MPR item data is required");
        }
        if (blank(lineId)) {
            throw new OrderBomMprValidationException("MPR item id is required");
        }

        MprDocument mpr = getByOrder(orderId);
        MprLine line = safeList(mpr.getLines()).stream()
                .filter(item -> item != null && lineId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR item not found"));

        applyLineUpdate(line, request);
        // Sales changes to values sourced from BOM stay pending until BOM reviews them.
        bomReviewService.capturePendingReview(line);
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());
        return mprRepository.save(mpr);
    }

    /** Deletes only one MPR item and keeps the other MPR rows unchanged. */
    public MprDocument deleteLine(String orderId, String lineId) {
        if (blank(lineId)) {
            throw new OrderBomMprValidationException("MPR item id is required");
        }

        MprDocument mpr = getByOrder(orderId);
        List<MprLine> remaining = new ArrayList<>(safeList(mpr.getLines()));
        boolean removed = remaining.removeIf(item -> item != null && lineId.equals(item.getId()));
        if (!removed) {
            throw new OrderBomMprNotFoundException("MPR item not found");
        }

        mpr.setLines(remaining);
        removeEmptyBatchSelections(mpr);
        mpr.setPoQuantity(totalPoQuantity(mpr.getSelections()));
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());
        return mprRepository.save(mpr);
    }

    /**
     * A batch is no longer shown in the FE when all of its lines were deleted
     * one-by-one. Legacy selections without a batch id remain untouched.
     */
    private void removeEmptyBatchSelections(MprDocument mpr) {
        Set<String> activeBatchIds = safeList(mpr.getLines()).stream()
                .filter(Objects::nonNull)
                .map(MprLine::getGenerationBatchId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MprSelection> remaining = safeList(mpr.getSelections()).stream()
                .filter(selection -> selection == null
                        || !hasText(selection.getBatchId())
                        || activeBatchIds.contains(selection.getBatchId()))
                .collect(Collectors.toCollection(ArrayList::new));

        mpr.setSelections(remaining);
    }

    private void applyLineUpdate(MprLine target, MprLineUpdateRequest request) {
        validateLineUpdate(request);

        target.setStyleDescription(trim(request.styleDescription()));
        target.setStyleColor(trim(request.styleColor()));
        target.setStyleColorKey(styleColorKey(target.getStyleDescription(), target.getStyleColor()));
        target.setShipTo(trim(request.shipTo()));
        target.setSalesComment(trim(request.salesComment()));

        target.setSapCode(trim(request.sapCode()));
        target.setBomLineNo(request.bomLineNo());
        target.setMaterialType(trim(request.materialType()));
        target.setMatFullDescription(trim(request.matFullDescription()));
        target.setMatColor(trim(request.matColor()));
        target.setMatUnit(trim(request.matUnit()));
        target.setYield(request.yield());
        target.setLossFactor(request.lossFactor());
        target.setPoQuantity(request.poQuantity());

        // Phase 1 calculation fields are controlled by the MPR system.
        target.setTotalYield(multiply(target.getYield(), target.getLossFactor()));
        target.setMatRequiredQuantity(multiply(target.getTotalYield(), target.getPoQuantity()));

        target.setCurrency(normalizeCurrency(request.currency()));
        target.setMatPriceWithoutTax(request.matPriceWithoutTax());
        target.setShortNameSupplier(trim(request.shortNameSupplier()));
        target.setVendorCode(trim(request.vendorCode()));
        target.setVendorName(trim(request.vendorName()));
        target.setMatCharger(trim(request.matCharger()));
    }

    private void validateLineUpdate(MprLineUpdateRequest request) {
        if (blank(request.materialType())) {
            throw new OrderBomMprValidationException("Material Type is required");
        }
        if (blank(request.matFullDescription())) {
            throw new OrderBomMprValidationException("MAT Full Description is required");
        }
        if (blank(request.matUnit())) {
            throw new OrderBomMprValidationException("MAT Unit is required");
        }
        requireNonNegative(request.bomLineNo() == null ? null : BigDecimal.valueOf(request.bomLineNo()), "BOM No");
        requireNonNegative(request.yield(), "Yield");
        requirePositive(request.lossFactor(), "Loss Factor");
        requireNonNegative(request.poQuantity(), "PO Qty");
        requireNonNegative(request.matPriceWithoutTax(), "MAT Price (W/O Tax)");
    }

    private void requireNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new OrderBomMprValidationException(field + " cannot be negative");
        }
    }

    private void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new OrderBomMprValidationException(field + " must be greater than zero");
        }
    }

    private MprDocument build(String orderId, MprGenerateRequest request) {
        if (request == null || request.selections() == null || request.selections().isEmpty()) {
            throw new OrderBomMprValidationException("Select at least one submitted BOM");
        }

        SalesOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderBomMprNotFoundException("Order not found"));

        Map<String, MatInfo> matByKey = buildMatInfoCache();
        Map<String, Loss> lossByKey = lossRepository.findAll().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        item -> normalize(item.getMaterialGroup()),
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        Map<String, VendorCode> vendorByKey = vendorCodeRepository.findAll().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        item -> normalize(item.getShortNameSupplier()),
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        Map<String, ShipTo> shipToById = buildShipToCache();

        List<MprSelection> selections = new ArrayList<>();
        List<MprLine> generated = new ArrayList<>();
        Set<String> seenBomIds = new HashSet<>();
        BigDecimal totalPoQuantity = BigDecimal.ZERO;
        BigDecimal sampleQuantity = safe(request.sampleQuantity());

        for (MprSelectionRequest selectionRequest : request.selections()) {
            if (selectionRequest == null || blank(selectionRequest.bomId())) {
                throw new OrderBomMprValidationException("BOM id is required for every selection");
            }
            if (!seenBomIds.add(selectionRequest.bomId())) {
                throw new OrderBomMprValidationException("The same BOM can only be selected once");
            }

            BomDocument bom = bomRepository.findById(selectionRequest.bomId())
                    .orElseThrow(() -> new OrderBomMprNotFoundException("Selected BOM not found"));
            if (!orderId.equals(bom.getOrderId())) {
                throw new OrderBomMprValidationException("Selected BOM does not belong to this order");
            }
            if (!"SUBMITTED".equalsIgnoreCase(bom.getStatus())) {
                throw new OrderBomMprValidationException("Only submitted BOM can be used to create MPR: " + bom.getBomNo());
            }

            List<String> colors = normalizeSelectionColors(selectionRequest.colors(), bom);
            if (colors.isEmpty()) {
                throw new OrderBomMprValidationException("Select at least one Product Color for BOM " + bom.getBomNo());
            }

            Map<String, BigDecimal> poQtyByColor = new LinkedHashMap<>();
            Map<String, List<String>> shipToIdsByColor = new LinkedHashMap<>();
            Map<String, String> shipToByColor = new LinkedHashMap<>();
            for (String colorName : colors) {
                BigDecimal poQuantity = poQuantityForColor(selectionRequest, bom, colorName, request.poQuantity());
                if (poQuantity.signum() < 0) {
                    throw new OrderBomMprValidationException("PO Qty cannot be negative for Product Color " + colorName);
                }
                List<String> shipToIds = shipToIdsForColor(selectionRequest, bom, colorName, shipToById);
                poQtyByColor.put(colorName, poQuantity);
                shipToIdsByColor.put(colorName, shipToIds);
                shipToByColor.put(colorName, shipToDisplay(shipToIds, shipToById));
                totalPoQuantity = totalPoQuantity.add(poQuantity);
            }

            MprSelection selection = new MprSelection();
            selection.setBatchId(UUID.randomUUID().toString());
            selection.setCreatedAt(LocalDateTime.now());
            selection.setCreatedBy(RequestActor.current());
            selection.setBomId(bom.getId());
            selection.setBomNo(bom.getBomNo());
            selection.setBomName(bom.getBomName());
            selection.setColors(colors);
            selection.setPackingIds(selectionRequest.packingIds() == null ? new ArrayList<>() : new ArrayList<>(selectionRequest.packingIds()));
            selection.setPoQtyByColor(poQtyByColor);
            selection.setShipToIdsByColor(shipToIdsByColor);
            selection.setShipToByColor(shipToByColor);
            selections.add(selection);

            /*
             * Core (non-Packing) BOM rows are the base data for every selected
             * Product Color. Packing is optional: selected Packing rows are added
             * after Core rows for the same color. A duplicated procurement item
             * (all requested BOM fields + Product Color are identical) is retained once,
             * with the Core row winning because it is added first.
             *
             * Examples:
             *   - RED, no Packing: 10 Core rows => 10 MPR rows.
             *   - RED + US Packing: 10 Core + 20 US rows => 30 rows when unique.
             *   - GREEN + Packing B, PURPLE + Packing K: each color stays in its
             *     own consecutive group and is deduplicated only within that color.
             */
            List<BomPacking> selectedPackings = new ArrayList<>();
            for (String packingId : safeList(selection.getPackingIds())) {
                if (blank(packingId)) continue;
                BomPacking packing = safeList(bom.getPackings()).stream()
                        .filter(item -> item != null && Objects.equals(item.getId(), packingId))
                        .findFirst()
                        .orElseThrow(() -> new OrderBomMprValidationException(
                                "Packing not found in BOM " + bom.getBomNo()
                        ));
                selectedPackings.add(packing);
            }

            // Color is the outer loop so MPR remains clearly separated by color.
            for (String colorName : colors) {
                List<MprLine> rowsForColor = new ArrayList<>();
                Set<String> materialKeysForColor = new LinkedHashSet<>();

                // 1) Always start from original BOM data without a Packing.
                for (BomLine coreLine : safeList(bom.getCoreLines())) {
                    appendForColorDeduplicated(
                            rowsForColor, materialKeysForColor,
                            bom, null, coreLine, "CORE", colorName,
                            poQtyByColor.get(colorName), sampleQuantity, selection.getBatchId(),
                            shipToIdsByColor.get(colorName), shipToByColor.get(colorName),
                            matByKey, lossByKey, vendorByKey
                    );
                }

                // 2) Add only the selected Packing data that applies to this color.
                for (BomPacking packing : selectedPackings) {
                    if (!packingAppliesToColor(packing, bom, colorName)) continue;
                    for (BomLine packingLine : safeList(packing.getLines())) {
                        appendForColorDeduplicated(
                                rowsForColor, materialKeysForColor,
                                bom, packing, packingLine, "PACKING", colorName,
                                poQtyByColor.get(colorName), sampleQuantity, selection.getBatchId(),
                                shipToIdsByColor.get(colorName), shipToByColor.get(colorName),
                                matByKey, lossByKey, vendorByKey
                        );
                    }
                }
                generated.addAll(rowsForColor);
            }
        }

        MprDocument mpr = new MprDocument();
        mpr.setOrderId(order.getId());
        mpr.setMprNo(blank(request.mprNo()) ? "MPR-" + order.getOrderNo() : request.mprNo().trim());
        mpr.setPoQuantity(totalPoQuantity);
        mpr.setSampleQuantity(sampleQuantity);
        mpr.setSelections(selections);
        mpr.setLines(generated);
        mpr.setStatus("DRAFT");
        return mpr;
    }

    /**
     * Appends one generated line only when its MPR procurement identity has not
     * appeared already for the current Product Color. Core is invoked before
     * Packing, so a Core/Packing duplicate keeps the Core source row.
     */
    private void appendForColorDeduplicated(
            List<MprLine> out,
            Set<String> materialKeys,
            BomDocument bom,
            BomPacking packing,
            BomLine source,
            String section,
            String selectedColor,
            BigDecimal poQuantity,
            BigDecimal sampleQuantity,
            String generationBatchId,
            List<String> shipToIds,
            String shipTo,
            Map<String, MatInfo> matByKey,
            Map<String, Loss> lossByKey,
            Map<String, VendorCode> vendorByKey
    ) {
        int startSize = out.size();
        appendForColor(
                out, bom, packing, source, section, selectedColor,
                poQuantity, sampleQuantity, generationBatchId, shipToIds, shipTo,
                matByKey, lossByKey, vendorByKey
        );
        if (out.size() == startSize) return;

        MprLine generatedLine = out.get(out.size() - 1);
        if (!materialKeys.add(mprMaterialKey(generatedLine))) {
            out.remove(out.size() - 1);
        }
    }

    /** Creates one MPR row for one BOM material line and one selected Product Color. */
    private void appendForColor(
            List<MprLine> out,
            BomDocument bom,
            BomPacking packing,
            BomLine source,
            String section,
            String selectedColor,
            BigDecimal poQuantity,
            BigDecimal sampleQuantity,
            String generationBatchId,
            List<String> shipToIds,
            String shipTo,
            Map<String, MatInfo> matByKey,
            Map<String, Loss> lossByKey,
            Map<String, VendorCode> vendorByKey
    ) {
        if (!isPurchasableMaterialLine(source)) return;

        String description = bomMaterialDescription(source);
        String materialColor = materialColorFor(source, bom, selectedColor);
        MatInfo mat = findMatInfo(matByKey, source, materialColor);
        String materialType = trim(source.getMaterialType());
        BigDecimal yield = source.getConsumptionNet();
        BigDecimal linePoQuantity = safe(poQuantity);
        BigDecimal lineSampleQuantity = safe(sampleQuantity);
        BigDecimal totalOrderQuantity = linePoQuantity.add(lineSampleQuantity);
        BigDecimal factor = lossFactor(lossByKey.get(normalize(materialType)), totalOrderQuantity);
        BigDecimal totalYield = multiply(yield, factor);
        BigDecimal matRequiredQuantity = multiply(totalYield, linePoQuantity);

        MprLine line = new MprLine();
        line.setId(UUID.randomUUID().toString());
        line.setBomId(bom.getId());
        line.setSourceLineId(source.getId());
        line.setPackingId(packing == null ? null : packing.getId());
        line.setPackingName(packing == null ? null : trim(packing.getPackingName()));
        line.setSection(section);
        line.setProductColorId(productColorIdFor(bom, selectedColor));
        line.setGenerationBatchId(generationBatchId);
        // Persist the full BOM-based key so Core/Packing rows are deduplicated
        // only when every requested BOM field and Product Color are identical.
        line.setSourceBomDedupKey(bomDuplicateKey(bom, source, selectedColor, materialColor));

        // A-C are created from BOM Header and the chosen Product Color.
        String styleDescription = firstNonBlank(
                bom.getHeader() == null ? null : bom.getHeader().getStyleName(),
                bom.getBomName()
        );
        line.setStyleDescription(styleDescription);
        line.setStyleColor(trim(selectedColor));
        line.setStyleColorKey(styleColorKey(styleDescription, selectedColor));

        // D-E: Ship To is selected by Sales together with the Product Color.
        line.setShipToIds(shipToIds == null ? new ArrayList<>() : new ArrayList<>(shipToIds));
        line.setShipTo(trim(shipTo));
        line.setSalesComment(null);

        // G-Q: BOM values are the source of truth. Master Data only fills missing commercial fields.
        line.setSapCode(firstNonBlank(source.getSapCode(), mat == null ? null : mat.getFlexId()));
        line.setBomLineNo(source.getMaterialGroupNo());
        line.setMaterialType(materialType);
        line.setMatFullDescription(description);
        line.setMatColor(materialColor);
        line.setMatUnit(firstNonBlank(source.getConsumptionUnit(), source.getCostingUnit(), mat == null ? null : mat.getMatUnit()));
        line.setYield(yield);
        line.setLossFactor(factor);
        line.setTotalYield(totalYield);
        line.setPoQuantity(linePoQuantity);
        line.setMatRequiredQuantity(matRequiredQuantity);

        // R-X will be collected in the stock/sample phase. They remain blank in Phase 1.
        line.setSampleQuantity(null);
        line.setMatSampleQuantity(null);
        line.setMcdStock(null);
        line.setCmcdStock(null);
        line.setSapStockQuantity(null);
        line.setNonSapStockQuantity(null);
        line.setPurchaseQuantity(null);

        // Y-AD: fields that can be linked with certainty from current Master Data.
        if (mat != null) {
            line.setCurrency(mat.getCurrency());
            line.setMatPriceWithoutTax(mat.getMatPriceWithoutTax());
            line.setShortNameSupplier(mat.getShortNameSupplier());
        }
        VendorCode vendor = vendorByKey.get(normalize(line.getShortNameSupplier()));
        if (vendor != null) {
            line.setVendorCode(vendor.getVendorCode());
            line.setVendorName(vendor.getVendorName());
            line.setMatCharger(vendor.getMatCharger());
        }

        // AE-AF use a safe Currency Master snapshot where a usable rate exists.
        snapshotCurrency(line);

        // AG-AI depend on stock, due date, and final purchasing rules, so remain blank for now.
        line.setMatAmountUsd(null);
        line.setMatDueDate(null);
        line.setTotalMatAmountPerStyle(null);
        line.setSourceRemark(source.getBomRemark());
        out.add(line);
    }

    /**
     * MPR is generated only for BOM lines that have a Consumption Unit.
     * A Costing Unit or MAT_INFO Unit must not be used as a substitute.
     */
    private boolean isPurchasableMaterialLine(BomLine source) {
        return source != null
                && !blank(source.getMaterialType())
                && !blank(source.getConsumptionUnit());
    }

    private Map<String, MatInfo> buildMatInfoCache() {
        Map<String, MatInfo> result = new LinkedHashMap<>();
        for (MatInfo item : matInfoRepository.findAll()) {
            if (item == null) continue;
            String fullKey = materialKey(item.getMaterialType(), item.getMatFullDescription(), item.getMatColor());
            result.putIfAbsent(fullKey, item);
            result.putIfAbsent(materialKey(item.getMaterialType(), item.getMatFullDescription(), ""), item);
        }
        return result;
    }

    /**
     * First try exact BOM Material Type + Description + Color, then fall back
     * to the same Material Type + Description without a color. A missing row
     * never removes the material from the MPR.
     */
    private MatInfo findMatInfo(Map<String, MatInfo> cache, BomLine line, String materialColor) {
        String materialType = line == null ? "" : line.getMaterialType();
        LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        descriptions.add(bomMaterialDescription(line));
        if (line != null) {
            descriptions.add(trim(line.getPosition()));
            descriptions.add(trim(line.getPositionDescription()));
            descriptions.add(trim(line.getPositionDescriptionExtra()));
        }

        for (String description : descriptions) {
            if (blank(description)) continue;
            MatInfo exact = cache.get(materialKey(materialType, description, materialColor));
            if (exact != null) return exact;
        }
        for (String description : descriptions) {
            if (blank(description)) continue;
            MatInfo withoutColor = cache.get(materialKey(materialType, description, ""));
            if (withoutColor != null) return withoutColor;
        }
        return null;
    }

    /** BOM Position is the material name in the supplied BOM template. */
    private String bomMaterialDescription(BomLine line) {
        if (line == null) return "";
        return firstNonBlank(
                line.getPosition(),
                line.getPositionDescription(),
                line.getPositionDescriptionExtra()
        );
    }

    /**
     * The MPR API accepts Product Color ids and names. The MPR stores readable
     * Color Names while keeping Product Color Id internally for traceability.
     */
    private List<String> normalizeSelectionColors(List<String> requestColors, BomDocument bom) {
        LinkedHashSet<String> colors = new LinkedHashSet<>();
        if (requestColors != null) {
            for (String requestColor : requestColors) {
                if (blank(requestColor)) continue;
                String colorName = resolveProductColorName(bom, requestColor);
                if (blank(colorName)) {
                    throw new OrderBomMprValidationException("Selected Product Color does not belong to BOM " + bom.getBomNo());
                }
                colors.add(colorName);
            }
        }
        return new ArrayList<>(colors);
    }

    private BigDecimal poQuantityForColor(
            MprSelectionRequest selectionRequest,
            BomDocument bom,
            String colorName,
            BigDecimal fallbackQuantity
    ) {
        Map<String, BigDecimal> supplied = selectionRequest.poQtyByColor();
        if (supplied != null && !supplied.isEmpty()) {
            String productColorId = productColorIdFor(bom, colorName);
            for (Map.Entry<String, BigDecimal> entry : supplied.entrySet()) {
                if (entry.getValue() == null) continue;
                String key = entry.getKey();
                if ((hasText(productColorId) && productColorId.equals(key)) || normalize(key).equals(normalize(colorName))) {
                    return entry.getValue();
                }
            }
        }
        return safe(fallbackQuantity);
    }

    private Map<String, ShipTo> buildShipToCache() {
        Map<String, ShipTo> result = new LinkedHashMap<>();
        for (ShipTo item : shipToRepository.findAll()) {
            if (item == null || !item.isActive() || blank(item.getId())) continue;
            result.put(item.getId(), item);
        }
        return result;
    }

    /** Ship To is required for every selected Product Color at MPR creation. */
    private List<String> shipToIdsForColor(
            MprSelectionRequest request,
            BomDocument bom,
            String colorName,
            Map<String, ShipTo> shipToById
    ) {
        List<String> supplied = mapValueForColor(
                request == null ? null : request.shipToIdsByColor(),
                colorName,
                productColorIdFor(bom, colorName)
        );
        List<String> ids = normalizeShipToIds(supplied, shipToById);
        if (ids.isEmpty()) {
            throw new OrderBomMprValidationException("Select at least one Ship To for Product Color " + colorName);
        }
        return ids;
    }

    private List<String> normalizeShipToIds(List<String> source, Map<String, ShipTo> shipToById) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : safeList(source)) {
            String clean = trim(id);
            if (clean.isEmpty()) continue;
            if (!shipToById.containsKey(clean)) {
                throw new OrderBomMprValidationException("Selected Ship To is inactive or does not exist");
            }
            ids.add(clean);
        }
        return new ArrayList<>(ids);
    }

    private String shipToDisplay(List<String> ids, Map<String, ShipTo> shipToById) {
        return safeList(ids).stream()
                .map(shipToById::get)
                .filter(Objects::nonNull)
                .map(ShipTo::getShipToName)
                .filter(this::hasText)
                .map(String::trim)
                .collect(Collectors.joining(" + "));
    }

    /** Supports Product Color id and readable Product Color name in payload maps. */
    private <T> T mapValueForColor(Map<String, T> values, String colorName, String productColorId) {
        if (values == null || values.isEmpty()) return null;
        for (Map.Entry<String, T> entry : values.entrySet()) {
            String key = entry.getKey();
            if ((hasText(productColorId) && productColorId.equals(key)) || normalize(key).equals(normalize(colorName))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String resolveBatchColor(MprSelection batch, String productColorId) {
        if (batch == null || blank(productColorId)) return "";
        for (String color : safeList(batch.getColors())) {
            if (normalize(color).equals(normalize(productColorId))) return color;
        }
        return "";
    }

    /** STYLE_COLOR is the readable concatenation of STYLE DESCRIPTION and STYLE COLOR. */
    private String styleColorKey(String styleDescription, String styleColor) {
        return joinNonBlank(styleDescription, styleColor);
    }

    private boolean packingAppliesToColor(BomPacking packing, BomDocument bom, String colorName) {
        if (packing == null) return false;
        String productColorId = productColorIdFor(bom, colorName);
        List<String> productColorIds = safeList(packing.getApplicableProductColorIds());
        if (!productColorIds.isEmpty()) {
            return hasText(productColorId) && productColorIds.contains(productColorId);
        }
        List<String> legacyColors = safeList(packing.getApplicableColors());
        if (legacyColors.isEmpty()) return true;
        return legacyColors.stream().anyMatch(value -> normalize(value).equals(normalize(colorName)));
    }

    /** Returns the Material Color written in the selected Product Color column. */
    private String materialColorFor(BomLine source, BomDocument bom, String selectedColor) {
        String colorName = resolveProductColorName(bom, selectedColor);
        if (blank(colorName)) colorName = selectedColor;
        String productColorId = productColorIdFor(bom, colorName);

        if (hasText(productColorId)) {
            for (BomLineColorValue value : safeList(source.getProductColorValues())) {
                if (value != null && productColorId.equals(value.getProductColorId()) && !blank(value.getValue())) {
                    return value.getValue().trim();
                }
            }
        }
        if (source.getColorValues() != null) {
            for (Map.Entry<String, String> entry : source.getColorValues().entrySet()) {
                if (normalize(entry.getKey()).equals(normalize(colorName)) && !blank(entry.getValue())) {
                    return entry.getValue().trim();
                }
            }
        }
        return "";
    }

    private String productColorIdFor(BomDocument bom, String colorName) {
        for (BomProductColor item : safeList(bom.getProductColors())) {
            if (item != null && normalize(item.getColorName()).equals(normalize(colorName))) {
                return trim(item.getId());
            }
        }
        return "";
    }

    private String resolveProductColorName(BomDocument bom, String idOrName) {
        if (blank(idOrName)) return "";
        for (BomProductColor item : safeList(bom.getProductColors())) {
            if (item == null) continue;
            if (idOrName.trim().equals(item.getId()) || normalize(idOrName).equals(normalize(item.getColorName()))) {
                return trim(item.getColorName());
            }
        }
        for (String color : safeList(bom.getColors())) {
            if (normalize(idOrName).equals(normalize(color))) return trim(color);
        }
        return "";
    }

    private void snapshotCurrency(MprLine line) {
        if (blank(line.getCurrency()) || line.getMatPriceWithoutTax() == null) return;
        try {
            CurrencyMaster currency = currencyMasterService.resolveCurrent(line.getCurrency());
            CurrencyMaster usd = currencyMasterService.resolveCurrent("USD");
            BigDecimal currencyRate = currency.getRateToVnd();
            BigDecimal usdRate = usd.getRateToVnd();
            if (currencyRate == null || usdRate == null || currencyRate.signum() <= 0 || usdRate.signum() <= 0) return;

            line.setCurrencyMasterId(currency.getId());
            line.setRateToVnd(currencyRate);
            line.setMatPriceVnd(line.getMatPriceWithoutTax().multiply(currencyRate).setScale(2, RoundingMode.HALF_UP));

            // Exchange Rate is the divisor required by the MPR template to obtain USD.
            BigDecimal exchangeRate = usdRate.divide(currencyRate, 8, RoundingMode.HALF_UP);
            line.setExchangeRate(exchangeRate);
            line.setMatPriceUsd(line.getMatPriceWithoutTax().divide(exchangeRate, 6, RoundingMode.HALF_UP));
        } catch (RuntimeException ignored) {
            // A missing/invalid Currency Master row must not remove this BOM material from MPR Phase 1.
        }
    }

    private BigDecimal lossFactor(Loss loss, BigDecimal totalOrderQty) {
        if (loss == null) return BigDecimal.ONE;
        BigDecimal percentage;
        if (safe(totalOrderQty).compareTo(new BigDecimal("500")) <= 0) percentage = loss.getLossLt501();
        else if (safe(totalOrderQty).compareTo(new BigDecimal("1500")) <= 0) percentage = loss.getLossLt1501();
        else if (safe(totalOrderQty).compareTo(new BigDecimal("3000")) <= 0) percentage = loss.getLossLt3001();
        else percentage = loss.getLossGte3001();
        return percentage == null ? BigDecimal.ONE : BigDecimal.ONE.add(percentage);
    }

    private BigDecimal multiply(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return null;
        return left.multiply(right).setScale(6, RoundingMode.HALF_UP);
    }

    private String materialKey(String materialType, String description, String color) {
        return normalize(materialType) + "|" + normalize(description) + "|" + normalize(color);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeCurrency(String value) {
        return trim(value).toUpperCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasText(String value) {
        return !blank(value);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value.trim();
        return "";
    }

    private String joinNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(this::hasText)
                .map(String::trim)
                .collect(Collectors.joining(" "));
    }
}
