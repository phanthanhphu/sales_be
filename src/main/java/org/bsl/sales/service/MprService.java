package org.bsl.sales.service;

import org.bsl.sales.dto.MprGenerateRequest;
import org.bsl.sales.dto.MprSelectionRequest;
import org.bsl.sales.dto.MprLineUpdateRequest;
import org.bsl.sales.dto.MprValidationIssue;
import org.bsl.sales.dto.MprValidationResult;
import org.bsl.sales.dto.MprBatchDeleteResult;
import org.bsl.sales.dto.MprBatchUpdateRequest;
import org.bsl.sales.exception.OrderBomMprNotFoundException;
import org.bsl.sales.exception.OrderBomMprValidationException;
import org.bsl.sales.model.*;
import org.bsl.sales.repository.*;
import org.bsl.sales.support.BuyerKeys;
import org.bsl.sales.support.BomMprSourceRevision;
import org.bsl.sales.support.MaterialShipToMappingKeys;
import org.bsl.sales.support.MasterDataTextNormalizer;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 1 MPR generator.
 *
 * It creates the full MPR table structure (without POUCH) and populates the
 * fields that are deterministically available from BOM, MAT_INFO, LOSS,
 * Vender Code, and Currency Master. Sample quantity is supplied when MPR is
 * created. MCD/CMCD/NON-SAP stock can be entered on each MPR line; all derived
 * quantities mirror the approved L.L.BEAN workbook formulas.
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
    private final MaterialShipToMappingRepository materialShipToMappingRepository;
    private final CurrencyMasterService currencyMasterService;
    private final OrderService orderService;
    private final BomLineStore lineStore;
    private final MprBomReviewService bomReviewService;
    private final MprExcelImportService excelImportService;

    public MprService(
            MprDocumentRepository mprRepository,
            BomDocumentRepository bomRepository,
            SalesOrderRepository orderRepository,
            MatInfoRepository matInfoRepository,
            LossRepository lossRepository,
            VendorCodeRepository vendorCodeRepository,
            ShipToRepository shipToRepository,
            MaterialShipToMappingRepository materialShipToMappingRepository,
            CurrencyMasterService currencyMasterService,
            OrderService orderService,
            BomLineStore lineStore,
            MprBomReviewService bomReviewService,
            MprExcelImportService excelImportService
    ) {
        this.mprRepository = mprRepository;
        this.bomRepository = bomRepository;
        this.orderRepository = orderRepository;
        this.matInfoRepository = matInfoRepository;
        this.lossRepository = lossRepository;
        this.vendorCodeRepository = vendorCodeRepository;
        this.shipToRepository = shipToRepository;
        this.materialShipToMappingRepository = materialShipToMappingRepository;
        this.currencyMasterService = currencyMasterService;
        this.orderService = orderService;
        this.lineStore = lineStore;
        this.bomReviewService = bomReviewService;
        this.excelImportService = excelImportService;
    }

    public MprDocument getByOrder(String orderId) {
        SalesOrder order = orderService.get(orderId);
        MprDocument mpr = mprRepository.findByOrderId(orderId)
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR has not been created for this order"));
        normalizeLegacyStatus(mpr);
        String orderBuyer = BuyerKeys.legacyDefault(order.getBuyerKey());
        if (mpr.getBuyerKey() == null || mpr.getBuyerKey().isBlank()) {
            mpr.setBuyerKey(orderBuyer);
        } else if (!orderBuyer.equals(BuyerKeys.legacyDefault(mpr.getBuyerKey()))) {
            throw new OrderBomMprValidationException("MPR belongs to another Buyer");
        }
        // COMPLETED is a frozen snapshot. Reads and exports must not silently
        // recalculate it from newer BOM/master-data values.
        if (!MprDocument.STATUS_COMPLETED.equalsIgnoreCase(mpr.getStatus())) {
            // Backfill BOM-owned fields introduced after older MPR records were saved.
            backfillMissingBomSourceFields(mpr);

            // MAT_INFO can be created/updated after the MPR batch was generated.
            // Fill only missing commercial fields while the MPR is editable.
            backfillMissingMatInfoFields(mpr, orderBuyer);

            // Always return the final MPR data set: every duplicate group keeps
            // exactly one survivor, including legacy records saved before duplicate
            // consolidation was introduced.
            mpr.setLines(consolidateFinalLines(mpr.getLines()));
            orderLinesForDisplay(mpr);
            // Recalculate once with one bulk Currency Master cache for the whole document.
            recalculateMprCalculations(mpr);
        }

        // Older MPR batches stored only the readable color name (for example
        // NATURAL). Recover the exact Product Color id from the generated lines
        // whenever possible so two four-field Product Colors with the same name
        // are not shown as both selected.
        backfillSelectionProductColorIds(mpr);

        decorateBomSourceState(mpr);
        return mpr;
    }

    /**
     * Preview retains saved rows, consolidates new duplicate source rows, and
     * returns the exact grouping that Generate will persist.
     */
    public MprDocument preview(String orderId, MprGenerateRequest request) {
        MprDocument candidate = build(orderId, request, false);
        Optional<MprDocument> current = mprRepository.findByOrderId(orderId);
        return current.map(existing -> {
            backfillMissingBomSourceFields(existing);
            backfillMissingMatInfoFields(existing, BuyerKeys.legacyDefault(existing.getBuyerKey()));
            return mergeForPreview(existing, candidate);
        }).orElseGet(() -> {
            orderLinesForDisplay(candidate);
            recalculateMprCalculations(candidate);
            return candidate;
        });
    }


    /**
     * Read-only preflight used by the UI before Preview/Create MPR.
     * It returns every dedicated Material -> Ship To mismatch in one pass so
     * users can fix all Product Colors without retrying generation repeatedly.
     */
    public MprValidationResult validateGeneration(String orderId, MprGenerateRequest request) {
        if (request == null || request.selections() == null || request.selections().isEmpty()) {
            throw new OrderBomMprValidationException("Select at least one submitted BOM");
        }

        SalesOrder order = orderService.get(orderId);
        String buyerKey = BuyerKeys.legacyDefault(order.getBuyerKey());
        requireLlBeanImplementation(buyerKey, "MPR generation");

        List<MatInfo> activeMatInfos = loadActiveMatInfos(buyerKey);
        Map<String, MatInfo> matByKey = buildMatInfoCache(activeMatInfos);
        Map<String, ShipTo> shipToById = buildShipToCache(buyerKey);
        Map<String, MaterialShipToMapping> dedicatedShipToByMaterialKey = materialShipToMappingRepository
                .findByBuyerKeyAndActiveTrue(buyerKey).stream()
                .filter(Objects::nonNull)
                .filter(item -> hasText(item.getMaterialKey()))
                .collect(Collectors.toMap(
                        MaterialShipToMapping::getMaterialKey,
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        LinkedHashSet<String> requestedBomIds = new LinkedHashSet<>();
        List<MprSelectionRequest> requestedSelections = new ArrayList<>();
        for (MprSelectionRequest selectionRequest : request.selections()) {
            if (selectionRequest == null || blank(selectionRequest.bomId())) {
                throw new OrderBomMprValidationException("BOM id is required for every selection");
            }
            if (!requestedBomIds.add(selectionRequest.bomId())) {
                throw new OrderBomMprValidationException("The same BOM can only be selected once");
            }
            requestedSelections.add(selectionRequest);
        }

        Map<String, BomDocument> selectedBomById = new LinkedHashMap<>();
        for (BomDocument bom : bomRepository.findAllById(requestedBomIds)) {
            if (bom != null && hasText(bom.getId())) selectedBomById.put(bom.getId(), bom);
        }
        List<BomDocument> selectedBoms = requestedBomIds.stream()
                .map(selectedBomById::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        lineStore.hydrateAllForMpr(selectedBoms);

        Map<String, ValidationIssueAccumulator> issueByKey = new LinkedHashMap<>();
        List<MprValidationIssue> masterDataWarnings = new ArrayList<>();
        Set<String> masterDataWarningKeys = new LinkedHashSet<>();

        for (MprSelectionRequest selectionRequest : requestedSelections) {
            BomDocument bom = selectedBomById.get(selectionRequest.bomId());
            if (bom == null) throw new OrderBomMprNotFoundException("Selected BOM not found");
            if (!buyerKey.equals(BuyerKeys.legacyDefault(bom.getBuyerKey()))) {
                throw new OrderBomMprValidationException("Selected BOM belongs to another Buyer");
            }
            if (!orderId.equals(bom.getOrderId())) {
                throw new OrderBomMprValidationException("Selected BOM does not belong to this order");
            }
            if (!"SUBMITTED".equalsIgnoreCase(bom.getStatus())) {
                throw new OrderBomMprValidationException("Only submitted BOM can be used to create MPR: " + bom.getBomNo());
            }

            List<BomProductColor> selectedProductColors = normalizeSelectionProductColors(selectionRequest.colors(), bom);
            if (selectedProductColors.isEmpty()) {
                throw new OrderBomMprValidationException("Select at least one Product Color for BOM " + bom.getBomNo());
            }

            Map<String, BomPacking> packingById = safeList(bom.getPackings()).stream()
                    .filter(Objects::nonNull)
                    .filter(item -> hasText(item.getId()))
                    .collect(Collectors.toMap(
                            BomPacking::getId,
                            item -> item,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
            List<BomPacking> selectedPackings = new ArrayList<>();
            for (String packingId : safeList(selectionRequest.packingIds())) {
                if (blank(packingId)) continue;
                BomPacking packing = packingById.get(packingId);
                if (packing == null) {
                    throw new OrderBomMprValidationException("Packing not found in BOM " + bom.getBomNo());
                }
                selectedPackings.add(packing);
            }

            for (BomProductColor productColor : selectedProductColors) {
                String productColorId = trim(productColor.getId());
                String colorName = trim(productColor.getColorName());
                List<String> selectedShipToIds = shipToIdsForColor(
                        selectionRequest, bom, productColor, shipToById
                );
                // Reuse the same quantity validation as real generation.
                shipToQuantitiesForColor(
                        selectionRequest, bom, productColor, selectedShipToIds, request.poQuantity()
                );
                String uiProductColorId = productColorId;

                List<BomLine> sourceLines = new ArrayList<>(safeList(bom.getCoreLines()));
                for (BomPacking packing : selectedPackings) sourceLines.addAll(safeList(packing.getLines()));

                for (BomLine source : sourceLines) {
                    if (!isPurchasableMaterialLine(source)) continue;

                    String description = bomMaterialDescription(source);
                    String materialColor = materialColorFor(source, colorName, productColorId);
                    MatInfo mat = findMatInfo(matByKey, source, materialColor);
                    String materialType = trim(source.getMaterialType());
                    String resolvedSapCode = firstNonBlank(source.getSapCode(), mat == null ? null : mat.getFlexId());
                    String resolvedMatUnit = firstNonBlank(
                            source.getConsumptionUnit(),
                            source.getCostingUnit(),
                            mat == null ? null : mat.getMatUnit()
                    );

                    if (mat == null) {
                        MprValidationIssue warning = buildBomMatInfoWarning(
                                bom, productColor, source, materialType, description, materialColor, resolvedMatUnit, activeMatInfos
                        );
                        String warningKey = matInfoWarningKey(warning);
                        if (warning != null && masterDataWarningKeys.add(warningKey)) {
                            masterDataWarnings.add(warning);
                        }
                    }
                    String materialMappingKey = MaterialShipToMappingKeys.build(
                            resolvedSapCode,
                            materialType,
                            description,
                            firstNonBlank(source.getPosition(), source.getPositionDescription(), source.getPositionDescriptionExtra()),
                            materialColor,
                            resolvedMatUnit
                    );
                    MaterialShipToMapping mapping = dedicatedShipToByMaterialKey.get(materialMappingKey);
                    if (mapping == null) continue;

                    String materialLabel = firstNonBlank(resolvedSapCode, description, materialType);
                    List<String> allowedIds = dedicatedShipToIds(mapping, shipToById, materialLabel);
                    boolean hasMatchingShipTo = selectedShipToIds.stream().anyMatch(allowedIds::contains);
                    if (hasMatchingShipTo) continue;

                    List<String> allowedCodes = shipToValues(allowedIds, shipToById, true);
                    List<String> allowedNames = shipToValues(allowedIds, shipToById, false);
                    String productColorDisplay = productColorLabel(productColor);
                    String issueKey = bom.getId() + "|" + uiProductColorId + "|" + normalize(productColorDisplay)
                            + "|" + String.join(",", allowedIds);
                    ValidationIssueAccumulator accumulator = issueByKey.computeIfAbsent(issueKey, key -> new ValidationIssueAccumulator(
                            bom.getId(),
                            trim(bom.getBomNo()),
                            trim(bom.getBomName()),
                            uiProductColorId,
                            productColorDisplay,
                            allowedIds,
                            allowedCodes,
                            allowedNames
                    ));
                    if (hasText(materialLabel)) accumulator.materials.add(materialLabel);
                }
            }
        }

        List<MprValidationIssue> issues = issueByKey.values().stream()
                .map(item -> {
                    String shipToLabel = dedicatedShipToLabel(item.allowedShipToIds, shipToById);
                    return new MprValidationIssue(
                            "NO_MATCHING_DEDICATED_SHIP_TO",
                            item.bomId,
                            item.bomNo,
                            item.bomName,
                            item.productColorId,
                            item.productColor,
                            firstListValue(item.allowedShipToIds),
                            firstListValue(item.allowedShipToCodes),
                            firstListValue(item.allowedShipToNames),
                            new ArrayList<>(item.allowedShipToIds),
                            new ArrayList<>(item.allowedShipToCodes),
                            new ArrayList<>(item.allowedShipToNames),
                            new ArrayList<>(item.materials),
                            "BOM " + item.bomNo + " · " + item.productColor
                                    + " → Select at least one Dedicated Ship To: " + shipToLabel,
                            "ERROR",
                            true,
                            null, null, null, null,
                            null, null, null, null,
                            List.of(),
                            null
                    );
                })
                .collect(Collectors.toCollection(ArrayList::new));

        issues.addAll(masterDataWarnings);
        boolean valid = issues.stream().noneMatch(MprValidationIssue::blocking);
        return new MprValidationResult(valid, issues);
    }

    /**
     * Validates the already-created MPR against current MAT Info before Excel export.
     * MAT Info gaps are warnings only: export remains allowed after user acknowledgement.
     */
    public MprValidationResult validateCurrentMasterData(String orderId) {
        SalesOrder order = orderService.get(orderId);
        String buyerKey = BuyerKeys.legacyDefault(order.getBuyerKey());
        MprDocument mpr = getByOrder(orderId);

        List<MatInfo> activeMatInfos = loadActiveMatInfos(buyerKey);
        Map<String, MatInfo> matByKey = buildMatInfoCache(activeMatInfos);
        List<MprValidationIssue> issues = new ArrayList<>();
        Set<String> warningKeys = new LinkedHashSet<>();

        for (MprLine line : safeList(mpr.getLines())) {
            if (line == null || blank(line.getMaterialType()) || blank(line.getMatUnit())) continue;
            boolean missingCommercialData = blank(line.getCurrency())
                    || blank(line.getShortNameSupplier())
                    || line.getMatPriceWithoutTax() == null
                    || line.getMatPriceWithoutTax().signum() == 0;
            if (!missingCommercialData) continue;

            String description = firstNonBlank(line.getMatFullDescription(), line.getPosition());
            if (blank(description)) continue;
            if (findMatInfo(matByKey, line) != null) continue;

            MprValidationIssue warning = buildMprLineMatInfoWarning(line, activeMatInfos);
            String warningKey = matInfoWarningKey(warning);
            if (warning != null && warningKeys.add(warningKey)) issues.add(warning);
        }
        return new MprValidationResult(true, issues);
    }

    /**
     * Create/Add MPR is cumulative. Exact source rows are never accepted twice.
     * Different BOM source rows are consolidated only when their business
     * material key (SAP/MTR/description/color/unit) and consumption values are
     * identical inside the same BOM/Product Color.
     */
    public MprDocument generate(String orderId, MprGenerateRequest request) {
        Optional<MprDocument> current = mprRepository.findByOrderId(orderId);
        current.ifPresent(this::normalizeLegacyStatus);
        current.ifPresent(this::requireEditable);

        MprDocument candidate = build(orderId, request, false);
        if (safeList(candidate.getLines()).isEmpty()) {
            throw new OrderBomMprValidationException(
                    "No MPR lines were created. Check BOM Core/Packing lines with a Consumption Unit."
            );
        }

        LocalDateTime now = LocalDateTime.now();

        if (current.isPresent()) {
            MprDocument entity = current.get();
            backfillMissingBomSourceFields(entity);
            backfillMissingMatInfoFields(entity, BuyerKeys.legacyDefault(entity.getBuyerKey()));
            LineMergeResult merged = mergeLineSets(entity.getLines(), candidate.getLines());
            if (!merged.changed()) {
                throw new OrderBomMprValidationException(
                        "All selected Product Color / Core / Packing source rows already exist in this MPR. "
                                + "Choose a different Product Color or Packing."
                );
            }

            List<MprSelection> newSelections = selectionsForAcceptedBatches(
                    candidate.getSelections(), merged.acceptedBatchIds()
            );
            List<MprSelection> allSelections = new ArrayList<>(safeList(entity.getSelections()));
            allSelections.addAll(newSelections);

            entity.setMprNo(firstNonBlank(entity.getMprNo(), candidate.getMprNo()));
            entity.setStatus(MprDocument.STATUS_IN_PROGRESS);
            entity.setPoQuantity(totalPoQuantity(allSelections));
            entity.setSampleQuantity(firstNonNull(entity.getSampleQuantity(), candidate.getSampleQuantity()));
            entity.setSelections(allSelections);
            entity.setLines(merged.lines());
            orderLinesForDisplay(entity);
            recalculateMprCalculations(entity);
            entity.setUpdatedAt(now);
            entity.setUpdatedBy(RequestActor.current());
            candidate = mprRepository.save(entity);
        } else {
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            candidate.setCreatedBy(RequestActor.current());
            candidate.setUpdatedBy(RequestActor.current());
            candidate.setStatus(MprDocument.STATUS_IN_PROGRESS);
            orderLinesForDisplay(candidate);
            recalculateMprCalculations(candidate);
            candidate = mprRepository.save(candidate);
        }

        decorateBomSourceState(candidate);
        orderService.markMprInProgress(orderId);
        return candidate;
    }

    public MprDocument confirmCompletion(String orderId) {
        MprDocument mpr = getByOrder(orderId);
        if (safeList(mpr.getLines()).isEmpty()) {
            throw new OrderBomMprValidationException("Cannot confirm an empty MPR");
        }

        if (!MprDocument.STATUS_COMPLETED.equalsIgnoreCase(mpr.getStatus())) {
            requireCurrentBomSources(mpr);
            mpr.setStatus(MprDocument.STATUS_COMPLETED);
            mpr.setUpdatedAt(LocalDateTime.now());
            mpr.setUpdatedBy(RequestActor.current());
            mpr = mprRepository.save(mpr);
        }

        // Keep Order and MPR completion status synchronized.
        decorateBomSourceState(mpr);
        orderService.markMprCompleted(orderId);
        return mpr;
    }

    public MprDocument reopen(String orderId, String reason) {
        MprDocument mpr = getByOrder(orderId);
        if (!MprDocument.STATUS_COMPLETED.equalsIgnoreCase(mpr.getStatus())) {
            throw new OrderBomMprValidationException("Only a completed MPR can be reopened");
        }

        String cleanReason = reason == null ? "" : reason.trim().replaceAll("\\s+", " ");
        if (cleanReason.isBlank()) {
            throw new OrderBomMprValidationException("Reopen reason is required");
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        MprReopenHistory history = new MprReopenHistory();
        history.setFromStatus(MprDocument.STATUS_COMPLETED);
        history.setToStatus(MprDocument.STATUS_IN_PROGRESS);
        history.setReason(cleanReason);
        history.setReopenedBy(actor);
        history.setReopenedAt(now);

        List<MprReopenHistory> reopenHistory = new ArrayList<>(safeList(mpr.getReopenHistory()));
        reopenHistory.add(history);
        mpr.setReopenHistory(reopenHistory);
        mpr.setStatus(MprDocument.STATUS_IN_PROGRESS);
        mpr.setUpdatedAt(now);
        mpr.setUpdatedBy(actor);
        MprDocument saved = mprRepository.save(mpr);
        decorateBomSourceState(saved);
        orderService.markMprInProgress(orderId);
        return saved;
    }

    private MprDocument mergeForPreview(MprDocument existing, MprDocument candidate) {
        LineMergeResult merged = mergeLineSets(existing.getLines(), candidate.getLines());
        List<MprSelection> newSelections = selectionsForAcceptedBatches(
                candidate.getSelections(), merged.acceptedBatchIds()
        );

        List<MprSelection> selections = new ArrayList<>(safeList(existing.getSelections()));
        selections.addAll(newSelections);

        candidate.setMprNo(firstNonBlank(existing.getMprNo(), candidate.getMprNo()));
        candidate.setStatus(existing.getStatus());
        candidate.setPoQuantity(totalPoQuantity(selections));
        candidate.setSampleQuantity(firstNonNull(existing.getSampleQuantity(), candidate.getSampleQuantity()));
        candidate.setSelections(selections);
        candidate.setLines(merged.lines());
        orderLinesForDisplay(candidate);
        recalculateMprCalculations(candidate);
        return candidate;
    }

    /**
     * Combines physical source rows into a compact MPR result while preserving
     * every source in MprSourceTrace. This is also used when a later Packing is
     * added to a BOM that already exists in the MPR.
     */
    private LineMergeResult mergeLineSets(List<MprLine> existing, List<MprLine> incoming) {
        // Existing saved rows may come from an older version that allowed
        // several identical MPR rows. Consolidate them first, so 2/3/100
        // identical rows always become one survivor before new rows are added.
        List<MprLine> result = consolidateFinalLines(existing);

        Map<String, MprLine> sourceOwner = new LinkedHashMap<>();
        Map<String, MprLine> duplicateOwner = new LinkedHashMap<>();
        for (MprLine line : result) {
            ensureSourceTraces(line);
            refreshDuplicateMetadata(line);
            for (MprSourceTrace trace : safeList(line.getSourceTraces())) {
                sourceOwner.putIfAbsent(sourceKey(trace, line), line);
            }
            duplicateOwner.putIfAbsent(mprDuplicateKey(line), line);
        }

        Set<String> acceptedBatchIds = new LinkedHashSet<>();
        int addedLineCount = 0;
        int mergedSourceCount = 0;

        for (MprLine sourceLine : safeList(incoming)) {
            if (sourceLine == null) continue;
            MprLine incomingLine = copyMprLine(sourceLine);
            ensureSourceTraces(incomingLine);

            List<MprSourceTrace> newTraces = new ArrayList<>();
            for (MprSourceTrace trace : safeList(incomingLine.getSourceTraces())) {
                String sourceKey = sourceKey(trace, incomingLine);
                if (!sourceOwner.containsKey(sourceKey)) {
                    newTraces.add(copySourceTrace(trace));
                }
            }
            if (newTraces.isEmpty()) continue;

            String duplicateKey = mprDuplicateKey(incomingLine);
            MprLine survivor = duplicateOwner.get(duplicateKey);
            if (survivor == null) {
                incomingLine.setSourceTraces(newTraces);
                // Some traces may already belong to a previously saved MPR row.
                // Rebuild PO Qty / Ship To from only the newly accepted traces so
                // a partially accepted pre-consolidated row can never overcount.
                refreshMergedQuantityAndShipTo(incomingLine);
                applyPrimaryTrace(incomingLine, newTraces.get(0));
                refreshDuplicateMetadata(incomingLine);
                result.add(incomingLine);
                survivor = incomingLine;
                duplicateOwner.put(duplicateKey, survivor);
                addedLineCount++;
            } else {
                List<MprSourceTrace> mergedTraces = new ArrayList<>(safeList(survivor.getSourceTraces()));
                mergedTraces.addAll(newTraces);
                survivor.setSourceTraces(uniqueSourceTraces(mergedTraces, survivor));
                refreshMergedQuantityAndShipTo(survivor);
                refreshDuplicateMetadata(survivor);
                mergedSourceCount += newTraces.size();
            }

            for (MprSourceTrace trace : newTraces) {
                sourceOwner.put(sourceKey(trace, incomingLine), survivor);
                if (hasText(trace.getGenerationBatchId())) {
                    acceptedBatchIds.add(trace.getGenerationBatchId());
                }
            }
        }

        return new LineMergeResult(
                consolidateFinalLines(result),
                acceptedBatchIds,
                addedLineCount,
                mergedSourceCount,
                addedLineCount > 0 || mergedSourceCount > 0
        );
    }

    /**
     * Produces the only row set that may be shown or exported.
     *
     * For every duplicate key, the first row is retained as the survivor and
     * all physical source traces from the other rows are attached to it. The
     * result is therefore deterministic:
     *
     *   2 identical rows   -> keep 1, remove 1
     *   3 identical rows   -> keep 1, remove 2
     *   100 identical rows -> keep 1, remove 99
     */
    private List<MprLine> consolidateFinalLines(List<MprLine> lines) {
        Map<String, MprLine> survivorByDuplicateKey = new LinkedHashMap<>();

        for (MprLine source : safeList(lines)) {
            if (source == null) continue;

            MprLine candidate = copyMprLine(source);
            ensureSourceTraces(candidate);
            String duplicateKey = mprDuplicateKey(candidate);
            MprLine survivor = survivorByDuplicateKey.get(duplicateKey);

            if (survivor == null) {
                // Also normalize rows that were already consolidated and saved by
                // an older build where duplicate trace PO Qty values were summed.
                refreshMergedQuantityAndShipTo(candidate);
                refreshDuplicateMetadata(candidate);
                survivorByDuplicateKey.put(duplicateKey, candidate);
                continue;
            }

            List<MprSourceTrace> traces = new ArrayList<>(safeList(survivor.getSourceTraces()));
            traces.addAll(safeList(candidate.getSourceTraces()));
            survivor.setSourceTraces(uniqueSourceTraces(traces, survivor));
            refreshMergedQuantityAndShipTo(survivor);
            applyPrimaryTrace(survivor, survivor.getSourceTraces().get(0));
            refreshDuplicateMetadata(survivor);
        }

        return new ArrayList<>(survivorByDuplicateKey.values());
    }

    private void addShipToLabels(Set<String> labels, String value) {
        if (labels == null || blank(value)) return;
        for (String item : value.split("\\s*\\+\\s*")) {
            String clean = trim(item);
            if (!clean.isEmpty()) labels.add(clean);
        }
    }

    private List<MprSelection> selectionsForAcceptedBatches(
            List<MprSelection> selections,
            Set<String> acceptedBatchIds
    ) {
        List<MprSelection> result = new ArrayList<>();
        for (MprSelection selection : safeList(selections)) {
            if (selection != null && acceptedBatchIds.contains(selection.getBatchId())) {
                result.add(selection);
            }
        }
        return result;
    }

    private MprLine copyMprLine(MprLine source) {
        MprLine target = new MprLine();
        BeanUtils.copyProperties(source, target);
        target.setShipToIds(new ArrayList<>(safeList(source.getShipToIds())));
        target.setBomReviews(new ArrayList<>(safeList(source.getBomReviews())));
        target.setSourceTraces(safeList(source.getSourceTraces()).stream()
                .filter(Objects::nonNull)
                .map(this::copySourceTrace)
                .collect(Collectors.toCollection(ArrayList::new)));
        return target;
    }

    private MprSourceTrace copySourceTrace(MprSourceTrace source) {
        MprSourceTrace target = new MprSourceTrace();
        if (source != null) {
            BeanUtils.copyProperties(source, target);
            target.setShipToIds(new ArrayList<>(safeList(source.getShipToIds())));
        }
        return target;
    }

    private void ensureSourceTraces(MprLine line) {
        if (line == null) return;
        List<MprSourceTrace> traces = uniqueSourceTraces(line.getSourceTraces(), line);
        if (traces.isEmpty()) traces.add(traceFromLegacyLine(line));
        normalizeTraceBusinessSnapshots(line, traces);
        line.setSourceTraces(traces);
        applyPrimaryTrace(line, traces.get(0));
    }

    private List<MprSourceTrace> uniqueSourceTraces(List<MprSourceTrace> source, MprLine fallback) {
        Map<String, MprSourceTrace> unique = new LinkedHashMap<>();
        for (MprSourceTrace trace : safeList(source)) {
            if (trace == null) continue;
            unique.putIfAbsent(sourceKey(trace, fallback), copySourceTrace(trace));
        }
        return new ArrayList<>(unique.values());
    }

    private MprSourceTrace traceFromLegacyLine(MprLine line) {
        MprSourceTrace trace = new MprSourceTrace();
        trace.setGenerationBatchId(line == null ? null : line.getGenerationBatchId());
        trace.setSourceBomDedupKey(line == null ? null : line.getSourceBomDedupKey());
        trace.setSourceLineId(line == null ? null : line.getSourceLineId());
        trace.setSourceRowNumber(line == null ? null : line.getSourceRowNumber());
        trace.setBomLineNo(line == null ? null : line.getBomLineNo());
        trace.setPackingId(line == null ? null : line.getPackingId());
        trace.setPackingName(line == null ? null : line.getPackingName());
        trace.setSection(line == null ? null : line.getSection());
        trace.setSourceLabel(sourceLabel(line == null ? null : line.getSection(),
                line == null ? null : line.getPackingName()));
        trace.setPoQuantity(line == null ? null : line.getPoQuantity());
        trace.setShipToIds(line == null ? new ArrayList<>() : new ArrayList<>(safeList(line.getShipToIds())));
        trace.setShipTo(line == null ? null : line.getShipTo());
        return trace;
    }

    private String sourceKey(MprSourceTrace trace, MprLine fallback) {
        if (trace != null && hasText(trace.getSourceBomDedupKey())) {
            return trace.getSourceBomDedupKey();
        }
        String color = firstNonBlank(
                fallback == null ? null : fallback.getProductColorId(),
                fallback == null ? null : fallback.getStyleColor()
        );
        String sourceLineId = trace == null
                ? (fallback == null ? null : fallback.getSourceLineId())
                : trace.getSourceLineId();
        String sourceRow = String.valueOf(trace == null
                ? (fallback == null ? null : fallback.getSourceRowNumber())
                : trace.getSourceRowNumber());
        return normalize(fallback == null ? null : fallback.getBomId())
                + "|" + normalize(color)
                + "|" + normalize(trace == null ? (fallback == null ? null : fallback.getSection()) : trace.getSection())
                + "|" + normalize(trace == null ? (fallback == null ? null : fallback.getPackingId()) : trace.getPackingId())
                + "|" + normalize(sourceLineId)
                + "|ROW:" + normalize(sourceRow)
                + "|" + (hasText(sourceLineId) ? "" : mprDuplicateKey(fallback));
    }

    private String sourceAnchorKey(MprSourceTrace trace, MprLine fallback) {
        String color = firstNonBlank(
                fallback == null ? null : fallback.getProductColorId(),
                fallback == null ? null : fallback.getStyleColor()
        );
        Integer row = trace == null
                ? (fallback == null ? null : fallback.getSourceRowNumber())
                : trace.getSourceRowNumber();
        String packing = firstNonBlank(
                trace == null ? null : trace.getPackingName(),
                fallback == null ? null : fallback.getPackingName(),
                trace == null ? null : trace.getPackingId(),
                fallback == null ? null : fallback.getPackingId()
        );
        return "ANCHOR|" + normalize(fallback == null ? null : fallback.getBomId())
                + "|" + normalize(color)
                + "|" + normalize(trace == null ? (fallback == null ? null : fallback.getSection()) : trace.getSection())
                + "|" + normalize(packing)
                + "|ROW:" + (row == null ? "" : row);
    }

    private String mprDuplicateKey(MprLine line) {
        String color = firstNonBlank(line == null ? null : line.getProductColorId(),
                line == null ? null : line.getStyleColor());
        return normalize(line == null ? null : line.getBomId())
                + "|" + normalize(color)
                + "|" + mprMaterialIdentityKey(line)
                + "|" + decimalKey(line == null ? null : line.getSourceDetailConsumption())
                + "|" + decimalKey(line == null ? null : line.getYield());
    }

    /**
     * Business identity of one material. Calculations and duplicate handling
     * must never rely on Excel row positions because the source and exported
     * MPR can be ordered differently.
     */
    private String mprMaterialIdentityKey(MprLine line) {
        if (line == null) return "";
        return MaterialShipToMappingKeys.build(
                line.getSapCode(),
                line.getMaterialType(),
                line.getMatFullDescription(),
                line.getPosition(),
                line.getMatColor(),
                line.getMatUnit()
        );
    }

    private void refreshDuplicateMetadata(MprLine line) {
        if (line == null) return;
        ensureSourceTracesWithoutRefresh(line);
        int removed = Math.max(0, safeList(line.getSourceTraces()).size() - 1);
        line.setRemovedDuplicateCount(removed);
        line.setDuplicateHighlighted(removed > 0);
        if (removed == 0) {
            line.setDuplicateNote(null);
            return;
        }

        String sources = safeList(line.getSourceTraces()).stream()
                .map(MprSourceTrace::getSourceLabel)
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(", "));
        line.setDuplicateNote(
                "Merged/Đã gộp " + removed
                        + " duplicate row(s) because the business material key + CONS. + NET are identical; "
                        + "PO Qty was kept once (not summed again)."
                        + (sources.isBlank() ? "" : " Sources/Nguồn: " + sources)
        );
    }

    private void ensureSourceTracesWithoutRefresh(MprLine line) {
        if (line == null) return;
        List<MprSourceTrace> traces = uniqueSourceTraces(line.getSourceTraces(), line);
        if (traces.isEmpty()) traces.add(traceFromLegacyLine(line));
        normalizeTraceBusinessSnapshots(line, traces);
        line.setSourceTraces(traces);
        applyPrimaryTrace(line, traces.get(0));
    }

    private void normalizeTraceBusinessSnapshots(MprLine line, List<MprSourceTrace> traces) {
        if (line == null || traces == null || traces.isEmpty()) return;
        boolean hasQuantitySnapshot = traces.stream().filter(Objects::nonNull).anyMatch(trace -> trace.getPoQuantity() != null);
        if (!hasQuantitySnapshot) {
            // PO Qty on a generated material row is already the total quantity for
            // the selected BOM + Product Color + Ship To scope. Duplicate BOM
            // source rows repeat that same quantity; they are not additive
            // contributions. Keep the same snapshot on every trace so removing
            // one duplicate source later does not change the visible PO Qty.
            BigDecimal snapshot = safe(line.getPoQuantity());
            for (MprSourceTrace trace : traces) {
                if (trace != null) trace.setPoQuantity(snapshot);
            }
        }
        for (MprSourceTrace trace : traces) {
            if (trace == null) continue;
            if ((trace.getShipToIds() == null || trace.getShipToIds().isEmpty()) && !safeList(line.getShipToIds()).isEmpty()) {
                trace.setShipToIds(new ArrayList<>(safeList(line.getShipToIds())));
            }
            if (!hasText(trace.getShipTo()) && hasText(line.getShipTo())) trace.setShipTo(line.getShipTo());
        }
    }

    private void refreshMergedQuantityAndShipTo(MprLine line) {
        if (line == null) return;
        ensureSourceTracesWithoutRefresh(line);

        // IMPORTANT BUSINESS RULE:
        // appendForColor() already calculates PO Qty as the TOTAL selected Ship To
        // quantity for this BOM + Product Color before one row is created for each
        // Core/Packing material source. Therefore two duplicate material rows with
        // PO Qty 400 represent the same 400 pcs order scope, not 400 + 400.
        // Duplicate consolidation removes repeated material rows; it must never
        // perform a second PO Qty aggregation.
        BigDecimal effectivePoQuantity = safeList(line.getSourceTraces()).stream()
                .filter(Objects::nonNull)
                .map(MprSourceTrace::getPoQuantity)
                .filter(Objects::nonNull)
                .findFirst()
                .map(this::safe)
                .orElseGet(() -> safe(line.getPoQuantity()));
        line.setPoQuantity(effectivePoQuantity);

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (MprSourceTrace trace : safeList(line.getSourceTraces())) {
            if (trace == null) continue;
            ids.addAll(safeList(trace.getShipToIds()));
            addShipToLabels(labels, trace.getShipTo());
        }
        line.setShipToIds(new ArrayList<>(ids));
        line.setShipTo(String.join(" + ", labels));
    }

    /**
     * Keeps source-trace PO Qty snapshots consistent when Sales manually edits a
     * merged MPR row. A source trace is provenance for a repeated BOM material row,
     * not a partial PO Qty contribution, so every duplicate trace keeps the same
     * visible line PO Qty.
     */
    private void redistributeTraceQuantityToLineTotal(MprLine line, BigDecimal requestedTotal) {
        if (line == null) return;
        ensureSourceTracesWithoutRefresh(line);
        List<MprSourceTrace> traces = safeList(line.getSourceTraces()).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        BigDecimal newTotal = safe(requestedTotal);
        for (MprSourceTrace trace : traces) {
            trace.setPoQuantity(newTotal);
        }
        line.setSourceTraces(traces);
        line.setPoQuantity(newTotal);
    }

    private void applyPrimaryTrace(MprLine line, MprSourceTrace trace) {
        if (line == null || trace == null) return;
        line.setGenerationBatchId(trace.getGenerationBatchId());
        line.setSourceBomDedupKey(trace.getSourceBomDedupKey());
        line.setSourceLineId(trace.getSourceLineId());
        line.setSourceRowNumber(trace.getSourceRowNumber());
        line.setPackingId(trace.getPackingId());
        line.setPackingName(trace.getPackingName());
        line.setSection(trace.getSection());
    }

    private boolean lineHasBatch(MprLine line, String batchId) {
        if (line == null || blank(batchId)) return false;
        if (batchId.equals(line.getGenerationBatchId())) return true;
        return safeList(line.getSourceTraces()).stream()
                .filter(Objects::nonNull)
                .anyMatch(trace -> batchId.equals(trace.getGenerationBatchId()));
    }

    private String sourceLabel(String section, String packingName) {
        return "PACKING".equalsIgnoreCase(trim(section))
                ? firstNonBlank(packingName, "Packing")
                : "Core BOM (No Packing)";
    }

    /**
     * Stable identity for one physical BOM source row and selected Product Color.
     * Packing remains part of this exact-source key so Core/US/JAPAN rows can be
     * traced and removed independently even when they are consolidated later.
     */
    private String bomSourceSelectionKey(
            BomDocument bom,
            BomPacking packing,
            BomLine source,
            String selectedColor,
            String productColorId
    ) {
        String sourceId = source == null ? null : source.getId();
        if (!hasText(sourceId)) {
            sourceId = normalize(source == null || source.getMaterialGroupNo() == null
                            ? null
                            : String.valueOf(source.getMaterialGroupNo()))
                    + "|" + normalize(source == null ? null : source.getDetailNo())
                    + "|" + normalize(source == null ? null : source.getMaterialType())
                    + "|" + normalize(source == null ? null : source.getSapCode())
                    + "|" + normalize(source == null ? null : source.getPosition())
                    + "|" + normalize(source == null ? null : source.getPositionDescription())
                    + "|" + decimalKey(source == null ? null : source.getDetailConsumption())
                    + "|" + decimalKey(source == null ? null : source.getConsumptionNet())
                    + "|" + normalize(source == null ? null : source.getConsumptionUnit());
        }
        return normalize(bom == null ? null : bom.getId())
                + "|" + normalize(firstNonBlank(productColorId, selectedColor))
                + "|" + (packing == null ? "core" : "packing")
                + "|" + normalize(packing == null ? null : packing.getId())
                + "|" + normalize(sourceId);
    }

    private String decimalKey(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private record LineMergeResult(
            List<MprLine> lines,
            Set<String> acceptedBatchIds,
            int addedLineCount,
            int mergedSourceCount,
            boolean changed
    ) {}

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
        requireEditable(mpr);
        mprRepository.delete(mpr);
        orderService.markBomSubmitted(orderId);
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
        requireEditable(mpr);
        safeList(mpr.getSelections()).stream()
                .filter(item -> item != null && batchId.equals(item.getBatchId()))
                .findFirst()
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR generation batch not found"));

        int removedSourceCount = 0;
        List<MprLine> remainingLines = new ArrayList<>();
        for (MprLine original : safeList(mpr.getLines())) {
            if (original == null) continue;
            MprLine line = copyMprLine(original);
            ensureSourceTraces(line);

            List<MprSourceTrace> traces = new ArrayList<>(safeList(line.getSourceTraces()));
            List<MprSourceTrace> remainingTraces = traces.stream()
                    .filter(trace -> trace == null || !batchId.equals(trace.getGenerationBatchId()))
                    .map(this::copySourceTrace)
                    .collect(Collectors.toCollection(ArrayList::new));
            int removedFromLine = traces.size() - remainingTraces.size();
            removedSourceCount += removedFromLine;

            if (removedFromLine == 0) {
                remainingLines.add(line);
            } else if (!remainingTraces.isEmpty()) {
                line.setSourceTraces(remainingTraces);
                MprSourceTrace primaryTrace = remainingTraces.get(0);
                applyPrimaryTrace(line, primaryTrace);
                // The previous representative source was removed. Keep BOM No.
                // exactly as stored on the new surviving physical BOM row.
                if (primaryTrace.getBomLineNo() != null) {
                    line.setBomLineNo(primaryTrace.getBomLineNo());
                }
                refreshDuplicateMetadata(line);
                remainingLines.add(line);
            }
        }

        if (removedSourceCount == 0) {
            throw new OrderBomMprNotFoundException(
                    "No saved MPR source rows were found for this generation batch"
            );
        }

        List<MprSelection> remainingSelections = safeList(mpr.getSelections()).stream()
                .filter(item -> item == null || !batchId.equals(item.getBatchId()))
                .collect(Collectors.toCollection(ArrayList::new));

        if (remainingLines.isEmpty()) {
            mprRepository.delete(mpr);
            orderService.markBomSubmitted(orderId);
            return new MprBatchDeleteResult(true, removedSourceCount, 0, null);
        }

        mpr.setLines(consolidateFinalLines(remainingLines));
        mpr.setSelections(remainingSelections);
        mpr.setPoQuantity(totalPoQuantity(remainingSelections));
        orderLinesForDisplay(mpr);
        recalculateMprCalculations(mpr);
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());

        MprDocument saved = mprRepository.save(mpr);
        decorateBomSourceState(saved);
        return new MprBatchDeleteResult(
                false,
                removedSourceCount,
                safeList(saved.getLines()).size(),
                saved
        );
    }

    /**
     * Edits one saved MPR generation batch after creation. Product Color,
     * Packing, PO Qty and Ship To are rebuilt from the selected BOM while
     * unchanged physical source rows retain their saved MPR edits.
     */
    public MprDocument updateBatch(String orderId, String batchId, MprBatchUpdateRequest request) {
        if (request == null) {
            throw new OrderBomMprValidationException("MPR batch data is required");
        }
        MprDocument mpr = getByOrder(orderId);
        requireEditable(mpr);
        rebuildBatchFromCurrentBom(orderId, mpr, batchId, request, false);
        return saveRefreshedMpr(mpr);
    }

    /**
     * Rebuilds every stale generation batch that uses one changed BOM. The
     * saved Product Color / Packing / Ship To / PO Qty selection is reused.
     * BOM-owned fields come from the current BOM; MPR-owned Sales/stock/vendor
     * inputs are preserved for source rows that can still be matched.
     */
    public MprDocument refreshBomSource(String orderId, String bomId) {
        if (blank(bomId)) {
            throw new OrderBomMprValidationException("BOM id is required");
        }
        return refreshChangedBomSources(orderId, bomId);
    }

    /** Refreshes every BOM source currently marked stale in this MPR. */
    public MprDocument refreshAllBomSources(String orderId) {
        return refreshChangedBomSources(orderId, null);
    }

    private MprDocument refreshChangedBomSources(String orderId, String onlyBomId) {
        MprDocument mpr = getByOrder(orderId);
        requireEditable(mpr);
        decorateBomSourceState(mpr);

        List<String> staleBatchIds = safeList(mpr.getSelections()).stream()
                .filter(Objects::nonNull)
                .filter(MprSelection::isBomSourceChanged)
                .filter(item -> !item.isBomSourceMissing())
                .filter(item -> blank(onlyBomId) || Objects.equals(onlyBomId, item.getBomId()))
                .map(MprSelection::getBatchId)
                .filter(this::hasText)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));

        if (staleBatchIds.isEmpty()) {
            return mpr;
        }

        for (String batchId : staleBatchIds) {
            rebuildBatchFromCurrentBom(orderId, mpr, batchId, null, true);
        }
        return saveRefreshedMpr(mpr);
    }

    private void rebuildBatchFromCurrentBom(
            String orderId,
            MprDocument mpr,
            String batchId,
            MprBatchUpdateRequest request,
            boolean sourceRefresh
    ) {
        if (blank(batchId)) {
            throw new OrderBomMprValidationException("MPR generation batch id is required");
        }

        MprSelection currentBatch = safeList(mpr.getSelections()).stream()
                .filter(item -> item != null && batchId.equals(item.getBatchId()))
                .findFirst()
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR generation batch not found"));

        List<String> colors = request == null || request.colors() == null
                ? new ArrayList<>(safeList(currentBatch.getColors()))
                : new ArrayList<>(safeList(request.colors()));
        List<String> packingIds = request == null || request.packingIds() == null
                ? new ArrayList<>(safeList(currentBatch.getPackingIds()))
                : new ArrayList<>(safeList(request.packingIds()));
        Map<String, BigDecimal> poQtyByColor = request == null || request.poQtyByColor() == null
                ? new LinkedHashMap<>(currentBatch.getPoQtyByColor() == null ? Map.of() : currentBatch.getPoQtyByColor())
                : new LinkedHashMap<>(request.poQtyByColor());
        Map<String, List<String>> shipToIdsByColor = request == null || request.shipToIdsByColor() == null
                ? copyStringListMap(currentBatch.getShipToIdsByColor())
                : copyStringListMap(request.shipToIdsByColor());
        Map<String, Map<String, BigDecimal>> shipToQtyByColor = request == null || request.shipToQtyByColor() == null
                ? copyNestedQuantityMap(currentBatch.getShipToQtyByColor())
                : copyNestedQuantityMap(request.shipToQtyByColor());

        if (colors.isEmpty()) {
            throw new OrderBomMprValidationException("Select at least one Product Color");
        }

        MprSelectionRequest replacementRequest = new MprSelectionRequest(
                currentBatch.getBomId(),
                colors,
                packingIds,
                poQtyByColor,
                shipToIdsByColor,
                shipToQtyByColor
        );
        MprGenerateRequest regenerateRequest = new MprGenerateRequest(
                mpr.getMprNo(),
                mpr.getPoQuantity(),
                safe(mpr.getSampleQuantity()),
                List.of(replacementRequest)
        );

        MprDocument regenerated = build(orderId, regenerateRequest, false);
        MprSelection replacementBatch = safeList(regenerated.getSelections()).stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new OrderBomMprValidationException("Unable to rebuild the selected MPR batch"));

        String temporaryBatchId = replacementBatch.getBatchId();
        replacementBatch.setBatchId(batchId);
        replacementBatch.setCreatedAt(currentBatch.getCreatedAt());
        replacementBatch.setCreatedBy(currentBatch.getCreatedBy());
        remapGenerationBatch(regenerated.getLines(), temporaryBatchId, batchId);

        Map<String, MprLine> editableSnapshotBySource = editableSnapshotByBatchSource(
                mpr.getLines(), batchId
        );
        if (sourceRefresh) {
            preserveMprOwnedLineEdits(regenerated.getLines(), editableSnapshotBySource);
        } else {
            preserveExistingLineEdits(regenerated.getLines(), editableSnapshotBySource);
        }

        // Remove the old batch traces first, then merge the regenerated batch
        // into the remaining MPR. Other saved batches are never regenerated.
        List<MprLine> remainingLines = removeBatchSources(mpr.getLines(), batchId);
        LineMergeResult merged = mergeLineSets(remainingLines, regenerated.getLines());
        if (!merged.acceptedBatchIds().contains(batchId)) {
            throw new OrderBomMprValidationException(
                    sourceRefresh
                            ? "The refreshed BOM sources are already fully represented by another MPR batch"
                            : "The edited Product Color / Packing selection is already fully represented by another MPR batch"
            );
        }

        List<MprSelection> selections = new ArrayList<>();
        boolean replaced = false;
        for (MprSelection selection : safeList(mpr.getSelections())) {
            if (selection != null && batchId.equals(selection.getBatchId())) {
                selections.add(replacementBatch);
                replaced = true;
            } else {
                selections.add(selection);
            }
        }
        if (!replaced) selections.add(replacementBatch);

        mpr.setSelections(selections);
        mpr.setLines(merged.lines());
        mpr.setPoQuantity(totalPoQuantity(selections));
        orderLinesForDisplay(mpr);
        recalculateMprCalculations(mpr);
    }

    private MprDocument saveRefreshedMpr(MprDocument mpr) {
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());
        MprDocument saved = mprRepository.save(mpr);
        decorateBomSourceState(saved);
        return saved;
    }

    private Map<String, List<String>> copyStringListMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (source == null) return result;
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(safeList(entry.getValue())));
        }
        return result;
    }

    private Map<String, Map<String, BigDecimal>> copyNestedQuantityMap(Map<String, Map<String, BigDecimal>> source) {
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        if (source == null) return result;
        for (Map.Entry<String, Map<String, BigDecimal>> entry : source.entrySet()) {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue() == null ? Map.of() : entry.getValue()));
        }
        return result;
    }

    private void remapGenerationBatch(List<MprLine> lines, String oldBatchId, String newBatchId) {
        for (MprLine line : safeList(lines)) {
            if (line == null) continue;
            ensureSourceTracesWithoutRefresh(line);
            for (MprSourceTrace trace : safeList(line.getSourceTraces())) {
                if (trace != null && Objects.equals(oldBatchId, trace.getGenerationBatchId())) {
                    trace.setGenerationBatchId(newBatchId);
                }
            }
            if (Objects.equals(oldBatchId, line.getGenerationBatchId())) {
                line.setGenerationBatchId(newBatchId);
            }
            if (!safeList(line.getSourceTraces()).isEmpty()) {
                applyPrimaryTrace(line, line.getSourceTraces().get(0));
            }
            refreshDuplicateMetadata(line);
        }
    }

    private Map<String, MprLine> editableSnapshotByBatchSource(List<MprLine> lines, String batchId) {
        Map<String, MprLine> result = new LinkedHashMap<>();
        for (MprLine original : safeList(lines)) {
            if (original == null) continue;
            MprLine line = copyMprLine(original);
            ensureSourceTracesWithoutRefresh(line);
            for (MprSourceTrace trace : safeList(line.getSourceTraces())) {
                if (trace != null && batchId.equals(trace.getGenerationBatchId())) {
                    result.putIfAbsent(sourceKey(trace, line), line);
                    result.putIfAbsent(sourceAnchorKey(trace, line), line);
                }
            }
        }
        return result;
    }

    private void preserveExistingLineEdits(
            List<MprLine> regeneratedLines,
            Map<String, MprLine> editableSnapshotBySource
    ) {
        for (MprLine target : safeList(regeneratedLines)) {
            if (target == null) continue;
            ensureSourceTracesWithoutRefresh(target);
            MprLine saved = null;
            for (MprSourceTrace trace : safeList(target.getSourceTraces())) {
                saved = editableSnapshotBySource.get(sourceKey(trace, target));
                if (saved == null) saved = editableSnapshotBySource.get(sourceAnchorKey(trace, target));
                if (saved != null) break;
            }
            if (saved == null) continue;
            copyEditableMprValues(saved, target);
        }
    }

    private void preserveMprOwnedLineEdits(
            List<MprLine> regeneratedLines,
            Map<String, MprLine> editableSnapshotBySource
    ) {
        for (MprLine target : safeList(regeneratedLines)) {
            if (target == null) continue;
            ensureSourceTracesWithoutRefresh(target);
            MprLine saved = null;
            for (MprSourceTrace trace : safeList(target.getSourceTraces())) {
                saved = editableSnapshotBySource.get(sourceKey(trace, target));
                if (saved == null) saved = editableSnapshotBySource.get(sourceAnchorKey(trace, target));
                if (saved != null) break;
            }
            if (saved == null) continue;
            copyMprOwnedValues(saved, target);
        }
    }

    /**
     * Values owned by Sales/MPR are retained during an Update from BOM.
     * BOM-owned identity/consumption fields intentionally remain from the newly
     * regenerated source so users really receive the BOM change.
     */
    private void copyMprOwnedValues(MprLine source, MprLine target) {
        target.setSalesComment(source.getSalesComment());
        target.setSampleQuantity(defaultZero(source.getSampleQuantity()));
        target.setMcdStock(defaultZero(source.getMcdStock()));
        target.setCmcdStock(defaultZero(source.getCmcdStock()));
        target.setNonSapStockQuantity(defaultZero(source.getNonSapStockQuantity()));
        target.setCurrency(source.getCurrency());
        target.setMatPriceWithoutTax(defaultZero(source.getMatPriceWithoutTax()));
        target.setShortNameSupplier(source.getShortNameSupplier());
        target.setVendorCode(source.getVendorCode());
        target.setVendorName(source.getVendorName());
        target.setMatCharger(source.getMatCharger());
        target.setMatDueDate(source.getMatDueDate());
        target.setBomReviews(new ArrayList<>(safeList(source.getBomReviews())));
    }

    private void copyEditableMprValues(MprLine source, MprLine target) {
        target.setStyleDescription(source.getStyleDescription());
        // Product Color belongs to the edited selection and must come from the
        // newly selected BOM color, not from the previous saved line.
        target.setSalesComment(source.getSalesComment());
        target.setSapCode(source.getSapCode());
        target.setBomLineNo(source.getBomLineNo());
        target.setMaterialType(source.getMaterialType());
        target.setPosition(source.getPosition());
        target.setMatFullDescription(source.getMatFullDescription());
        target.setMatColor(source.getMatColor());
        target.setMatUnit(source.getMatUnit());
        target.setYield(defaultZero(source.getYield()));
        target.setSampleQuantity(defaultZero(source.getSampleQuantity()));
        target.setMcdStock(defaultZero(source.getMcdStock()));
        target.setCmcdStock(defaultZero(source.getCmcdStock()));
        target.setNonSapStockQuantity(defaultZero(source.getNonSapStockQuantity()));
        target.setCurrency(source.getCurrency());
        target.setMatPriceWithoutTax(defaultZero(source.getMatPriceWithoutTax()));
        target.setShortNameSupplier(source.getShortNameSupplier());
        target.setVendorCode(source.getVendorCode());
        target.setVendorName(source.getVendorName());
        target.setMatCharger(source.getMatCharger());
        target.setMatDueDate(source.getMatDueDate());
        target.setSourceRemark(source.getSourceRemark());
        target.setBomReviews(new ArrayList<>(safeList(source.getBomReviews())));
    }

    private List<MprLine> removeBatchSources(List<MprLine> lines, String batchId) {
        List<MprLine> remainingLines = new ArrayList<>();
        for (MprLine original : safeList(lines)) {
            if (original == null) continue;
            MprLine line = copyMprLine(original);
            ensureSourceTracesWithoutRefresh(line);
            List<MprSourceTrace> remainingTraces = safeList(line.getSourceTraces()).stream()
                    .filter(trace -> trace == null || !batchId.equals(trace.getGenerationBatchId()))
                    .map(this::copySourceTrace)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (remainingTraces.isEmpty()) continue;
            line.setSourceTraces(remainingTraces);
            refreshMergedQuantityAndShipTo(line);
            applyPrimaryTrace(line, remainingTraces.get(0));
            if (remainingTraces.get(0).getBomLineNo() != null) {
                line.setBomLineNo(remainingTraces.get(0).getBomLineNo());
            }
            refreshDuplicateMetadata(line);
            remainingLines.add(line);
        }
        return consolidateFinalLines(remainingLines);
    }

    /**
     * Updates the current MPR from a workbook previously downloaded from this system.
     * Derived/formula columns are ignored and recalculated after import. Hidden line
     * ids are preferred; older exports fall back to the visible business key/row order.
     */
    public MprDocument importExcel(String orderId, MultipartFile file) {
        MprDocument mpr = getByOrder(orderId);
        requireEditable(mpr);
        List<MprExcelImportService.ImportedMprRow> importedRows = excelImportService.parse(file);
        List<MprLine> lines = new ArrayList<>(safeList(mpr.getLines()));

        Map<String, MprLine> byId = lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> hasText(line.getId()))
                .collect(Collectors.toMap(MprLine::getId, line -> line, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<MprLine>> byFallbackKey = new LinkedHashMap<>();
        for (MprLine line : lines) {
            if (line == null) continue;
            byFallbackKey.computeIfAbsent(mprImportFallbackKey(line), ignored -> new ArrayList<>()).add(line);
        }

        Set<String> updatedLineIds = new LinkedHashSet<>();
        for (MprExcelImportService.ImportedMprRow imported : importedRows) {
            MprLine target = null;
            if (hasText(imported.lineId())) target = byId.get(imported.lineId());

            if (target == null) {
                List<MprLine> matches = byFallbackKey.getOrDefault(imported.fallbackKey(), List.of()).stream()
                        .filter(line -> line != null && !updatedLineIds.contains(line.getId()))
                        .toList();
                if (matches.size() == 1) target = matches.get(0);
            }

            if (target == null) {
                int position = imported.excelRow() - 3;
                if (position >= 0 && position < lines.size()) {
                    MprLine candidate = lines.get(position);
                    if (candidate != null && !updatedLineIds.contains(candidate.getId())) target = candidate;
                }
            }

            if (target == null) {
                throw new OrderBomMprValidationException(
                        "Excel row " + imported.excelRow() + ": cannot match this row to the current MPR. "
                                + "Download the latest MPR file and upload it again."
                );
            }

            applyImportedExcelRow(target, imported);
            if (hasText(target.getId())) updatedLineIds.add(target.getId());
        }

        // Excel import is intentionally limited to MPR-owned operational inputs.
        // PO Qty / Ship To and all BOM/Master Data snapshots are not changed here,
        // so Product Color selections remain exactly as they were generated.
        mpr.setLines(consolidateFinalLines(lines));
        orderLinesForDisplay(mpr);
        recalculateMprCalculations(mpr);
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());
        MprDocument saved = mprRepository.save(mpr);
        decorateBomSourceState(saved);
        return saved;
    }

    /**
     * Applies only the operational MPR inputs that Sales is allowed to edit in
     * an exported workbook. BOM/Master Data fields are reference snapshots and
     * must never be overwritten from an MPR upload. Derived values are recalculated
     * after all rows are imported.
     */
    private void applyImportedExcelRow(MprLine target, MprExcelImportService.ImportedMprRow row) {
        if (target == null || row == null) return;

        requireNonNegative(row.sampleQuantity(), "Sample Qty");
        requireNonNegative(row.mcdStock(), "MCD Stock");
        requireNonNegative(row.cmcdStock(), "CMCD Stock");
        requireNonNegative(row.nonSapStockQuantity(), "NON SAP Stock Qty");

        // Blank editable cells are treated as zero, matching the MPR UI.
        target.setSampleQuantity(defaultZero(row.sampleQuantity()));
        target.setMcdStock(defaultZero(row.mcdStock()));
        target.setCmcdStock(defaultZero(row.cmcdStock()));
        target.setNonSapStockQuantity(defaultZero(row.nonSapStockQuantity()));
    }

    private String mprImportFallbackKey(MprLine line) {
        if (line == null) return "";
        return MprExcelImportService.fallbackKey(
                line.getStyleDescription(), line.getStyleColor(), line.getBomLineNo(),
                line.getMaterialType(), firstNonBlank(line.getPosition(), line.getMatFullDescription()),
                line.getMatColor(), line.getMatUnit()
        );
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
        requireEditable(mpr);
        MprLine line = safeList(mpr.getLines()).stream()
                .filter(item -> item != null && lineId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new OrderBomMprNotFoundException("MPR item not found"));

        applyLineUpdate(line, request);
        // Sales changes to values sourced from BOM stay pending until BOM reviews them.
        bomReviewService.capturePendingReview(line);
        // Editing can make this row identical to another saved row. Rebuild the
        // final set immediately and keep one survivor only.
        mpr.setLines(consolidateFinalLines(mpr.getLines()));
        orderLinesForDisplay(mpr);
        recalculateMprCalculations(mpr);
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());
        MprDocument saved = mprRepository.save(mpr);
        decorateBomSourceState(saved);
        return saved;
    }

    /** Deletes only one MPR item and keeps the other MPR rows unchanged. */
    public MprDocument deleteLine(String orderId, String lineId) {
        if (blank(lineId)) {
            throw new OrderBomMprValidationException("MPR item id is required");
        }

        MprDocument mpr = getByOrder(orderId);
        requireEditable(mpr);
        List<MprLine> remaining = new ArrayList<>(safeList(mpr.getLines()));
        boolean removed = remaining.removeIf(item -> item != null && lineId.equals(item.getId()));
        if (!removed) {
            throw new OrderBomMprNotFoundException("MPR item not found");
        }

        if (remaining.isEmpty()) {
            mprRepository.delete(mpr);
            orderService.markBomSubmitted(orderId);
            return null;
        }

        mpr.setLines(consolidateFinalLines(remaining));
        removeEmptyBatchSelections(mpr);
        mpr.setPoQuantity(totalPoQuantity(mpr.getSelections()));
        orderLinesForDisplay(mpr);
        recalculateMprCalculations(mpr);
        mpr.setUpdatedAt(LocalDateTime.now());
        mpr.setUpdatedBy(RequestActor.current());
        MprDocument saved = mprRepository.save(mpr);
        decorateBomSourceState(saved);
        return saved;
    }

    /**
     * A batch is no longer shown in the FE when all of its lines were deleted
     * one-by-one. Legacy selections without a batch id remain untouched.
     */
    private void removeEmptyBatchSelections(MprDocument mpr) {
        Set<String> activeBatchIds = safeList(mpr.getLines()).stream()
                .filter(Objects::nonNull)
                .flatMap(line -> {
                    ensureSourceTraces(line);
                    return safeList(line.getSourceTraces()).stream();
                })
                .filter(Objects::nonNull)
                .map(MprSourceTrace::getGenerationBatchId)
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
        target.setYield(defaultZero(request.yield()));
        target.setLossFactor(defaultZero(request.lossFactor()));
        redistributeTraceQuantityToLineTotal(target, defaultZero(request.poQuantity()));
        target.setSampleQuantity(defaultZero(request.sampleQuantity()));
        target.setMcdStock(defaultZero(request.mcdStock()));
        target.setCmcdStock(defaultZero(request.cmcdStock()));
        target.setNonSapStockQuantity(defaultZero(request.nonSapStockQuantity()));

        target.setCurrency(normalizeCurrency(request.currency()));
        target.setMatPriceWithoutTax(defaultZero(request.matPriceWithoutTax()));
        target.setShortNameSupplier(trim(request.shortNameSupplier()));
        target.setVendorCode(vendorCodeText(request.vendorCode()));
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
        requireNonNegative(request.sampleQuantity(), "Sample Qty");
        requireNonNegative(request.mcdStock(), "MCD Stock");
        requireNonNegative(request.cmcdStock(), "CMCD Stock");
        requireNonNegative(request.nonSapStockQuantity(), "NON SAP Stock Qty");
        requireNonNegative(request.matPriceWithoutTax(), "MAT Price (W/O Tax)");
    }

    private void requireLlBeanImplementation(String buyerKey, String feature) {
        String normalizedBuyerKey = BuyerKeys.legacyDefault(buyerKey);
        if (!BuyerKeys.LL_BEAN.equals(normalizedBuyerKey)) {
            throw new OrderBomMprValidationException(
                    feature + " is currently configured for L.L.BEAN only. "
                            + "Buyer strategy has not been configured for " + normalizedBuyerKey
            );
        }
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

    private MprDocument build(String orderId, MprGenerateRequest request, boolean calculate) {
        if (request == null || request.selections() == null || request.selections().isEmpty()) {
            throw new OrderBomMprValidationException("Select at least one submitted BOM");
        }

        SalesOrder order = orderService.get(orderId);
        String buyerKey = BuyerKeys.legacyDefault(order.getBuyerKey());
        requireLlBeanImplementation(buyerKey, "MPR generation");

        Map<String, MatInfo> matByKey = buildMatInfoCache(buyerKey);
        Map<String, Loss> lossByKey = lossRepository.findByBuyerKey(buyerKey).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        item -> normalize(item.getMaterialGroup()),
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        Map<String, VendorCode> vendorByKey = vendorCodeRepository.findByBuyerKey(buyerKey).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        item -> normalize(item.getShortNameSupplier()),
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        Map<String, ShipTo> shipToById = buildShipToCache(buyerKey);
        Map<String, MaterialShipToMapping> dedicatedShipToByMaterialKey = materialShipToMappingRepository
                .findByBuyerKeyAndActiveTrue(buyerKey).stream()
                .filter(Objects::nonNull)
                .filter(item -> hasText(item.getMaterialKey()))
                .collect(Collectors.toMap(
                        MaterialShipToMapping::getMaterialKey,
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        List<MprSelectionRequest> requestedSelections = new ArrayList<>();
        LinkedHashSet<String> requestedBomIds = new LinkedHashSet<>();
        for (MprSelectionRequest selectionRequest : request.selections()) {
            if (selectionRequest == null || blank(selectionRequest.bomId())) {
                throw new OrderBomMprValidationException("BOM id is required for every selection");
            }
            if (!requestedBomIds.add(selectionRequest.bomId())) {
                throw new OrderBomMprValidationException("The same BOM can only be selected once");
            }
            requestedSelections.add(selectionRequest);
        }

        // One BOM query + one bom_lines query for the complete selection. This
        // replaces the previous N BOM queries + N line queries workflow.
        Map<String, BomDocument> selectedBomById = new LinkedHashMap<>();
        for (BomDocument bom : bomRepository.findAllById(requestedBomIds)) {
            if (bom != null && hasText(bom.getId())) selectedBomById.put(bom.getId(), bom);
        }
        List<BomDocument> selectedBoms = requestedBomIds.stream()
                .map(selectedBomById::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        lineStore.hydrateAllForMpr(selectedBoms);

        List<MprSelection> selections = new ArrayList<>();
        List<MprLine> generated = new ArrayList<>();
        BigDecimal totalPoQuantity = BigDecimal.ZERO;
        BigDecimal sampleQuantity = safe(request.sampleQuantity());

        for (MprSelectionRequest selectionRequest : requestedSelections) {
            BomDocument bom = selectedBomById.get(selectionRequest.bomId());
            if (bom == null) {
                throw new OrderBomMprNotFoundException("Selected BOM not found");
            }
            if (!buyerKey.equals(BuyerKeys.legacyDefault(bom.getBuyerKey()))) {
                throw new OrderBomMprValidationException("Selected BOM belongs to another Buyer");
            }
            if (!orderId.equals(bom.getOrderId())) {
                throw new OrderBomMprValidationException("Selected BOM does not belong to this order");
            }
            if (!"SUBMITTED".equalsIgnoreCase(bom.getStatus())) {
                throw new OrderBomMprValidationException("Only submitted BOM can be used to create MPR: " + bom.getBomNo());
            }

            List<BomProductColor> selectedProductColors = normalizeSelectionProductColors(selectionRequest.colors(), bom);
            if (selectedProductColors.isEmpty()) {
                throw new OrderBomMprValidationException("Select at least one Product Color for BOM " + bom.getBomNo());
            }

            List<String> productColorIds = selectedProductColors.stream()
                    .map(BomProductColor::getId)
                    .map(this::trim)
                    .filter(this::hasText)
                    .collect(Collectors.toCollection(ArrayList::new));

            Map<String, BigDecimal> poQtyByColor = new LinkedHashMap<>();
            Map<String, List<String>> shipToIdsByColor = new LinkedHashMap<>();
            Map<String, Map<String, BigDecimal>> shipToQtyByColor = new LinkedHashMap<>();
            Map<String, String> shipToByColor = new LinkedHashMap<>();
            for (BomProductColor productColor : selectedProductColors) {
                String productColorId = trim(productColor.getId());
                String colorName = trim(productColor.getColorName());
                List<String> shipToIds = shipToIdsForColor(selectionRequest, bom, productColor, shipToById);
                Map<String, BigDecimal> shipToQty = shipToQuantitiesForColor(
                        selectionRequest, bom, productColor, shipToIds, request.poQuantity()
                );
                BigDecimal poQuantity = shipToQty.isEmpty()
                        ? poQuantityForColor(selectionRequest, bom, productColor, request.poQuantity())
                        : shipToQty.values().stream().map(this::safe).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (poQuantity.signum() < 0) {
                    throw new OrderBomMprValidationException("PO Qty cannot be negative for Product Color " + productColorLabel(productColor));
                }
                poQtyByColor.put(productColorId, poQuantity);
                shipToIdsByColor.put(productColorId, shipToIds);
                shipToQtyByColor.put(productColorId, shipToQty);
                shipToByColor.put(productColorId, shipToDisplay(shipToIds, shipToById));
                totalPoQuantity = totalPoQuantity.add(poQuantity);
            }

            MprSelection selection = new MprSelection();
            selection.setBatchId(UUID.randomUUID().toString());
            selection.setCreatedAt(LocalDateTime.now());
            selection.setCreatedBy(RequestActor.current());
            selection.setBomId(bom.getId());
            selection.setBomNo(bom.getBomNo());
            selection.setBomName(bom.getBomName());
            selection.setBomSourceRevision(BomMprSourceRevision.current(bom));
            selection.setBomSourceChangedAt(bom.getMprSourceChangedAt());
            selection.setColors(productColorIds);
            selection.setPackingIds(selectionRequest.packingIds() == null ? new ArrayList<>() : new ArrayList<>(selectionRequest.packingIds()));
            selection.setPoQtyByColor(poQtyByColor);
            selection.setShipToIdsByColor(shipToIdsByColor);
            selection.setShipToQtyByColor(shipToQtyByColor);
            selection.setShipToByColor(shipToByColor);
            selections.add(selection);

            /*
             * Every selected Product Color receives the complete selected source
             * structure independently:
             *
             *   Core BOM rows + every selected Packing's rows.
             *
             * Packing applicability metadata is not used here. For example, when
             * Core has 15 rows and US/JAPAN have 15/20 rows, each selected color
             * receives all 50 physical source rows. After collection, rows with
             * identical MTR + POSITION + CONS. + NET CONSUMPTION/MK + UNIT are
             * consolidated while every original row stays available in sourceTraces.
             */
            Map<String, BomPacking> packingById = safeList(bom.getPackings()).stream()
                    .filter(Objects::nonNull)
                    .filter(item -> hasText(item.getId()))
                    .collect(Collectors.toMap(
                            BomPacking::getId,
                            item -> item,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
            List<BomPacking> selectedPackings = new ArrayList<>();
            for (String packingId : safeList(selection.getPackingIds())) {
                if (blank(packingId)) continue;
                BomPacking packing = packingById.get(packingId);
                if (packing == null) {
                    throw new OrderBomMprValidationException("Packing not found in BOM " + bom.getBomNo());
                }
                selectedPackings.add(packing);
            }
            // Product Color is the outer loop so every exact 4-field Product Color
            // identity owns one complete, consecutive block.
            for (BomProductColor productColor : selectedProductColors) {
                String productColorId = trim(productColor.getId());
                String colorName = trim(productColor.getColorName());
                List<MprLine> rowsForColor = new ArrayList<>();

                // 1) Copy every original BOM row without Packing into this color.
                for (BomLine coreLine : safeList(bom.getCoreLines())) {
                    appendForColor(
                            rowsForColor,
                            bom, null, coreLine, "CORE", colorName, productColorId,
                            poQtyByColor.get(productColorId), sampleQuantity, selection.getBatchId(),
                            shipToIdsByColor.get(productColorId), shipToByColor.get(productColorId),
                            shipToQtyByColor.get(productColorId), dedicatedShipToByMaterialKey, shipToById,
                            matByKey, lossByKey, vendorByKey
                    );
                }

                // 2) Copy every row from every selected Packing into this same color.
                for (BomPacking packing : selectedPackings) {
                    for (BomLine packingLine : safeList(packing.getLines())) {
                        appendForColor(
                                rowsForColor,
                                bom, packing, packingLine, "PACKING", colorName, productColorId,
                                poQtyByColor.get(productColorId), sampleQuantity, selection.getBatchId(),
                                shipToIdsByColor.get(productColorId), shipToByColor.get(productColorId),
                                shipToQtyByColor.get(productColorId), dedicatedShipToByMaterialKey, shipToById,
                                matByKey, lossByKey, vendorByKey
                        );
                    }
                }
                generated.addAll(rowsForColor);
            }
        }

        MprDocument mpr = new MprDocument();
        mpr.setOrderId(order.getId());
        mpr.setBuyerKey(buyerKey);
        mpr.setMprNo(blank(request.mprNo()) ? "MPR-" + order.getOrderNo() : request.mprNo().trim());
        mpr.setPoQuantity(totalPoQuantity);
        mpr.setSampleQuantity(sampleQuantity);
        mpr.setSelections(selections);
        LineMergeResult consolidated = mergeLineSets(List.of(), generated);
        mpr.setLines(consolidated.lines());
        mpr.setStatus(MprDocument.STATUS_IN_PROGRESS);
        orderLinesForDisplay(mpr);
        if (calculate) recalculateMprCalculations(mpr);
        return mpr;
    }

    /** Creates one MPR row for one BOM material line and one selected Product Color. */
    private void appendForColor(
            List<MprLine> out,
            BomDocument bom,
            BomPacking packing,
            BomLine source,
            String section,
            String selectedColor,
            String productColorId,
            BigDecimal poQuantity,
            BigDecimal sampleQuantity,
            String generationBatchId,
            List<String> shipToIds,
            String shipTo,
            Map<String, BigDecimal> shipToQtyById,
            Map<String, MaterialShipToMapping> dedicatedShipToByMaterialKey,
            Map<String, ShipTo> shipToById,
            Map<String, MatInfo> matByKey,
            Map<String, Loss> lossByKey,
            Map<String, VendorCode> vendorByKey
    ) {
        if (!isPurchasableMaterialLine(source)) return;

        String description = bomMaterialDescription(source);
        String materialColor = materialColorFor(source, selectedColor, productColorId);
        MatInfo mat = findMatInfo(matByKey, source, materialColor);
        String materialType = trim(source.getMaterialType());
        String resolvedSapCode = firstNonBlank(source.getSapCode(), mat == null ? null : mat.getFlexId());
        String resolvedMatUnit = firstNonBlank(source.getConsumptionUnit(), source.getCostingUnit(), mat == null ? null : mat.getMatUnit());
        String materialMappingKey = MaterialShipToMappingKeys.build(
                resolvedSapCode, materialType, description,
                firstNonBlank(source.getPosition(), source.getPositionDescription(), source.getPositionDescriptionExtra()),
                materialColor, resolvedMatUnit
        );
        MaterialShipToMapping dedicatedMapping = dedicatedShipToByMaterialKey == null
                ? null : dedicatedShipToByMaterialKey.get(materialMappingKey);
        List<String> effectiveShipToIds = shipToIds == null ? new ArrayList<>() : new ArrayList<>(shipToIds);
        String effectiveShipTo = trim(shipTo);
        BigDecimal linePoQuantity = safe(poQuantity);
        if (dedicatedMapping != null) {
            String materialLabel = firstNonBlank(resolvedSapCode, description, materialType);
            List<String> dedicatedIds = dedicatedShipToIds(dedicatedMapping, shipToById, materialLabel);
            effectiveShipToIds = effectiveShipToIds.stream()
                    .filter(dedicatedIds::contains)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (effectiveShipToIds.isEmpty()) {
                throw new OrderBomMprValidationException(
                        "Material " + materialLabel
                                + " is dedicated to " + dedicatedShipToLabel(dedicatedIds, shipToById)
                                + ", but none of those Ship To values was selected for Product Color " + selectedColor
                );
            }

            BigDecimal dedicatedTotal = BigDecimal.ZERO;
            for (String dedicatedId : effectiveShipToIds) {
                BigDecimal dedicatedQty = shipToQtyById == null ? null : shipToQtyById.get(dedicatedId);
                if (dedicatedQty == null) {
                    if (safeList(shipToIds).size() == 1) dedicatedQty = safe(poQuantity);
                    else throw new OrderBomMprValidationException(
                            "Enter separate PO Qty for every selected Ship To before generating dedicated material " + materialLabel
                    );
                }
                dedicatedTotal = dedicatedTotal.add(safe(dedicatedQty));
            }
            linePoQuantity = dedicatedTotal;
            effectiveShipTo = shipToDisplay(effectiveShipToIds, shipToById);
        }
        BigDecimal yield = source.getConsumptionNet();
        BigDecimal lineSampleQuantity = safe(sampleQuantity);
        BigDecimal totalOrderQuantity = linePoQuantity.add(lineSampleQuantity);
        BigDecimal factor = lossFactor(lossByKey.get(normalize(materialType)), totalOrderQuantity);
        BigDecimal totalYield = multiplyExact(yield, factor);
        BigDecimal matRequiredQuantity = multiplyExact(totalYield, linePoQuantity);

        MprLine line = new MprLine();
        line.setId(UUID.randomUUID().toString());
        line.setBomId(bom.getId());
        line.setBomNo(bom.getBomNo());
        line.setBomName(bom.getBomName());
        line.setSourceRowNumber(source.getSourceRowNumber());
        line.setSourceLineId(source.getId());
        line.setPackingId(packing == null ? null : packing.getId());
        line.setPackingName(packing == null ? null : trim(packing.getPackingName()));
        line.setSection(section);
        line.setProductColorId(trim(productColorId));
        line.setGenerationBatchId(generationBatchId);
        // Persist source identity only to prevent re-adding the exact same
        // Core/Packing source row in a later Add To MPR action.
        line.setSourceBomDedupKey(bomSourceSelectionKey(bom, packing, source, selectedColor, productColorId));
        line.setSourceDetailConsumption(source.getDetailConsumption());

        MprSourceTrace trace = new MprSourceTrace();
        trace.setGenerationBatchId(generationBatchId);
        trace.setSourceBomDedupKey(line.getSourceBomDedupKey());
        trace.setSourceLineId(source.getId());
        trace.setSourceRowNumber(source.getSourceRowNumber());
        trace.setBomLineNo(source.getMaterialGroupNo());
        trace.setPackingId(packing == null ? null : packing.getId());
        trace.setPackingName(packing == null ? null : trim(packing.getPackingName()));
        trace.setSection(section);
        trace.setSourceLabel(sourceLabel(section, trace.getPackingName()));
        trace.setPoQuantity(linePoQuantity);
        trace.setShipToIds(new ArrayList<>(effectiveShipToIds));
        trace.setShipTo(effectiveShipTo);
        line.setSourceTraces(new ArrayList<>(List.of(trace)));

        // A-C are created from BOM Header and the chosen Product Color.
        String styleDescription = firstNonBlank(
                bom.getHeader() == null ? null : bom.getHeader().getStyleName(),
                bom.getBomName()
        );
        line.setStyleDescription(styleDescription);
        line.setStyleColor(trim(selectedColor));
        line.setStyleColorKey(styleColorKey(styleDescription, selectedColor));

        // D-E: Ship To is selected by Sales together with the Product Color.
        line.setShipToIds(effectiveShipToIds);
        line.setShipTo(effectiveShipTo);
        line.setSalesComment(null);

        // G-Q: BOM values are the source of truth. Master Data only fills missing commercial fields.
        line.setSapCode(resolvedSapCode);
        line.setBomLineNo(source.getMaterialGroupNo());
        line.setMaterialType(materialType);
        line.setPosition(firstNonBlank(source.getPosition(), source.getPositionDescription(), source.getPositionDescriptionExtra()));
        line.setMatFullDescription(description);
        line.setMatColor(materialColor);
        line.setMatUnit(resolvedMatUnit);
        line.setYield(yield);
        line.setLossFactor(factor);
        line.setTotalYield(totalYield);
        line.setPoQuantity(linePoQuantity);
        line.setMatRequiredQuantity(matRequiredQuantity);

        // P is supplied at MPR creation. R/S/U are editable stock inputs.
        // Q/T/V are derived later by recalculateLineCalculations.
        line.setSampleQuantity(defaultZero(lineSampleQuantity));
        line.setMatSampleQuantity(BigDecimal.ZERO);
        line.setMcdStock(BigDecimal.ZERO);
        line.setCmcdStock(BigDecimal.ZERO);
        line.setSapStockQuantity(BigDecimal.ZERO);
        line.setNonSapStockQuantity(BigDecimal.ZERO);
        line.setPurchaseQuantity(BigDecimal.ZERO);

        // Y-AD: fields that can be linked with certainty from current Master Data.
        if (mat != null) {
            line.setCurrency(mat.getCurrency());
            line.setMatPriceWithoutTax(mat.getMatPriceWithoutTax());
            line.setShortNameSupplier(mat.getShortNameSupplier());
        }
        VendorCode vendor = vendorByKey.get(normalize(line.getShortNameSupplier()));
        if (vendor != null) {
            line.setVendorCode(vendorCodeText(vendor.getVendorCode()));
            line.setVendorName(vendor.getVendorName());
            line.setMatCharger(vendor.getMatCharger());
        }

        // Currency is resolved once per MPR document during bulk recalculation.

        // AG-AI depend on stock, due date, and final purchasing rules, so remain blank for now.
        line.setMatAmountUsd(null);
        line.setMatDueDate(null);
        line.setTotalMatAmountPerStyle(null);
        line.setSourceRemark(source.getBomRemark());
        out.add(line);
    }

    private String vendorCodeText(String value) {
        String text = trim(value);
        if (text == null) {
            return null;
        }
        return text.matches("^[0-9,]+$") ? text.replace(",", "") : text;
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

    /**
     * Older MongoDB MPR rows did not persist POSITION. Resolve it in bulk from
     * the originating BOM/Core/Packing row so existing MPRs export correctly
     * without forcing Sales to delete and recreate them.
     */
    private void backfillMissingBomSourceFields(MprDocument mpr) {
        List<MprLine> targets = safeList(mpr == null ? null : mpr.getLines()).stream()
                .filter(Objects::nonNull)
                .filter(line -> blank(line.getPosition())
                        || line.getSourceDetailConsumption() == null
                        || line.getYield() == null
                        || blank(line.getMatUnit()))
                .toList();
        if (targets.isEmpty()) return;

        LinkedHashSet<String> bomIds = targets.stream()
                .map(MprLine::getBomId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (bomIds.isEmpty()) return;

        List<BomDocument> boms = new ArrayList<>();
        bomRepository.findAllById(bomIds).forEach(boms::add);
        lineStore.hydrateAllForMpr(boms);
        Map<String, BomDocument> bomById = boms.stream()
                .filter(Objects::nonNull)
                .filter(bom -> hasText(bom.getId()))
                .collect(Collectors.toMap(
                        BomDocument::getId,
                        bom -> bom,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        for (MprLine line : targets) {
            BomLine source = findMprSourceLine(bomById.get(line.getBomId()), line);
            if (source == null) continue;
            if (blank(line.getPosition())) {
                line.setPosition(firstNonBlank(
                        source.getPosition(),
                        source.getPositionDescription(),
                        source.getPositionDescriptionExtra()
                ));
            }
            if (line.getSourceDetailConsumption() == null) {
                line.setSourceDetailConsumption(source.getDetailConsumption());
            }
            if (line.getYield() == null) line.setYield(source.getConsumptionNet());
            if (blank(line.getMatUnit())) {
                line.setMatUnit(firstNonBlank(source.getConsumptionUnit(), source.getCostingUnit()));
            }
            if (line.getBomLineNo() == null) line.setBomLineNo(source.getMaterialGroupNo());
            if (blank(line.getMaterialType())) line.setMaterialType(trim(source.getMaterialType()));
        }
    }


    /**
     * MAT_INFO is matched when a batch is generated, but an existing editable
     * MPR can still have blank commercial fields if MAT_INFO was added later.
     * Fill only missing values; never overwrite saved Sales/MPR values.
     */
    private void backfillMissingMatInfoFields(MprDocument mpr, String buyerKey) {
        if (mpr == null || safeList(mpr.getLines()).isEmpty()) return;

        String normalizedBuyer = BuyerKeys.legacyDefault(buyerKey);
        Map<String, MatInfo> matByKey = buildMatInfoCache(normalizedBuyer);
        if (matByKey.isEmpty()) return;

        Map<String, VendorCode> vendorByKey = vendorCodeRepository.findByBuyerKey(normalizedBuyer).stream()
                .filter(Objects::nonNull)
                .filter(item -> hasText(item.getShortNameSupplier()))
                .collect(Collectors.toMap(
                        item -> normalize(item.getShortNameSupplier()),
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        for (MprLine line : safeList(mpr.getLines())) {
            if (line == null) continue;

            boolean missingCurrency = blank(line.getCurrency());
            boolean missingSupplier = blank(line.getShortNameSupplier());
            boolean missingPrice = line.getMatPriceWithoutTax() == null
                    || line.getMatPriceWithoutTax().signum() == 0;

            if (missingCurrency || missingSupplier || missingPrice) {
                MatInfo mat = findMatInfo(matByKey, line);
                if (mat != null) {
                    if (missingCurrency && hasText(mat.getCurrency())) {
                        line.setCurrency(normalizeCurrency(mat.getCurrency()));
                    }
                    if (missingPrice && mat.getMatPriceWithoutTax() != null) {
                        line.setMatPriceWithoutTax(mat.getMatPriceWithoutTax());
                    }
                    if (missingSupplier && hasText(mat.getShortNameSupplier())) {
                        line.setShortNameSupplier(trim(mat.getShortNameSupplier()));
                    }
                }
            }

            backfillMissingVendorFields(line, vendorByKey);
        }
    }

    /**
     * MPR-line version of the same MAT_INFO lookup used during generation:
     * Material Type + Description/Position + Material Color.
     */
    private MatInfo findMatInfo(Map<String, MatInfo> cache, MprLine line) {
        if (cache == null || cache.isEmpty() || line == null) return null;

        String materialType = line.getMaterialType();
        LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        descriptions.add(trim(line.getMatFullDescription()));
        descriptions.add(trim(line.getPosition()));

        for (String description : descriptions) {
            if (blank(description)) continue;
            MatInfo exact = cache.get(materialKey(
                    materialType,
                    description,
                    line.getMatColor(),
                    line.getMatUnit()
            ));
            if (exact != null) return exact;
        }
        return null;
    }

    private void backfillMissingVendorFields(MprLine line, Map<String, VendorCode> vendorByKey) {
        if (line == null || vendorByKey == null || vendorByKey.isEmpty()) return;
        if (!hasText(line.getShortNameSupplier())) return;

        VendorCode vendor = vendorByKey.get(normalize(line.getShortNameSupplier()));
        if (vendor == null) return;

        if (blank(line.getVendorCode())) {
            line.setVendorCode(vendorCodeText(vendor.getVendorCode()));
        }
        if (blank(line.getVendorName())) {
            line.setVendorName(vendor.getVendorName());
        }
        if (blank(line.getMatCharger())) {
            line.setMatCharger(vendor.getMatCharger());
        }
    }

    private BomLine findMprSourceLine(BomDocument bom, MprLine line) {
        if (bom == null || line == null) return null;
        List<BomLine> candidates = new ArrayList<>();
        if (hasText(line.getPackingId())) {
            safeList(bom.getPackings()).stream()
                    .filter(Objects::nonNull)
                    .filter(packing -> line.getPackingId().equals(packing.getId()))
                    .findFirst()
                    .ifPresent(packing -> candidates.addAll(safeList(packing.getLines())));
        } else {
            candidates.addAll(safeList(bom.getCoreLines()));
        }
        if (candidates.isEmpty()) {
            candidates.addAll(safeList(bom.getCoreLines()));
            for (BomPacking packing : safeList(bom.getPackings())) {
                if (packing != null) candidates.addAll(safeList(packing.getLines()));
            }
        }

        if (hasText(line.getSourceLineId())) {
            for (BomLine source : candidates) {
                if (source != null && line.getSourceLineId().equals(source.getId())) return source;
            }
        }
        if (line.getSourceRowNumber() != null) {
            for (BomLine source : candidates) {
                if (source != null && Objects.equals(line.getSourceRowNumber(), source.getSourceRowNumber())) {
                    return source;
                }
            }
        }
        return null;
    }

    private List<MatInfo> loadActiveMatInfos(String buyerKey) {
        return matInfoRepository.findByBuyerKey(BuyerKeys.legacyDefault(buyerKey)).stream()
                .filter(Objects::nonNull)
                .filter(MatInfo::isActive)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Map<String, MatInfo> buildMatInfoCache(String buyerKey) {
        return buildMatInfoCache(loadActiveMatInfos(buyerKey));
    }

    private Map<String, MatInfo> buildMatInfoCache(List<MatInfo> matInfos) {
        Map<String, MatInfo> result = new LinkedHashMap<>();
        for (MatInfo item : safeList(matInfos)) {
            if (item == null) continue;
            String fullKey = materialKey(
                    item.getMaterialType(),
                    item.getMatFullDescription(),
                    item.getMatColor(),
                    item.getMatUnit()
            );
            result.putIfAbsent(fullKey, item);
        }
        return result;
    }

    private MprValidationIssue buildBomMatInfoWarning(
            BomDocument bom,
            BomProductColor productColor,
            BomLine source,
            String materialType,
            String description,
            String materialColor,
            String materialUnit,
            List<MatInfo> matInfos
    ) {
        if (bom == null || source == null) return null;
        MatInfo possible = findPossibleMatInfo(matInfos, materialType, description, materialColor, materialUnit);
        List<String> mismatchFields = matInfoMismatchFields(possible, materialType, description, materialColor, materialUnit);
        String code = possible == null ? "MAT_INFO_NOT_FOUND" : "MAT_INFO_MISMATCH";
        String materialLabel = firstNonBlank(source.getSapCode(), description, materialType);
        String productColorDisplay = productColor == null ? "" : productColorLabel(productColor);
        String message = possible == null
                ? "No exact MAT Info match. Check Material Type, MAT Full Description, MAT Color and MAT Unit."
                : "Possible MAT Info found, but " + String.join(", ", mismatchFields)
                        + " does not match. Review Master Data before continuing.";

        return new MprValidationIssue(
                code,
                bom.getId(),
                trim(bom.getBomNo()),
                trim(bom.getBomName()),
                productColor == null ? "" : trim(productColor.getId()),
                productColorDisplay,
                null, null, null,
                List.of(), List.of(), List.of(),
                hasText(materialLabel) ? List.of(materialLabel) : List.of(),
                message,
                "WARNING",
                false,
                trim(materialType),
                trim(description),
                trim(materialColor),
                trim(materialUnit),
                possible == null ? null : trim(possible.getMaterialType()),
                possible == null ? null : trim(possible.getMatFullDescription()),
                possible == null ? null : trim(possible.getMatColor()),
                possible == null ? null : trim(possible.getMatUnit()),
                mismatchFields,
                source.getSourceRowNumber()
        );
    }

    private MprValidationIssue buildMprLineMatInfoWarning(MprLine line, List<MatInfo> matInfos) {
        if (line == null) return null;
        String description = firstNonBlank(line.getMatFullDescription(), line.getPosition());
        MatInfo possible = findPossibleMatInfo(
                matInfos, line.getMaterialType(), description, line.getMatColor(), line.getMatUnit()
        );
        List<String> mismatchFields = matInfoMismatchFields(
                possible, line.getMaterialType(), description, line.getMatColor(), line.getMatUnit()
        );
        String code = possible == null ? "MAT_INFO_NOT_FOUND" : "MAT_INFO_MISMATCH";
        String materialLabel = firstNonBlank(line.getSapCode(), description, line.getMaterialType());
        String message = possible == null
                ? "No exact MAT Info match. Export may contain blank Currency, Price or Supplier."
                : "Possible MAT Info found, but " + String.join(", ", mismatchFields)
                        + " does not match. Export may contain incorrect or missing commercial data.";

        return new MprValidationIssue(
                code,
                line.getBomId(),
                trim(line.getBomNo()),
                trim(line.getBomName()),
                trim(line.getProductColorId()),
                trim(line.getStyleColor()),
                null, null, null,
                List.of(), List.of(), List.of(),
                hasText(materialLabel) ? List.of(materialLabel) : List.of(),
                message,
                "WARNING",
                false,
                trim(line.getMaterialType()),
                trim(description),
                trim(line.getMatColor()),
                trim(line.getMatUnit()),
                possible == null ? null : trim(possible.getMaterialType()),
                possible == null ? null : trim(possible.getMatFullDescription()),
                possible == null ? null : trim(possible.getMatColor()),
                possible == null ? null : trim(possible.getMatUnit()),
                mismatchFields,
                line.getSourceRowNumber()
        );
    }

    /**
     * Suggest a MAT Info row only when at least three of the four identity fields
     * match. This is diagnostic only; it is never used to populate MPR values.
     */
    private MatInfo findPossibleMatInfo(
            List<MatInfo> matInfos,
            String materialType,
            String description,
            String materialColor,
            String materialUnit
    ) {
        MatInfo best = null;
        int bestScore = 0;
        for (MatInfo item : safeList(matInfos)) {
            if (item == null) continue;
            int score = matInfoMatchScore(item, materialType, description, materialColor, materialUnit);
            if (score > bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return bestScore >= 3 ? best : null;
    }

    private int matInfoMatchScore(
            MatInfo item,
            String materialType,
            String description,
            String materialColor,
            String materialUnit
    ) {
        if (item == null) return 0;
        int score = 0;
        if (materialTypeMatchKey(materialType).equals(materialTypeMatchKey(item.getMaterialType()))) score++;
        if (materialTextMatchKey(description).equals(materialTextMatchKey(item.getMatFullDescription()))) score++;
        if (materialTextMatchKey(materialColor).equals(materialTextMatchKey(item.getMatColor()))) score++;
        if (materialTextMatchKey(materialUnit).equals(materialTextMatchKey(item.getMatUnit()))) score++;
        return score;
    }

    private List<String> matInfoMismatchFields(
            MatInfo possible,
            String materialType,
            String description,
            String materialColor,
            String materialUnit
    ) {
        if (possible == null) return List.of("MAT Info record");
        List<String> fields = new ArrayList<>();
        if (!materialTypeMatchKey(materialType).equals(materialTypeMatchKey(possible.getMaterialType()))) {
            fields.add("Material Type");
        }
        if (!materialTextMatchKey(description).equals(materialTextMatchKey(possible.getMatFullDescription()))) {
            fields.add("MAT Full Description");
        }
        if (!materialTextMatchKey(materialColor).equals(materialTextMatchKey(possible.getMatColor()))) {
            fields.add("MAT Color");
        }
        if (!materialTextMatchKey(materialUnit).equals(materialTextMatchKey(possible.getMatUnit()))) {
            fields.add("MAT Unit");
        }
        return fields.isEmpty() ? List.of("MAT Info identity") : fields;
    }

    private String matInfoWarningKey(MprValidationIssue issue) {
        if (issue == null) return "";
        return normalize(issue.bomId())
                + "|" + normalize(issue.productColorId())
                + "|" + materialTypeMatchKey(issue.materialType())
                + "|" + materialTextMatchKey(issue.matFullDescription())
                + "|" + materialTextMatchKey(issue.matColor())
                + "|" + materialTextMatchKey(issue.matUnit());
    }

    /**
     * MAT_INFO must match the actual material identity exactly:
     * Material Type + Description + MAT Color + MAT Unit.
     *
     * Do not fall back by dropping color: one description can legitimately have
     * NATURAL, WEATHERED OAK, etc. with different prices/currencies/suppliers.
     */
    private MatInfo findMatInfo(Map<String, MatInfo> cache, BomLine line, String materialColor) {
        String materialType = line == null ? "" : line.getMaterialType();
        String materialUnit = line == null ? "" : firstNonBlank(line.getConsumptionUnit(), line.getCostingUnit());
        LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        descriptions.add(bomMaterialDescription(line));
        if (line != null) {
            descriptions.add(trim(line.getPosition()));
            descriptions.add(trim(line.getPositionDescription()));
            descriptions.add(trim(line.getPositionDescriptionExtra()));
        }

        for (String description : descriptions) {
            if (blank(description)) continue;
            MatInfo exact = cache.get(materialKey(materialType, description, materialColor, materialUnit));
            if (exact != null) return exact;
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
     * One BOM Product Color is a business identity made from exactly four fields:
     * Product/Style Color + Pattern Number + Season + Style Number.
     *
     * The API persists the stable Product Color id for that four-field identity.
     * A readable color name is accepted only for legacy payloads when that name is
     * unique inside the BOM. If two Product Colors are both NATURAL, a name-only
     * request is ambiguous and is rejected instead of silently selecting the first.
     */
    private List<BomProductColor> normalizeSelectionProductColors(
            List<String> requestedProductColors,
            BomDocument bom
    ) {
        LinkedHashMap<String, BomProductColor> selectedByIdentity = new LinkedHashMap<>();
        for (String requested : safeList(requestedProductColors)) {
            if (blank(requested)) continue;
            BomProductColor productColor = resolveProductColorReference(bom, requested);
            if (productColor == null) {
                throw new OrderBomMprValidationException(
                        "Selected Product Color does not belong to BOM " + bom.getBomNo()
                );
            }
            selectedByIdentity.putIfAbsent(productColorBusinessIdentityKey(productColor), productColor);
        }
        return new ArrayList<>(selectedByIdentity.values());
    }

    private BomProductColor resolveProductColorReference(BomDocument bom, String idOrLegacyName) {
        String reference = trim(idOrLegacyName);
        if (blank(reference)) return null;

        // Current clients send the stable Product Color id. That id belongs to one
        // exact four-field Product Color identity in the BOM.
        for (BomProductColor item : safeList(bom == null ? null : bom.getProductColors())) {
            if (item == null) continue;
            if (reference.equals(trim(item.getId()))) return item;
            if (normalize(reference).equals(productColorBusinessIdentityKey(item))) return item;
        }

        // Backward compatibility for old saved batches / clients that stored only
        // the readable color name. It is safe only when the name is unique.
        List<BomProductColor> sameName = safeList(bom == null ? null : bom.getProductColors()).stream()
                .filter(Objects::nonNull)
                .filter(item -> normalize(reference).equals(normalize(item.getColorName())))
                .collect(Collectors.toCollection(ArrayList::new));
        if (sameName.size() == 1) return sameName.get(0);
        if (sameName.size() > 1) {
            throw new OrderBomMprValidationException(
                    "Product Color " + reference
                            + " is ambiguous in BOM " + bom.getBomNo()
                            + ". Select the exact Product Color (Color + Pattern Number + Season + Style Number)."
            );
        }
        return null;
    }

    private String productColorBusinessIdentityKey(BomProductColor item) {
        if (item == null) return "";
        return normalize(item.getColorName())
                + "|" + normalize(item.getPatternNumber())
                + "|" + normalize(item.getSeason())
                + "|" + normalize(item.getStyleNumber());
    }

    private String productColorLabel(BomProductColor item) {
        if (item == null) return "";
        return joinNonBlank(
                item.getColorName(),
                item.getPatternNumber(),
                item.getSeason(),
                item.getStyleNumber()
        );
    }

    private boolean productColorNameIsUnique(BomDocument bom, BomProductColor productColor) {
        if (bom == null || productColor == null || blank(productColor.getColorName())) return false;
        String wanted = normalize(productColor.getColorName());
        long count = safeList(bom.getProductColors()).stream()
                .filter(Objects::nonNull)
                .filter(item -> wanted.equals(normalize(item.getColorName())))
                .count();
        return count == 1;
    }

    private <T> T mapValueForProductColor(
            Map<String, T> values,
            BomDocument bom,
            BomProductColor productColor
    ) {
        if (values == null || values.isEmpty() || productColor == null) return null;

        String productColorId = trim(productColor.getId());
        if (hasText(productColorId) && values.containsKey(productColorId)) {
            return values.get(productColorId);
        }
        String businessKey = productColorBusinessIdentityKey(productColor);
        if (hasText(businessKey) && values.containsKey(businessKey)) {
            return values.get(businessKey);
        }

        // Legacy name-keyed maps are accepted only when the visible color name is
        // unique in this BOM. Never let NATURAL resolve to two different columns.
        if (productColorNameIsUnique(bom, productColor)) {
            for (Map.Entry<String, T> entry : values.entrySet()) {
                if (normalize(entry.getKey()).equals(normalize(productColor.getColorName()))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private BigDecimal poQuantityForColor(
            MprSelectionRequest selectionRequest,
            BomDocument bom,
            BomProductColor productColor,
            BigDecimal fallbackQuantity
    ) {
        BigDecimal supplied = mapValueForProductColor(
                selectionRequest == null ? null : selectionRequest.poQtyByColor(),
                bom,
                productColor
        );
        return supplied == null ? safe(fallbackQuantity) : supplied;
    }

    private Map<String, BigDecimal> shipToQuantitiesForColor(
            MprSelectionRequest request,
            BomDocument bom,
            BomProductColor productColor,
            List<String> selectedShipToIds,
            BigDecimal fallbackQuantity
    ) {
        Map<String, BigDecimal> supplied = mapValueForProductColor(
                request == null ? null : request.shipToQtyByColor(),
                bom,
                productColor
        );
        LinkedHashMap<String, BigDecimal> result = new LinkedHashMap<>();
        if (supplied != null && !supplied.isEmpty()) {
            for (String id : safeList(selectedShipToIds)) {
                BigDecimal quantity = supplied.get(id);
                if (quantity == null) {
                    throw new OrderBomMprValidationException(
                            "Enter PO Qty for every selected Ship To in Product Color " + productColorLabel(productColor)
                    );
                }
                if (quantity.signum() < 0) {
                    throw new OrderBomMprValidationException(
                            "Ship To PO Qty cannot be negative for Product Color " + productColorLabel(productColor)
                    );
                }
                result.put(id, quantity);
            }
            for (String suppliedId : supplied.keySet()) {
                if (!safeList(selectedShipToIds).contains(suppliedId)) {
                    throw new OrderBomMprValidationException(
                            "Ship To quantity was provided for an unselected Ship To in Product Color "
                                    + productColorLabel(productColor)
                    );
                }
            }
            return result;
        }

        // Backward compatibility: one selected Ship To can inherit the old
        // Product Color total PO Qty.
        if (safeList(selectedShipToIds).size() == 1) {
            result.put(
                    selectedShipToIds.get(0),
                    poQuantityForColor(request, bom, productColor, fallbackQuantity)
            );
        }
        return result;
    }

    private Map<String, ShipTo> buildShipToCache(String buyerKey) {
        Map<String, ShipTo> result = new LinkedHashMap<>();
        for (ShipTo item : shipToRepository.findByBuyerKeyAndActiveTrueOrderByShipToNameAsc(buyerKey)) {
            if (item == null || blank(item.getId())) continue;
            result.put(item.getId(), item);
        }
        return result;
    }

    /** Ship To is required for every selected four-field Product Color identity. */
    private List<String> shipToIdsForColor(
            MprSelectionRequest request,
            BomDocument bom,
            BomProductColor productColor,
            Map<String, ShipTo> shipToById
    ) {
        List<String> supplied = mapValueForProductColor(
                request == null ? null : request.shipToIdsByColor(),
                bom,
                productColor
        );
        List<String> ids = normalizeShipToIds(supplied, shipToById);
        if (ids.isEmpty()) {
            throw new OrderBomMprValidationException(
                    "Select at least one Ship To for Product Color " + productColorLabel(productColor)
            );
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

    private List<String> dedicatedShipToIds(
            MaterialShipToMapping mapping,
            Map<String, ShipTo> activeShipToById,
            String materialLabel
    ) {
        List<String> storedIds = mapping == null ? List.of() : safeList(mapping.getShipToIds());
        if (storedIds.isEmpty() && mapping != null && hasText(mapping.getShipToId())) {
            storedIds = List.of(mapping.getShipToId());
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String id : storedIds) {
            String clean = trim(id);
            if (clean.isEmpty()) continue;
            if (!activeShipToById.containsKey(clean)) {
                throw new OrderBomMprValidationException(
                        "Dedicated Ship To configuration for material " + firstNonBlank(materialLabel, "(unknown material)")
                                + " contains an inactive or missing Ship To. Update Material Ship To Master before creating MPR."
                );
            }
            result.add(clean);
        }
        if (result.isEmpty()) {
            throw new OrderBomMprValidationException(
                    "Dedicated Ship To configuration for material " + firstNonBlank(materialLabel, "(unknown material)")
                            + " is empty. Update Material Ship To Master before creating MPR."
            );
        }
        return new ArrayList<>(result);
    }

    private List<String> shipToValues(List<String> ids, Map<String, ShipTo> shipToById, boolean code) {
        return safeList(ids).stream()
                .map(shipToById::get)
                .filter(Objects::nonNull)
                .map(item -> code
                        ? firstNonBlank(item.getShipToCode(), item.getShipToName(), item.getId())
                        : firstNonBlank(item.getShipToName(), item.getShipToCode(), item.getId()))
                .filter(this::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String dedicatedShipToLabel(List<String> ids, Map<String, ShipTo> shipToById) {
        return safeList(ids).stream()
                .map(id -> {
                    ShipTo shipTo = shipToById.get(id);
                    return shipTo == null ? id : firstNonBlank(shipTo.getShipToCode(), shipTo.getShipToName(), id);
                })
                .filter(this::hasText)
                .collect(Collectors.joining(" / "));
    }

    private String firstListValue(List<String> values) {
        return safeList(values).stream().filter(this::hasText).findFirst().orElse("");
    }

    /** STYLE_COLOR is the readable concatenation of STYLE DESCRIPTION and STYLE COLOR. */
    private String styleColorKey(String styleDescription, String styleColor) {
        return joinNonBlank(styleDescription, styleColor);
    }

    /** Returns the Material Color written in the selected Product Color column. */
    private String materialColorFor(BomLine source, String selectedColor, String productColorId) {
        String colorName = trim(selectedColor);
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

    /**
     * Preserve the exact order in which BOM rows were copied into the MPR.
     *
     * BOM No. is source data, not a sequencing field. Therefore this method
     * intentionally does not sort by BOM No., source row, material, or any
     * other display column. The same insertion order is used by the UI and
     * the final Excel export.
     */
    private void orderLinesForDisplay(MprDocument mpr) {
        // Intentionally no sorting. LinkedHashMap-based duplicate consolidation
        // already retains the first occurrence and its original BOM order.
    }

    private void recalculateMprCalculations(MprDocument mpr) {
        if (mpr == null) return;
        List<MprLine> lines = safeList(mpr.getLines());
        Map<String, CurrencyMaster> currencyByCode = currencyMasterService.currentCurrencyMap();
        for (MprLine line : lines) {
            recalculateLineCalculations(line, currencyByCode);
        }
        recalculateTotalMatAmountPerStyle(lines);
    }

    /**
     * Mirrors the MPR Excel formulas inside the application so the on-screen
     * Sales MPR table and exported workbook show the same calculated values.
     */
    private void recalculateLineCalculations(MprLine line, Map<String, CurrencyMaster> currencyByCode) {
        if (line == null) return;
        // Approved MPR formulas: M=K*L, O=M*N, Q=P*K, T=R+S,
        // V=MAX(0,O+Q-T-U). Do not round intermediate quantities.
        line.setTotalYield(multiplyExact(line.getYield(), line.getLossFactor()));
        line.setMatRequiredQuantity(multiplyExact(line.getTotalYield(), line.getPoQuantity()));
        line.setMatSampleQuantity(multiplyExact(line.getSampleQuantity(), line.getYield()));
        line.setSapStockQuantity(sumAsZero(line.getMcdStock(), line.getCmcdStock()));
        line.setPurchaseQuantity(purchaseQuantity(line));
        snapshotCurrency(line, currencyByCode);
        line.setMatAmountUsd(matAmountUsd(line));
    }

    private void recalculateTotalMatAmountPerStyle(List<MprLine> lines) {
        Map<String, BigDecimal> amountByStyle = new LinkedHashMap<>();
        for (MprLine line : safeList(lines)) {
            if (line == null || blank(line.getStyleColorKey())) continue;
            BigDecimal amount = line.getMatAmountUsd();
            if (amount == null) continue;
            String key = normalize(line.getStyleColorKey());
            amountByStyle.merge(key, amount, BigDecimal::add);
        }
        for (MprLine line : safeList(lines)) {
            if (line == null) continue;
            if (blank(line.getStyleColorKey())) {
                line.setTotalMatAmountPerStyle(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                continue;
            }
            BigDecimal total = amountByStyle.get(normalize(line.getStyleColorKey()));
            line.setTotalMatAmountPerStyle(defaultZero(total).setScale(2, RoundingMode.HALF_UP));
        }
    }

    private BigDecimal purchaseQuantity(MprLine line) {
        if (line == null) return BigDecimal.ZERO;
        BigDecimal value = defaultZero(line.getMatRequiredQuantity())
                .add(defaultZero(line.getMatSampleQuantity()))
                .subtract(defaultZero(line.getSapStockQuantity()))
                .subtract(defaultZero(line.getNonSapStockQuantity()));
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal matAmountUsd(MprLine line) {
        if (line == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = defaultZero(line.getPurchaseQuantity())
                .add(defaultZero(line.getSapStockQuantity()))
                .multiply(defaultZero(line.getMatPriceUsd()));
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumAsZero(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        if (values == null) return total;
        for (BigDecimal value : values) {
            if (value != null) total = total.add(value);
        }
        return total;
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }


    private void snapshotCurrency(MprLine line, Map<String, CurrencyMaster> currencyByCode) {
        if (line == null) return;
        // All numeric calculation inputs/outputs use zero as the safe default.
        // This keeps the API and Excel export free from null-driven formula errors.
        line.setCurrencyMasterId(null);
        line.setRateToVnd(BigDecimal.ZERO);
        line.setMatPriceVnd(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        line.setExchangeRate(BigDecimal.ZERO);
        line.setMatPriceUsd(BigDecimal.ZERO);

        BigDecimal priceWithoutTax = defaultZero(line.getMatPriceWithoutTax());
        if (blank(line.getCurrency()) || currencyByCode == null) return;
        CurrencyMaster currency = currencyByCode.get(normalizeCurrency(line.getCurrency()));
        CurrencyMaster usd = currencyByCode.get("USD");
        if (currency == null || usd == null) return;

        BigDecimal currencyRate = currency.getRateToVnd();
        BigDecimal usdRate = usd.getRateToVnd();
        if (currencyRate == null || usdRate == null || currencyRate.signum() <= 0 || usdRate.signum() <= 0) return;

        line.setCurrencyMasterId(currency.getId());
        line.setRateToVnd(currencyRate);
        line.setMatPriceVnd(priceWithoutTax.multiply(currencyRate).setScale(2, RoundingMode.HALF_UP));

        // Keep the existing system Exchange Rate logic unchanged.
        BigDecimal exchangeRate = usdRate.divide(currencyRate, 8, RoundingMode.HALF_UP);
        line.setExchangeRate(exchangeRate);
        // Approved workbook formula AD = X / AC. A missing/zero input resolves to zero.
        if (exchangeRate.signum() > 0) {
            line.setMatPriceUsd(priceWithoutTax.divide(exchangeRate, MathContext.DECIMAL128));
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

    private BigDecimal multiplyExact(BigDecimal left, BigDecimal right) {
        return defaultZero(left).multiply(defaultZero(right));
    }

    private String materialKey(String materialType, String description, String color, String unit) {
        return materialTypeMatchKey(materialType)
                + "|" + materialTextMatchKey(description)
                + "|" + materialTextMatchKey(color)
                + "|" + materialTextMatchKey(unit);
    }

    /**
     * Use the same text cleanup rules as Master Data. BOM values originate from
     * Excel and can contain NBSP/Unicode spacing that looks identical in the UI
     * but previously produced a different MPR lookup key.
     */
    private String materialTextMatchKey(String value) {
        if (value == null) return "";
        String unicode = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')   // no-break space
                .replace('\u202F', ' ')   // narrow no-break space
                .replace('\u2007', ' ');  // figure space
        String key = MasterDataTextNormalizer.key(unicode);
        return key == null ? "" : key;
    }

    private String materialTypeMatchKey(String value) {
        String unicode = value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFKC);
        String key = MasterDataTextNormalizer.materialGroupKey(unicode);
        return key == null ? "" : key;
    }


    /**
     * Migrates legacy saved selection keys from a readable color name to the
     * exact BOM Product Color id in-memory. The generated MPR lines are the
     * strongest evidence because they already carry productColorId.
     */
    private void backfillSelectionProductColorIds(MprDocument mpr) {
        if (mpr == null || safeList(mpr.getSelections()).isEmpty()) return;

        LinkedHashSet<String> bomIds = safeList(mpr.getSelections()).stream()
                .filter(Objects::nonNull)
                .map(MprSelection::getBomId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, BomDocument> bomById = new LinkedHashMap<>();
        for (BomDocument bom : bomRepository.findAllById(bomIds)) {
            if (bom != null && hasText(bom.getId())) bomById.put(bom.getId(), bom);
        }

        for (MprSelection selection : safeList(mpr.getSelections())) {
            if (selection == null) continue;
            BomDocument bom = bomById.get(selection.getBomId());
            if (bom == null) continue;

            LinkedHashMap<String, String> oldToExactId = new LinkedHashMap<>();
            List<String> exactIds = new ArrayList<>();

            for (String saved : safeList(selection.getColors())) {
                if (blank(saved)) continue;
                BomProductColor resolved = resolveLegacySavedProductColor(mpr, selection, bom, saved);
                String exact = resolved == null || blank(resolved.getId()) ? trim(saved) : trim(resolved.getId());
                oldToExactId.put(saved, exact);
                if (hasText(exact) && !exactIds.contains(exact)) exactIds.add(exact);
            }

            if (oldToExactId.isEmpty()) continue;

            selection.setColors(exactIds);
            selection.setPoQtyByColor(remapSelectionMap(selection.getPoQtyByColor(), oldToExactId));
            selection.setShipToIdsByColor(remapSelectionMap(selection.getShipToIdsByColor(), oldToExactId));
            selection.setShipToQtyByColor(remapSelectionMap(selection.getShipToQtyByColor(), oldToExactId));
            selection.setShipToByColor(remapSelectionMap(selection.getShipToByColor(), oldToExactId));
        }
    }

    private BomProductColor resolveLegacySavedProductColor(
            MprDocument mpr,
            MprSelection selection,
            BomDocument bom,
            String saved
    ) {
        String reference = trim(saved);
        if (blank(reference)) return null;

        List<BomProductColor> productColors = safeList(bom.getProductColors());
        for (BomProductColor item : productColors) {
            if (item != null && reference.equals(trim(item.getId()))) return item;
        }

        List<BomProductColor> sameName = productColors.stream()
                .filter(Objects::nonNull)
                .filter(item -> normalize(reference).equals(normalize(item.getColorName())))
                .collect(Collectors.toCollection(ArrayList::new));
        if (sameName.size() == 1) return sameName.get(0);
        if (sameName.isEmpty()) return null;

        Set<String> candidateIds = sameName.stream()
                .map(BomProductColor::getId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        LinkedHashSet<String> generatedIds = safeList(mpr.getLines()).stream()
                .filter(Objects::nonNull)
                .filter(line -> Objects.equals(selection.getBomId(), line.getBomId()))
                .filter(line -> lineHasBatch(line, selection.getBatchId()))
                .filter(line -> candidateIds.contains(trim(line.getProductColorId())))
                .map(MprLine::getProductColorId)
                .map(this::trim)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (generatedIds.size() == 1) {
            String id = generatedIds.iterator().next();
            return sameName.stream()
                    .filter(item -> Objects.equals(id, trim(item.getId())))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private <T> Map<String, T> remapSelectionMap(
            Map<String, T> source,
            Map<String, String> oldToExactId
    ) {
        LinkedHashMap<String, T> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) return result;

        for (Map.Entry<String, String> mapping : oldToExactId.entrySet()) {
            String oldKey = mapping.getKey();
            String exactKey = mapping.getValue();
            T value = source.containsKey(oldKey) ? source.get(oldKey) : source.get(exactKey);
            if (value != null || source.containsKey(oldKey) || source.containsKey(exactKey)) {
                result.put(exactKey, value);
            }
        }

        // Keep unrelated legacy entries untouched rather than dropping data.
        for (Map.Entry<String, T> entry : source.entrySet()) {
            boolean remapped = oldToExactId.keySet().stream()
                    .anyMatch(key -> normalize(key).equals(normalize(entry.getKey())));
            if (!remapped) result.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private void decorateBomSourceState(MprDocument mpr) {
        if (mpr == null || safeList(mpr.getSelections()).isEmpty()) return;
        LinkedHashSet<String> bomIds = safeList(mpr.getSelections()).stream()
                .filter(Objects::nonNull)
                .map(MprSelection::getBomId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, BomDocument> currentBomById = new LinkedHashMap<>();
        for (BomDocument bom : bomRepository.findAllById(bomIds)) {
            if (bom != null && hasText(bom.getId())) currentBomById.put(bom.getId(), bom);
        }

        for (MprSelection selection : safeList(mpr.getSelections())) {
            if (selection == null) continue;
            BomDocument bom = currentBomById.get(selection.getBomId());
            long snapshotRevision = BomMprSourceRevision.snapshot(selection.getBomSourceRevision());
            if (bom == null) {
                selection.setCurrentBomSourceRevision(snapshotRevision);
                selection.setCurrentBomSourceChangedAt(null);
                selection.setCurrentBomSourceChangedBy("");
                selection.setCurrentBomSourceChangeSummary("Source BOM is no longer available");
                selection.setBomSourceMissing(true);
                selection.setBomSourceChanged(true);
                continue;
            }

            long currentRevision = BomMprSourceRevision.current(bom);
            selection.setCurrentBomSourceRevision(currentRevision);
            selection.setCurrentBomSourceChangedAt(bom.getMprSourceChangedAt());
            selection.setCurrentBomSourceChangedBy(trim(bom.getMprSourceChangedBy()));
            selection.setCurrentBomSourceChangeSummary(trim(bom.getMprSourceChangeSummary()));
            selection.setBomSourceMissing(false);
            boolean changedAfterSnapshot = bom.getMprSourceChangedAt() != null
                    && (selection.getBomSourceChangedAt() == null
                    || bom.getMprSourceChangedAt().isAfter(selection.getBomSourceChangedAt()));
            selection.setBomSourceChanged(currentRevision != snapshotRevision || changedAfterSnapshot);
        }
    }

    private void requireCurrentBomSources(MprDocument mpr) {
        decorateBomSourceState(mpr);
        List<String> changedBoms = safeList(mpr.getSelections()).stream()
                .filter(Objects::nonNull)
                .filter(MprSelection::isBomSourceChanged)
                .map(item -> firstNonBlank(item.getBomNo(), item.getBomName(), item.getBomId()))
                .filter(this::hasText)
                .distinct()
                .toList();
        if (!changedBoms.isEmpty()) {
            throw new OrderBomMprValidationException(
                    "Source BOM changed after this MPR was generated: " + String.join(", ", changedBoms)
                            + ". Update the changed BOM source before confirming MPR."
            );
        }
    }

    private void normalizeLegacyStatus(MprDocument mpr) {
        if (mpr == null) return;
        String status = normalize(mpr.getStatus());
        if (status.isBlank() || "DRAFT".equals(status)) {
            mpr.setStatus(MprDocument.STATUS_IN_PROGRESS);
        }
    }

    private void requireEditable(MprDocument mpr) {
        if (mpr != null && MprDocument.STATUS_COMPLETED.equalsIgnoreCase(mpr.getStatus())) {
            throw new OrderBomMprValidationException(
                    "MPR is completed and locked. Reopen it before changing any MPR data."
            );
        }
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
    private static final class ValidationIssueAccumulator {
        private final String bomId;
        private final String bomNo;
        private final String bomName;
        private final String productColorId;
        private final String productColor;
        private final List<String> allowedShipToIds;
        private final List<String> allowedShipToCodes;
        private final List<String> allowedShipToNames;
        private final LinkedHashSet<String> materials = new LinkedHashSet<>();

        private ValidationIssueAccumulator(
                String bomId,
                String bomNo,
                String bomName,
                String productColorId,
                String productColor,
                List<String> allowedShipToIds,
                List<String> allowedShipToCodes,
                List<String> allowedShipToNames
        ) {
            this.bomId = bomId;
            this.bomNo = bomNo;
            this.bomName = bomName;
            this.productColorId = productColorId;
            this.productColor = productColor;
            this.allowedShipToIds = new ArrayList<>(allowedShipToIds);
            this.allowedShipToCodes = new ArrayList<>(allowedShipToCodes);
            this.allowedShipToNames = new ArrayList<>(allowedShipToNames);
        }
    }

}
