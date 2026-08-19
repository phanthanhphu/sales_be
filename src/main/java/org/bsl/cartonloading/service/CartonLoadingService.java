package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.dto.carton.*;
import org.bsl.cartonloading.dto.barcode.BarcodeAssignmentPageResponse;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeAssignRequest;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeScanRequest;
import org.bsl.cartonloading.enums.CartonScanStatus;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.CartonScanTransaction;
import org.bsl.cartonloading.model.FactoryBarcode;
import org.bsl.cartonloading.model.PackingAllocationLine;
import org.bsl.cartonloading.model.PackingListLine;
import org.bsl.cartonloading.model.PackingOrder;
import org.bsl.cartonloading.model.ScaleStation;
import org.bsl.cartonloading.repository.CartonScanTransactionRepository;
import org.bsl.cartonloading.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.repository.PackingListLineRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class CartonLoadingService {
    private static final List<CartonScanStatus> COUNTED_STATUSES = List.of(
            CartonScanStatus.WAITING_WEIGHT,
            CartonScanStatus.COMPLETED,
            CartonScanStatus.WEIGHT_WARNING
    );
    private static final List<CartonScanStatus> COMPLETED_STATUSES = List.of(
            CartonScanStatus.COMPLETED,
            CartonScanStatus.WEIGHT_WARNING
    );

    private final PackingOrderService orderService;
    private final PackingAllocationLineRepository masterRepository;
    private final PackingListLineRepository packingRepository;
    private final CartonScanTransactionRepository transactionRepository;
    private final ScaleStationService stationService;
    private final FactoryBarcodeService factoryBarcodeService;
    private final MongoTemplate mongoTemplate;
    private final AppSocketPublisher socketPublisher;

    public CartonLoadingService(
            PackingOrderService orderService,
            PackingAllocationLineRepository masterRepository,
            PackingListLineRepository packingRepository,
            CartonScanTransactionRepository transactionRepository,
            ScaleStationService stationService,
            FactoryBarcodeService factoryBarcodeService,
            MongoTemplate mongoTemplate,
            AppSocketPublisher socketPublisher
    ) {
        this.orderService = orderService;
        this.masterRepository = masterRepository;
        this.packingRepository = packingRepository;
        this.transactionRepository = transactionRepository;
        this.stationService = stationService;
        this.factoryBarcodeService = factoryBarcodeService;
        this.mongoTemplate = mongoTemplate;
        this.socketPublisher = socketPublisher;
    }

    /**
     * Builds Carton Master rows from the Packing List. One generated row represents one
     * physical carton, so a unique Factory Barcode can later be assigned 1:1 to that carton.
     *
     * The Factory Barcode pool remains independent. This method only prepares the carton side
     * of the mapping: Factory Barcode <-> Carton <-> Master Data.
     */
    public synchronized CartonPlanGenerationResult generateFromWsp(
            String buyerCode,
            String orderId,
            boolean replace
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        List<PackingAllocationLine> masterLines = masterRepository
                .findByOrderIdAndBuyerCode(order.getId(), buyer)
                .stream()
                .sorted(Comparator.comparing(PackingAllocationLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (masterLines.isEmpty()) {
            return new CartonPlanGenerationResult(false, 0, 0, 0,
                    "Upload the WSP ALLOCATION / Order Items before generating Carton Master data.");
        }

        List<PackingListLine> packingLines = packingRepository
                .findByOrderIdAndBuyerCode(order.getId(), buyer)
                .stream()
                .sorted(Comparator.comparing(PackingListLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (packingLines.isEmpty()) {
            return new CartonPlanGenerationResult(false, masterLines.size(), 0, 0,
                    "Generate or import the Packing List before generating Carton Master data.");
        }

        List<CartonScanTransaction> existing = transactionRepository.findByOrderIdAndBuyerCode(order.getId(), buyer);
        if (!existing.isEmpty() && !replace) {
            return new CartonPlanGenerationResult(false, masterLines.size(), 0, 0,
                    "Carton Master data already exists for this Order.");
        }
        if (replace && existing.stream().anyMatch(this::hasOperationalData)) {
            throw new IllegalArgumentException(
                    "Carton Master data cannot be regenerated because Factory Barcode assignment, scanning, or weighing has already started. "
                            + "Unassign unused barcodes first or create a new Order."
            );
        }
        if (replace) transactionRepository.deleteByOrderIdAndBuyerCode(order.getId(), buyer);

        /*
         * Packing List is the carton-level source of truth. CTNS Qty / carton range determines
         * how many physical cartons are generated. Qty/CTN describes the pieces in each carton;
         * it is NOT the number of child rows.
         */
        Map<String, Integer> parentTotals = new LinkedHashMap<>();
        Map<String, Integer> itemGroupTotals = new LinkedHashMap<>();
        int skipped = 0;
        for (PackingListLine line : packingLines) {
            PackingAllocationLine master = findMasterForPackingLine(masterLines, line);
            BigDecimal qtyPerCarton = resolvedQtyPerCarton(line, master);
            int cartonCount = plannedCartons(line);
            if (!positive(qtyPerCarton) || cartonCount <= 0) {
                skipped++;
                continue;
            }
            parentTotals.merge(cartonParentKey(master, line), cartonCount, Integer::sum);
            itemGroupTotals.merge(itemGroupKey(line.getPoNumber(), line.getArticleNumber()), cartonCount, Integer::sum);
        }

        List<CartonScanTransaction> cartons = new ArrayList<>();
        Map<String, Integer> parentCounters = new HashMap<>();
        Map<String, Integer> itemGroupCounters = new HashMap<>();
        int orderSequence = 0;
        LocalDateTime now = LocalDateTime.now();

        for (PackingListLine line : packingLines) {
            PackingAllocationLine master = findMasterForPackingLine(masterLines, line);
            BigDecimal qtyPerCarton = resolvedQtyPerCarton(line, master);
            int cartonCount = plannedCartons(line);
            if (!positive(qtyPerCarton) || cartonCount <= 0) continue;

            String parentKey = cartonParentKey(master, line);
            int parentTotal = parentTotals.getOrDefault(parentKey, cartonCount);
            String groupKey = itemGroupKey(line.getPoNumber(), line.getArticleNumber());
            int itemTotal = itemGroupTotals.getOrDefault(groupKey, cartonCount);
            BigDecimal remainingPcs = positive(line.getTotalPcs()) ? line.getTotalPcs() : null;
            Integer firstCartonNumber = integerCartonNumber(line.getCartonFrom());

            for (int sequenceInPackingLine = 1; sequenceInPackingLine <= cartonCount; sequenceInPackingLine++) {
                orderSequence++;
                int cartonSequence = parentCounters.merge(parentKey, 1, Integer::sum);
                int itemSequence = itemGroupCounters.merge(groupKey, 1, Integer::sum);
                BigDecimal cartonPcs = resolveCartonPcs(qtyPerCarton, remainingPcs, sequenceInPackingLine, cartonCount);
                if (remainingPcs != null && cartonPcs != null) {
                    remainingPcs = remainingPcs.subtract(cartonPcs).max(BigDecimal.ZERO);
                }

                CartonScanTransaction carton = new CartonScanTransaction();
                carton.setBuyerCode(buyer);
                carton.setOrderId(order.getId());
                carton.setOrderName(order.getOrderName());
                carton.setMasterLineId(master == null ? null : master.getId());
                carton.setPackingLineId(line.getId());
                carton.setPackingLineNo(line.getLineNo());
                carton.setSupplierName(master == null ? null : master.getSupplierName());
                carton.setSupplierNumber(master == null ? null : master.getSupplierNumber());
                carton.setPoNumber(firstText(line.getPoNumber(), master == null ? null : master.getPoNumber()));
                carton.setArticleNumber(firstText(line.getArticleNumber(), master == null ? null : master.getArticleNumber()));
                carton.setStyleNumber(firstText(line.getStyleNumber(), master == null ? null : master.getStyleNumber()));
                carton.setStyle(firstText(line.getStyle(), master == null ? null : master.getStyle()));
                carton.setColor(firstText(line.getColor(), master == null ? null : master.getColor()));
                carton.setSize(firstText(line.getSize(), master == null ? null : master.getSize()));
                carton.setQtyPerCarton(qtyPerCarton);
                carton.setCartonPcs(cartonPcs);
                carton.setCartonSequence(cartonSequence);
                carton.setPlannedCartons(parentTotal);
                carton.setOrderCartonSequence(orderSequence);
                carton.setCartonNumber(firstCartonNumber == null ? orderSequence : firstCartonNumber + sequenceInPackingLine - 1);
                carton.setItemSequence(itemSequence);
                carton.setItemTotal(itemTotal);
                carton.setItemKey(buildItemKey(carton.getPoNumber(), carton.getArticleNumber(), itemSequence, itemTotal));
                carton.setCartonCode(carton.getItemKey());
                carton.setExpectedWeightKg(positive(line.getGrossWeightKg()) ? line.getGrossWeightKg() : null);
                carton.setWeightStatus("NOT_WEIGHED");
                carton.setStatus(CartonScanStatus.PLANNED);
                carton.setUpdatedAt(now);
                cartons.add(carton);
            }
        }

        if (cartons.isEmpty()) {
            return new CartonPlanGenerationResult(false, masterLines.size(), 0, skipped,
                    "No valid physical carton could be generated from the Packing List. Check CTNS Qty and Qty/CTN.");
        }

        transactionRepository.saveAll(cartons);
        orderService.touchFromMaster(order);
        socketPublisher.cartonLoadingChanged("PLAN_GENERATED", order.getId());
        return new CartonPlanGenerationResult(true, masterLines.size(), cartons.size(), skipped,
                "Carton Master generated from Packing List. One row represents one physical carton.");
    }

    public Page<CartonScanTransaction> listCartons(
            String buyerCode,
            String orderId,
            String keyword,
            String status,
            int page,
            int pageSize
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        CartonScanStatus statusFilter = parseStatus(status);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(pageSize, 200));
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Criteria criteria = Criteria.where("buyerCode").is(buyer).and("orderId").is(order.getId());
        if (statusFilter != null) {
            criteria = new Criteria().andOperator(criteria, Criteria.where("status").is(statusFilter));
        }
        Criteria keywordCriteria = cartonKeywordCriteria(keyword);
        if (keywordCriteria != null) {
            criteria = new Criteria().andOperator(criteria, keywordCriteria);
        }

        Query countQuery = Query.query(criteria);
        long total = mongoTemplate.count(countQuery, CartonScanTransaction.class);

        Query dataQuery = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "orderCartonSequence"))
                .skip(pageable.getOffset())
                .limit(pageable.getPageSize());
        List<CartonScanTransaction> rows = mongoTemplate.find(dataQuery, CartonScanTransaction.class);
        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * Returns the physical Carton Master rows linked to one WSP / Order Item row.
     */
    public List<CartonScanTransaction> listCartonsForItem(
            String buyerCode,
            String orderId,
            String masterLineId
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        String itemId = required(masterLineId, "WSP item is required");
        masterRepository.findById(itemId)
                .filter(item -> order.getId().equals(item.getOrderId()) && buyer.equals(item.getBuyerCode()))
                .orElseThrow(() -> new IllegalArgumentException("WSP item not found in the selected Order"));
        return transactionRepository
                .findByOrderIdAndBuyerCodeAndMasterLineIdOrderByCartonSequenceAsc(order.getId(), buyer, itemId);
    }

    /**
     * Returns the generated carton master/child rows used by the packing-line barcode assignment screen.
     * Assignment is deliberately separate from weighing: a Factory Barcode is first mapped to a carton,
     * then the same unique code is scanned later at the warehouse/weight station.
     */
    public BarcodeAssignmentPageResponse listCartonsForBarcodeAssignment(
            String buyerCode,
            String orderId,
            String keyword,
            String assignment,
            int page,
            int pageSize
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(pageSize, 100));
        long offset = (long) safePage * safeSize;

        Criteria base = Criteria.where("buyerCode").is(buyer).and("orderId").is(order.getId());
        Criteria filtered = base;

        Criteria assignmentCriteria = assignmentCriteria(assignment);
        if (assignmentCriteria != null) {
            filtered = new Criteria().andOperator(filtered, assignmentCriteria);
        }

        Criteria keywordCriteria = cartonKeywordCriteria(keyword);
        if (keywordCriteria != null) {
            filtered = new Criteria().andOperator(filtered, keywordCriteria);
        }

        long totalElements = mongoTemplate.count(Query.query(filtered), CartonScanTransaction.class);
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);

        // If the last row on the last page was just assigned/unassigned, keep the response on a valid page.
        int effectivePage = safePage;
        if (totalPages > 0 && effectivePage >= totalPages) {
            effectivePage = totalPages - 1;
            offset = (long) effectivePage * safeSize;
        }

        Query dataQuery = Query.query(filtered)
                .with(Sort.by(Sort.Direction.ASC, "orderCartonSequence"))
                .skip(offset)
                .limit(safeSize);
        List<CartonScanTransaction> content = mongoTemplate.find(dataQuery, CartonScanTransaction.class);

        long totalCartons = mongoTemplate.count(Query.query(base), CartonScanTransaction.class);
        long assignedCount = mongoTemplate.count(
                Query.query(new Criteria().andOperator(base, assignedBarcodeCriteria())),
                CartonScanTransaction.class
        );
        long unassignedCount = Math.max(0L, totalCartons - assignedCount);

        return new BarcodeAssignmentPageResponse(
                content,
                effectivePage,
                safeSize,
                totalElements,
                totalPages,
                totalCartons,
                assignedCount,
                unassignedCount
        );
    }

    public FactoryBarcode checkFactoryBarcodeForAssignment(String buyerCode, String orderId, String barcodeValue) {
        String buyer = requireBuyer(buyerCode);
        orderService.getEntity(buyer, orderId);
        return factoryBarcodeService.requireAvailable(barcodeValue);
    }

    public synchronized CartonScanTransaction assignFactoryBarcode(
            String buyerCode,
            String orderId,
            FactoryBarcodeAssignRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        if (request == null) throw new IllegalArgumentException("Assignment request is required");
        String barcodeValue = required(request.factoryBarcode(), "Factory Barcode is required");
        String cartonId = required(request.cartonId(), "Carton is required");

        CartonScanTransaction existingCarton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(cartonId, order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException("Carton Master record not found in the selected Order"));
        if (existingCarton.getStatus() != CartonScanStatus.PLANNED) {
            throw new IllegalArgumentException("Factory Barcode can only be assigned before weighing starts. Carton is " + existingCarton.getStatus() + ".");
        }
        if (!blank(existingCarton.getFactoryBarcode())) {
            if (barcodeValue.equals(existingCarton.getFactoryBarcode())) return existingCarton;
            throw new IllegalArgumentException("Carton " + existingCarton.getCartonCode() + " already has Factory Barcode " + existingCarton.getFactoryBarcode() + ".");
        }

        FactoryBarcode label = factoryBarcodeService.requireAvailable(barcodeValue);
        LocalDateTime now = LocalDateTime.now();
        Query claimQuery = Query.query(Criteria.where("_id").is(cartonId)
                .and("buyerCode").is(buyer)
                .and("orderId").is(order.getId())
                .and("status").is(CartonScanStatus.PLANNED)
                .and("factoryBarcode").is(null));
        Update claimUpdate = new Update()
                .set("factoryBarcode", label.getBarcode())
                .set("factoryBarcodeAssignedBy", RequestActor.current())
                .set("factoryBarcodeAssignedAt", now)
                .set("updatedAt", now);

        CartonScanTransaction claimed;
        try {
            claimed = mongoTemplate.findAndModify(
                    claimQuery,
                    claimUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    CartonScanTransaction.class
            );
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("Factory Barcode " + label.getBarcode() + " was assigned by another operator.");
        }
        if (claimed == null) {
            throw new IllegalArgumentException("Carton changed before assignment. Refresh the Carton Master list and scan again.");
        }

        try {
            factoryBarcodeService.assignToCarton(label, claimed);
        } catch (RuntimeException ex) {
            Query rollback = Query.query(Criteria.where("_id").is(claimed.getId()).and("factoryBarcode").is(label.getBarcode()));
            mongoTemplate.updateFirst(rollback, new Update()
                    .unset("factoryBarcode")
                    .unset("factoryBarcodeAssignedBy")
                    .unset("factoryBarcodeAssignedAt")
                    .set("updatedAt", LocalDateTime.now()), CartonScanTransaction.class);
            throw ex;
        }

        socketPublisher.cartonLoadingChanged("FACTORY_BARCODE_ASSIGNED", claimed.getId());
        return claimed;
    }

    public synchronized CartonScanTransaction unassignFactoryBarcode(
            String buyerCode,
            String orderId,
            String cartonId
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(required(cartonId, "Carton is required"), order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException("Carton Master record not found in the selected Order"));
        if (carton.getStatus() != CartonScanStatus.PLANNED || carton.getJobId() != null || carton.getWeightKg() != null) {
            throw new IllegalArgumentException("Factory Barcode cannot be unassigned after weighing has started.");
        }
        String barcodeValue = clean(carton.getFactoryBarcode());
        if (barcodeValue == null) return carton;

        carton.setFactoryBarcode(null);
        carton.setFactoryBarcodeAssignedBy(null);
        carton.setFactoryBarcodeAssignedAt(null);
        carton.setUpdatedAt(LocalDateTime.now());
        CartonScanTransaction saved = transactionRepository.save(carton);
        try {
            factoryBarcodeService.releaseAssignment(barcodeValue, saved.getId());
        } catch (RuntimeException ex) {
            saved.setFactoryBarcode(barcodeValue);
            saved.setFactoryBarcodeAssignedBy(RequestActor.current());
            saved.setFactoryBarcodeAssignedAt(LocalDateTime.now());
            transactionRepository.save(saved);
            throw ex;
        }
        socketPublisher.cartonLoadingChanged("FACTORY_BARCODE_UNASSIGNED", saved.getId());
        return saved;
    }

    /**
     * Warehouse/weight-station scan. The Factory Barcode already points to one exact carton, so no
     * QA-code matching or 'first unfinished child' selection is performed here.
     */
    public synchronized CartonScanTransaction scanAssignedFactoryBarcode(
            String buyerCode,
            String orderId,
            FactoryBarcodeScanRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        if (request == null) throw new IllegalArgumentException("Factory Barcode scan request is required");
        boolean manualMode = Boolean.TRUE.equals(request.manualMode()) || blank(request.stationCode());
        ScaleStation station = manualMode ? null : stationService.requireActive(request.stationCode());
        String stationCode = station == null ? null : station.getStationCode();
        String barcodeValue = required(request.factoryBarcode(), "Factory Barcode is required");
        String scanId = clean(request.scanId());

        if (scanId != null) {
            Optional<CartonScanTransaction> repeated = transactionRepository.findByScanId(scanId);
            if (repeated.isPresent()) {
                CartonScanTransaction existing = repeated.get();
                boolean sameStation = stationCode == null ? blank(existing.getStationCode()) : stationCode.equals(existing.getStationCode());
                if (!buyer.equals(existing.getBuyerCode()) || !order.getId().equals(existing.getOrderId()) || !sameStation) {
                    throw new IllegalArgumentException("Scan ID is already used by another station or Order.");
                }
                return existing;
            }
        }

        if (stationCode != null) {
            Optional<CartonScanTransaction> openJob = transactionRepository
                    .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
            if (openJob.isPresent()) {
                throw new IllegalArgumentException("Station " + stationCode + " already has Job "
                        + openJob.get().getJobId() + " waiting for weight.");
            }
        }

        FactoryBarcode label = factoryBarcodeService.requireAssigned(barcodeValue);
        if (!buyer.equals(label.getAssignedBuyerCode()) || !order.getId().equals(label.getAssignedOrderId())) {
            throw new IllegalArgumentException("Factory Barcode belongs to another Buyer/Order: " + label.getBarcode());
        }
        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(label.getAssignedCartonId(), order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException("Assigned carton record was not found for Factory Barcode " + label.getBarcode()));
        if (!label.getBarcode().equals(carton.getFactoryBarcode())) {
            throw new IllegalArgumentException("Factory Barcode mapping is inconsistent. Please contact IT before weighing this carton.");
        }
        if (carton.getStatus() != CartonScanStatus.PLANNED) {
            throw new IllegalArgumentException("Carton " + carton.getCartonCode() + " is already " + carton.getStatus() + ".");
        }

        LocalDateTime now = LocalDateTime.now();
        long jobId = nextCounter("carton_loading_job");
        BigDecimal currentStandardWeight = carton.getExpectedWeightKg();
        if (!blank(carton.getPackingLineId())) {
            Optional<PackingListLine> currentPackingLine = packingRepository.findByIdAndOrderIdAndBuyerCode(
                    carton.getPackingLineId(), order.getId(), buyer);
            if (currentPackingLine.isPresent()) {
                BigDecimal latestGrossWeight = currentPackingLine.get().getGrossWeightKg();
                currentStandardWeight = positive(latestGrossWeight) ? latestGrossWeight : null;
            }
        }
        Query claimQuery = Query.query(Criteria.where("_id").is(carton.getId())
                .and("buyerCode").is(buyer)
                .and("orderId").is(order.getId())
                .and("factoryBarcode").is(label.getBarcode())
                .and("status").is(CartonScanStatus.PLANNED));
        Update claimUpdate = new Update()
                .set("jobId", jobId)
                .set("palletCode", clean(request.palletCode()))
                .set("barcode", label.getBarcode())
                .set("status", CartonScanStatus.WAITING_WEIGHT)
                .set("weightToleranceKg", defaultTolerance(station))
                .set("scannedBy", RequestActor.current())
                .set("scannedAt", now)
                .set("updatedAt", now);
        if (currentStandardWeight == null) claimUpdate.unset("expectedWeightKg"); else claimUpdate.set("expectedWeightKg", currentStandardWeight);
        if (stationCode == null) claimUpdate.unset("stationCode"); else claimUpdate.set("stationCode", stationCode);
        if (scanId != null) claimUpdate.set("scanId", scanId);

        try {
            CartonScanTransaction claimed = mongoTemplate.findAndModify(
                    claimQuery,
                    claimUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    CartonScanTransaction.class
            );
            if (claimed == null) throw new IllegalArgumentException("This carton was already taken by another station. Scan again after refreshing.");
            socketPublisher.cartonLoadingChanged("WAITING_WEIGHT", claimed.getId());
            return claimed;
        } catch (DuplicateKeyException ex) {
            if (scanId != null) {
                Optional<CartonScanTransaction> repeated = transactionRepository.findByScanId(scanId);
                if (repeated.isPresent()) return repeated.get();
            }
            throw new IllegalArgumentException("Factory Barcode scan was submitted more than once or the station is busy.");
        }
    }

    /**
     * Scan-first lookup: the physical labels can be identical, so the barcode identifies the WSP parent item.
     * The matching parent item is shown on the web page; scanning reserves the first unfinished child automatically.
     */
    public CartonItemLookupResponse lookupGeneratedItems(
            String buyerCode,
            String orderId,
            CartonItemLookupRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        String barcode = required(request.barcode(), "Barcode is required");
        String normalized = normalizeBarcode(barcode);
        ParsedQr parsed = parseQr(barcode);

        List<CartonScanTransaction> matched = transactionRepository
                .findByOrderIdAndBuyerCodeOrderByOrderCartonSequenceAsc(order.getId(), buyer)
                .stream()
                .filter(row -> row.getMasterLineId() != null)
                .filter(row -> qrMatchesItem(parsed, normalized, row))
                .toList();

        Map<String, List<CartonScanTransaction>> grouped = new LinkedHashMap<>();
        for (CartonScanTransaction row : matched) {
            grouped.computeIfAbsent(row.getMasterLineId(), ignored -> new ArrayList<>()).add(row);
        }
        List<CartonMasterItemResponse> items = grouped.values().stream()
                .map(this::masterItemSummary)
                .toList();
        String message = items.isEmpty()
                ? "QR/barcode does not match any generated Master Data item in the selected Order."
                : items.size() == 1
                    ? "Item matched. The first unfinished child will be selected automatically when scanned."
                    : "More than one item uses this PO/Article. Select the correct Size/Color item.";
        return new CartonItemLookupResponse(!items.isEmpty(), barcode, normalized, message, items);
    }

    /**
     * Handles one completed Zebra USB HID scan.
     *
     * The QA code identifies a single WSP/Master Data row. Because every physical carton
     * can carry the same QA code, the backend atomically reserves the first remaining
     * PLANNED child carton and immediately creates the PLC waiting Job.
     */
    public synchronized CartonScanTransaction scanNextFromZebra(
            String buyerCode,
            String orderId,
            ZebraScanRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        boolean manualMode = Boolean.TRUE.equals(request.manualMode()) || blank(request.stationCode());
        ScaleStation station = manualMode ? null : stationService.requireActive(request.stationCode());
        String stationCode = station == null ? null : station.getStationCode();
        String barcode = required(request.barcode(), "QA Code is required");
        String selectedMasterLineId = clean(request.masterLineId());
        String scanId = clean(request.scanId());

        /* A browser retry with the same scanId returns the original Job instead of consuming another carton. */
        if (scanId != null) {
            Optional<CartonScanTransaction> repeated = transactionRepository.findByScanId(scanId);
            if (repeated.isPresent()) {
                CartonScanTransaction existing = repeated.get();
                boolean sameStation = stationCode == null
                        ? blank(existing.getStationCode())
                        : stationCode.equals(existing.getStationCode());
                if (!buyer.equals(existing.getBuyerCode())
                        || !order.getId().equals(existing.getOrderId())
                        || !sameStation) {
                    throw new IllegalArgumentException("Scan ID is already used by another station or Order.");
                }
                return existing;
            }
        }

        if (stationCode != null) {
            Optional<CartonScanTransaction> openJob = transactionRepository
                    .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
            if (openJob.isPresent()) {
                throw new IllegalArgumentException("Station " + stationCode + " already has Job "
                        + openJob.get().getJobId() + " waiting for weight.");
            }
        }

        ParsedQr parsed = parseQr(barcode);
        String normalized = normalizeBarcode(barcode);
        List<CartonScanTransaction> matchedRows = transactionRepository
                .findByOrderIdAndBuyerCodeOrderByOrderCartonSequenceAsc(order.getId(), buyer)
                .stream()
                .filter(row -> row.getMasterLineId() != null)
                .filter(row -> selectedMasterLineId == null || selectedMasterLineId.equals(row.getMasterLineId()))
                .filter(row -> qrMatchesItem(parsed, normalized, row))
                .toList();

        if (matchedRows.isEmpty()) {
            if (selectedMasterLineId != null) {
                throw new IllegalArgumentException(
                        "The selected item does not match this QA Code in the selected Order."
                );
            }
            throw new IllegalArgumentException(
                    "QA Code does not match any generated Master Data item in the selected Order."
            );
        }

        Map<String, List<CartonScanTransaction>> availableByMaster = new LinkedHashMap<>();
        for (CartonScanTransaction row : matchedRows) {
            if (row.getStatus() == CartonScanStatus.PLANNED) {
                availableByMaster.computeIfAbsent(row.getMasterLineId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        if (availableByMaster.isEmpty()) {
            throw new IllegalArgumentException("All cartons matching this QA Code have already been scanned.");
        }
        if (availableByMaster.size() > 1) {
            String choices = availableByMaster.values().stream()
                    .map(rows -> rows.get(0))
                    .map(row -> "Size " + (blank(row.getSize()) ? "-" : row.getSize())
                            + " / Color " + (blank(row.getColor()) ? "-" : row.getColor()))
                    .distinct()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("multiple Master Data rows");
            throw new IllegalArgumentException(
                    "QA Code matches more than one unfinished Master Data row (" + choices
                            + "). Select the correct Size/Color item first."
            );
        }

        String masterLineId = availableByMaster.keySet().iterator().next();
        LocalDateTime now = LocalDateTime.now();
        long jobId = nextCounter("carton_loading_job");

        Query claimQuery = Query.query(Criteria.where("buyerCode").is(buyer)
                        .and("orderId").is(order.getId())
                        .and("masterLineId").is(masterLineId)
                        .and("status").is(CartonScanStatus.PLANNED))
                .with(Sort.by(Sort.Direction.ASC, "cartonSequence", "orderCartonSequence"));
        Update claimUpdate = new Update()
                .set("jobId", jobId)
                .set("palletCode", clean(request.palletCode()))
                .set("barcode", barcode.trim())
                .set("status", CartonScanStatus.WAITING_WEIGHT)
                .set("weightToleranceKg", defaultTolerance(station))
                .set("scannedBy", RequestActor.current())
                .set("scannedAt", now)
                .set("updatedAt", now);
        if (stationCode == null) {
            claimUpdate.unset("stationCode");
        } else {
            claimUpdate.set("stationCode", stationCode);
        }
        if (scanId != null) claimUpdate.set("scanId", scanId);

        try {
            CartonScanTransaction claimed = mongoTemplate.findAndModify(
                    claimQuery,
                    claimUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    CartonScanTransaction.class
            );
            if (claimed == null) {
                throw new IllegalArgumentException(
                        "The next carton was taken by another scanner. Scan the QA Code again."
                );
            }
            socketPublisher.cartonLoadingChanged("WAITING_WEIGHT", claimed.getId());
            return claimed;
        } catch (DuplicateKeyException ex) {
            if (scanId != null) {
                Optional<CartonScanTransaction> repeated = transactionRepository.findByScanId(scanId);
                if (repeated.isPresent()) return repeated.get();
            }
            throw new IllegalArgumentException("The QA scan was submitted more than once. Search again when the current child is ready.");
        }
    }

    public synchronized CartonScanTransaction completePlannedCartonManually(
            String buyerCode,
            String orderId,
            String cartonId,
            CartonManualCompleteRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        ScaleStation station = stationService.requireActive(request.stationCode());
        String stationCode = station.getStationCode();

        Optional<CartonScanTransaction> openJob = transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
        if (openJob.isPresent()) {
            throw new IllegalArgumentException("Station " + stationCode + " already has Job "
                    + openJob.get().getJobId() + " waiting for PLC weight.");
        }

        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(cartonId, order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException("Child item record not found"));
        if (carton.getStatus() != CartonScanStatus.PLANNED) {
            throw new IllegalArgumentException("Item " + carton.getCartonCode() + " is already " + carton.getStatus() + ".");
        }

        String barcode = required(request.barcode(), "Barcode is required");
        if (!qrMatchesItem(parseQr(barcode), normalizeBarcode(barcode), carton)) {
            throw new IllegalArgumentException("Scanned QR does not match PO " + carton.getPoNumber() + " / Article " + carton.getArticleNumber());
        }
        BigDecimal weight = requireWeight(request.weightKg(), station);
        String reason = required(request.reason(), "Manual input reason is required");
        LocalDateTime now = LocalDateTime.now();
        carton.setJobId(nextCounter("carton_loading_job"));
        carton.setStationCode(stationCode);
        carton.setPalletCode(clean(request.palletCode()));
        carton.setBarcode(barcode.trim());
        carton.setScannedBy(RequestActor.current());
        carton.setScannedAt(now);
        carton.setWeightToleranceKg(defaultTolerance(station));
        carton.setUpdatedAt(now);
        return completeWeight(
                carton, weight, "MANUAL-" + System.currentTimeMillis(),
                RequestActor.current(), reason, "MANUAL"
        );
    }

    public synchronized CartonScanTransaction scanPlannedCarton(
            String buyerCode,
            String orderId,
            String cartonId,
            CartonPlanScanRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        ScaleStation station = stationService.requireActive(request.stationCode());
        String stationCode = station.getStationCode();

        Optional<CartonScanTransaction> openJob = transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
        if (openJob.isPresent()) {
            throw new IllegalArgumentException("Station " + stationCode + " already has Job "
                    + openJob.get().getJobId() + " waiting for weight.");
        }

        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(cartonId, order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException("Child item record not found"));
        if (carton.getStatus() != CartonScanStatus.PLANNED) {
            throw new IllegalArgumentException("Item " + carton.getCartonCode() + " is already " + carton.getStatus() + ".");
        }

        String barcode = required(request.barcode(), "Barcode is required");
        if (!qrMatchesItem(parseQr(barcode), normalizeBarcode(barcode), carton)) {
            throw new IllegalArgumentException("Scanned QR does not match PO " + carton.getPoNumber() + " / Article " + carton.getArticleNumber());
        }

        LocalDateTime now = LocalDateTime.now();
        carton.setJobId(nextCounter("carton_loading_job"));
        carton.setStationCode(stationCode);
        carton.setPalletCode(clean(request.palletCode()));
        carton.setBarcode(barcode.trim());
        carton.setStatus(CartonScanStatus.WAITING_WEIGHT);
        carton.setWeightToleranceKg(defaultTolerance(station));
        carton.setScannedBy(RequestActor.current());
        carton.setScannedAt(now);
        carton.setUpdatedAt(now);
        try {
            CartonScanTransaction saved = transactionRepository.save(carton);
            socketPublisher.cartonLoadingChanged("WAITING_WEIGHT", saved.getId());
            return saved;
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("Another item Job was created at this station at the same time. Reload and try again.");
        }
    }

    /* Backward-compatible barcode lookup based on the generated Packing List. */
    public CartonLookupResponse lookup(String buyerCode, String orderId, CartonLookupRequest request) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        stationService.requireActive(request.stationCode());
        String barcode = required(request.barcode(), "Barcode is required");
        String normalized = normalizeBarcode(barcode);

        List<PackingListLine> matches = packingRepository.findByOrderIdAndBuyerCode(order.getId(), buyer).stream()
                .filter(line -> barcodeMatches(normalized, line.getArticleNumber()))
                .sorted(Comparator.comparing(PackingListLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        List<CartonCandidateResponse> candidates = matches.stream().map(this::candidate).toList();
        String message = candidates.isEmpty()
                ? "Barcode does not match any Packing List row in the selected Order."
                : candidates.size() == 1
                    ? "Packing List row matched."
                    : "More than one Packing List row uses this Article. Select the correct Size/Color row.";
        return new CartonLookupResponse(!candidates.isEmpty(), barcode, normalized, message, candidates, progress(buyer, orderId));
    }

    /* Backward-compatible transaction creation from a Packing List parent row. */
    public synchronized CartonScanTransaction start(
            String buyerCode,
            String orderId,
            CartonStartRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        ScaleStation station = stationService.requireActive(request.stationCode());
        String stationCode = station.getStationCode();

        Optional<CartonScanTransaction> openJob = transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
        if (openJob.isPresent()) {
            throw new IllegalArgumentException("Station " + stationCode + " already has Job " + openJob.get().getJobId() + " waiting for weight.");
        }

        String barcode = required(request.barcode(), "Barcode is required");
        PackingListLine line = packingRepository.findByIdAndOrderIdAndBuyerCode(
                        required(request.packingLineId(), "Packing List row is required"), order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException("Packing List row not found"));
        if (!barcodeMatches(normalizeBarcode(barcode), line.getArticleNumber())) {
            throw new IllegalArgumentException("Scanned barcode does not match Article " + line.getArticleNumber());
        }

        int planned = plannedCartons(line);
        long alreadyCounted = transactionRepository.countByPackingLineIdAndStatusIn(line.getId(), COUNTED_STATUSES);
        if (alreadyCounted >= planned) {
            throw new IllegalArgumentException("This Packing List row is already complete: " + alreadyCounted + "/" + planned + " cartons.");
        }

        LocalDateTime now = LocalDateTime.now();
        CartonScanTransaction entity = new CartonScanTransaction();
        entity.setJobId(nextCounter("carton_loading_job"));
        entity.setBuyerCode(buyer);
        entity.setOrderId(order.getId());
        entity.setOrderName(order.getOrderName());
        entity.setPackingLineId(line.getId());
        entity.setPackingLineNo(line.getLineNo());
        entity.setStationCode(stationCode);
        entity.setPalletCode(clean(request.palletCode()));
        entity.setBarcode(barcode.trim());
        entity.setPoNumber(line.getPoNumber());
        entity.setArticleNumber(line.getArticleNumber());
        entity.setStyleNumber(line.getStyleNumber());
        entity.setStyle(line.getStyle());
        entity.setColor(line.getColor());
        entity.setSize(line.getSize());
        entity.setQtyPerCarton(line.getQtyPerCarton());
        entity.setCartonPcs(line.getQtyPerCarton());
        long allocatedSequence = nextCounter("carton_line_sequence_" + line.getId());
        if (allocatedSequence > planned) {
            throw new IllegalArgumentException("This Packing List row has no remaining carton slot: " + alreadyCounted + "/" + planned + ".");
        }
        entity.setCartonSequence(Math.toIntExact(allocatedSequence));
        entity.setPlannedCartons(planned);
        entity.setItemSequence(Math.toIntExact(allocatedSequence));
        entity.setItemTotal(planned);
        entity.setItemKey(buildItemKey(line.getPoNumber(), line.getArticleNumber(), Math.toIntExact(allocatedSequence), planned));
        entity.setCartonCode(entity.getItemKey());
        entity.setExpectedWeightKg(line.getGrossWeightKg());
        entity.setWeightToleranceKg(defaultTolerance(station));
        entity.setWeightStatus("NOT_WEIGHED");
        entity.setStatus(CartonScanStatus.WAITING_WEIGHT);
        entity.setScannedBy(RequestActor.current());
        entity.setScannedAt(now);
        entity.setUpdatedAt(now);

        try {
            CartonScanTransaction saved = transactionRepository.save(entity);
            socketPublisher.cartonLoadingChanged("WAITING_WEIGHT", saved.getId());
            return saved;
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("Another carton Job was created at this station at the same time. Reload the station and try again.");
        }
    }

    public CartonScanTransaction current(String buyerCode, String stationCode) {
        String buyer = requireBuyer(buyerCode);
        return transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(normalizeStation(stationCode), CartonScanStatus.WAITING_WEIGHT)
                .filter(transaction -> buyer.equals(transaction.getBuyerCode()))
                .orElse(null);
    }

    public PlcJobResponse currentPlcJob(String stationCode) {
        ScaleStation station = stationService.requireActive(stationCode);
        Optional<CartonScanTransaction> open = transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(station.getStationCode(), CartonScanStatus.WAITING_WEIGHT);
        return open.map(job -> new PlcJobResponse(
                        station.getStationCode(), true, job.getJobId(), job.getId(), job.getArticleNumber(),
                        job.getCartonSequence(), job.getPlannedCartons(), station.getMinimumWeightKg()))
                .orElseGet(() -> new PlcJobResponse(
                        station.getStationCode(), false, null, null, null, null, null, station.getMinimumWeightKg()));
    }

    public CartonScanTransaction get(String buyerCode, String transactionId) {
        String buyer = requireBuyer(buyerCode);
        return transactionRepository.findByIdAndBuyerCode(transactionId, buyer)
                .orElseThrow(() -> new IllegalArgumentException("Item transaction not found"));
    }

    public CartonScanTransaction receivePlcWeight(PlcWeightRequest request) {
        String stationCode = normalizeStation(request.stationCode());
        ScaleStation station = stationService.requireActive(stationCode);
        stationService.markOnline(stationCode, "Weight data received");
        if (!Boolean.TRUE.equals(request.stable())) {
            throw new IllegalArgumentException("Scale value is not stable yet");
        }
        BigDecimal weight = requireWeight(request.weightKg(), station);

        CartonScanTransaction transaction;
        if (request.jobId() != null) {
            transaction = transactionRepository.findByJobId(request.jobId())
                    .orElseThrow(() -> new IllegalArgumentException("PLC Job not found: " + request.jobId()));
            if (!stationCode.equals(transaction.getStationCode())) {
                throw new IllegalArgumentException("PLC Job does not belong to station " + stationCode);
            }
            if (transaction.getStatus() != CartonScanStatus.WAITING_WEIGHT) {
                if (transaction.getWeightKg() != null) return transaction;
                throw new IllegalArgumentException("PLC Job is no longer waiting for weight");
            }
        } else {
            transaction = transactionRepository
                    .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT)
                    .orElseThrow(() -> new IllegalArgumentException("No item is waiting at station " + stationCode));
        }
        return completeWeight(transaction, weight, request.messageId(), "PLC_GATEWAY", null, "PLC");
    }

    public CartonScanTransaction manualWeight(String buyerCode, String transactionId, ManualWeightRequest request) {
        CartonScanTransaction transaction = get(buyerCode, transactionId);
        if (transaction.getStatus() != CartonScanStatus.WAITING_WEIGHT) {
            throw new IllegalArgumentException("Transaction is not waiting for weight");
        }
        ScaleStation station = blank(transaction.getStationCode())
                ? null
                : stationService.requireActive(transaction.getStationCode());
        BigDecimal weight = station == null
                ? requirePositiveWeight(request.weightKg())
                : requireWeight(request.weightKg(), station);
        String reason = required(request.reason(), "Manual input reason is required");
        return completeWeight(transaction, weight, "MANUAL-" + System.currentTimeMillis(), RequestActor.current(), reason, "MANUAL");
    }

    public CartonProgressResponse progress(String buyerCode, String orderId) {
        String buyer = requireBuyer(buyerCode);
        orderService.getEntity(buyer, orderId);
        List<CartonScanTransaction> transactions = transactionRepository.findByOrderIdAndBuyerCode(orderId, buyer);

        long planned;
        if (!transactions.isEmpty()) {
            planned = transactions.stream().filter(t -> t.getStatus() != CartonScanStatus.CANCELLED).count();
        } else {
            List<PackingListLine> lines = packingRepository.findByOrderIdAndBuyerCode(orderId, buyer);
            planned = lines.stream().mapToLong(this::plannedCartons).sum();
        }
        long completed = transactions.stream().filter(t -> t.getStatus() == CartonScanStatus.COMPLETED).count();
        long warning = transactions.stream().filter(t -> t.getStatus() == CartonScanStatus.WEIGHT_WARNING).count();
        long waiting = transactions.stream().filter(t -> t.getStatus() == CartonScanStatus.WAITING_WEIGHT).count();
        BigDecimal totalWeight = transactions.stream()
                .filter(t -> COMPLETED_STATUSES.contains(t.getStatus()))
                .map(CartonScanTransaction::getWeightKg)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long remaining = Math.max(0, planned - completed - warning - waiting);
        return new CartonProgressResponse(orderId, planned, completed, waiting, warning, remaining, totalWeight);
    }

    public List<CartonScanTransaction> recent(String buyerCode, String orderId) {
        String buyer = requireBuyer(buyerCode);
        orderService.getEntity(buyer, orderId);
        return transactionRepository.findTop20ByOrderIdAndBuyerCodeOrderByScannedAtDesc(orderId, buyer).stream()
                .filter(item -> item.getScannedAt() != null)
                .toList();
    }

    private CartonScanTransaction completeWeight(
            CartonScanTransaction transaction,
            BigDecimal weight,
            String messageId,
            String actor,
            String manualReason,
            String weightSource
    ) {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal actual = weight.setScale(3, RoundingMode.HALF_UP);
        transaction.setWeightKg(actual);
        transaction.setPlcMessageId(clean(messageId));
        transaction.setManualReason(clean(manualReason));
        transaction.setWeightSource(clean(weightSource));
        applyWeightStatus(transaction, actual);
        transaction.setWeighedBy(actor);
        transaction.setWeighedAt(now);
        transaction.setUpdatedAt(now);
        CartonScanTransaction saved = transactionRepository.save(transaction);
        socketPublisher.cartonLoadingChanged(saved.getStatus().name(), saved.getId());
        return saved;
    }

    private CartonMasterItemResponse masterItemSummary(List<CartonScanTransaction> rows) {
        CartonScanTransaction first = rows.get(0);
        long notScanned = rows.stream().filter(row -> row.getStatus() == CartonScanStatus.PLANNED).count();
        long waiting = rows.stream().filter(row -> row.getStatus() == CartonScanStatus.WAITING_WEIGHT).count();
        long completed = rows.stream().filter(row -> COMPLETED_STATUSES.contains(row.getStatus())).count();
        BigDecimal totalWeight = rows.stream()
                .filter(row -> COMPLETED_STATUSES.contains(row.getStatus()))
                .map(CartonScanTransaction::getWeightKg)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartonMasterItemResponse(
                first.getMasterLineId(), first.getPackingLineNo(), first.getSupplierNumber(), first.getPoNumber(), first.getArticleNumber(),
                first.getStyleNumber(), first.getStyle(), first.getColor(), first.getSize(), first.getQtyPerCarton(),
                rows.size(), notScanned, waiting, completed, totalWeight,
                rows.get(0).getItemKey(), rows.get(rows.size() - 1).getItemKey()
        );
    }

    private CartonCandidateResponse candidate(PackingListLine line) {
        long counted = transactionRepository.countByPackingLineIdAndStatusIn(line.getId(), COUNTED_STATUSES);
        long waiting = transactionRepository.countByPackingLineIdAndStatusIn(line.getId(), List.of(CartonScanStatus.WAITING_WEIGHT));
        long completed = Math.max(0, counted - waiting);
        return new CartonCandidateResponse(
                line.getId(), line.getLineNo(), line.getPoNumber(), line.getArticleNumber(),
                line.getStyleNumber(), line.getStyle(), line.getColor(), line.getSize(),
                line.getQtyPerCarton(), plannedCartons(line), completed, waiting
        );
    }

    private PackingAllocationLine findMasterForPackingLine(
            List<PackingAllocationLine> masterLines,
            PackingListLine line
    ) {
        if (masterLines == null || line == null) return null;
        return masterLines.stream()
                .filter(master -> blank(line.getPoNumber()) || sameKey(line.getPoNumber(), master.getPoNumber()))
                .filter(master -> blank(line.getArticleNumber()) || sameKey(line.getArticleNumber(), master.getArticleNumber()))
                .filter(master -> blank(line.getStyleNumber()) || blank(master.getStyleNumber()) || sameKey(line.getStyleNumber(), master.getStyleNumber()))
                .filter(master -> blank(line.getStyle()) || blank(master.getStyle()) || sameKey(line.getStyle(), master.getStyle()))
                .filter(master -> blank(line.getColor()) || blank(master.getColor()) || sameKey(line.getColor(), master.getColor()))
                .filter(master -> blank(line.getSize()) || blank(master.getSize()) || sameKey(line.getSize(), master.getSize()))
                .sorted(Comparator.comparing(PackingAllocationLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .findFirst()
                .orElse(null);
    }

    private BigDecimal resolvedQtyPerCarton(PackingListLine line, PackingAllocationLine master) {
        if (line != null && positive(line.getQtyPerCarton())) return line.getQtyPerCarton();
        return master != null && positive(master.getQtyPerCarton()) ? master.getQtyPerCarton() : null;
    }

    private String cartonParentKey(PackingAllocationLine master, PackingListLine line) {
        if (master != null && !blank(master.getId())) return "MASTER:" + master.getId();
        return "PACKING:" + (line == null || blank(line.getId()) ? String.valueOf(line == null ? 0 : line.getLineNo()) : line.getId());
    }

    private Integer integerCartonNumber(BigDecimal value) {
        if (value == null) return null;
        try {
            return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException ex) {
            return value.setScale(0, RoundingMode.HALF_UP).intValue();
        }
    }

    private String firstText(String primary, String fallback) {
        String value = clean(primary);
        return value == null ? clean(fallback) : value;
    }

    private int plannedCartons(PackingListLine line) {
        if (line.getCartonsQty() != null && line.getCartonsQty().signum() > 0) {
            return Math.max(1, line.getCartonsQty().setScale(0, RoundingMode.HALF_UP).intValue());
        }
        if (line.getCartonFrom() != null && line.getCartonTo() != null) {
            BigDecimal calculated = line.getCartonTo().subtract(line.getCartonFrom()).add(BigDecimal.ONE);
            if (calculated.signum() > 0) return Math.max(1, calculated.setScale(0, RoundingMode.HALF_UP).intValue());
        }
        return 1;
    }

    private BigDecimal resolveCartonPcs(
            BigDecimal qtyPerCarton,
            BigDecimal remainingPcs,
            int sequence,
            int totalCartons
    ) {
        if (remainingPcs != null && remainingPcs.signum() > 0) {
            if (qtyPerCarton != null && qtyPerCarton.signum() > 0 && sequence < totalCartons) {
                return remainingPcs.min(qtyPerCarton);
            }
            return remainingPcs;
        }
        return qtyPerCarton;
    }

    private String itemGroupKey(String poNumber, String articleNumber) {
        return keyToken(poNumber) + "|" + keyToken(articleNumber);
    }

    private String buildItemKey(String poNumber, String articleNumber, int sequence, int total) {
        int width = Math.max(3, String.valueOf(Math.max(1, total)).length());
        return keyToken(poNumber) + "-" + keyToken(articleNumber) + "-"
                + String.format(Locale.ROOT, "%0" + width + "d", sequence);
    }

    private String keyToken(String value) {
        String token = value == null ? "NA" : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return token.isEmpty() ? "NA" : token;
    }

    private BigDecimal findExpectedItemWeight(List<PackingListLine> packingLines, PackingAllocationLine master) {
        return packingLines.stream()
                .filter(line -> sameKey(line.getPoNumber(), master.getPoNumber()))
                .filter(line -> sameKey(line.getArticleNumber(), master.getArticleNumber()))
                .filter(line -> blank(master.getSize()) || blank(line.getSize()) || sameKey(line.getSize(), master.getSize()))
                .filter(line -> blank(master.getColor()) || blank(line.getColor()) || sameKey(line.getColor(), master.getColor()))
                .map(line -> {
                    if (positive(line.getNetWeightKg()) && positive(line.getTotalPcs())) {
                        return line.getNetWeightKg().divide(line.getTotalPcs(), 3, RoundingMode.HALF_UP);
                    }
                    if (positive(line.getGrossWeightKg()) && positive(line.getQtyPerCarton())) {
                        return line.getGrossWeightKg().divide(line.getQtyPerCarton(), 3, RoundingMode.HALF_UP);
                    }
                    return null;
                })
                .filter(this::positive)
                .findFirst()
                .orElse(null);
    }

    private boolean sameKey(String left, String right) {
        return keyToken(left).equals(keyToken(right));
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private BigDecimal defaultTolerance(ScaleStation station) {
        BigDecimal value = station == null ? null : station.getStabilityToleranceKg();
        return value == null || value.signum() < 0 ? new BigDecimal("0.050") : value;
    }

    private void applyWeightStatus(CartonScanTransaction transaction, BigDecimal actual) {
        BigDecimal expected = transaction.getExpectedWeightKg();
        if (expected == null || expected.signum() <= 0) {
            transaction.setWeightDifferenceKg(null);
            transaction.setWeightStatus("NO_STANDARD");
            transaction.setStatus(CartonScanStatus.COMPLETED);
            transaction.setWarningMessage(null);
            return;
        }
        BigDecimal difference = actual.subtract(expected).setScale(3, RoundingMode.HALF_UP);
        BigDecimal tolerance = transaction.getWeightToleranceKg();
        if (tolerance == null || tolerance.signum() < 0) tolerance = new BigDecimal("0.050");
        transaction.setWeightDifferenceKg(difference);
        if (difference.abs().compareTo(tolerance) <= 0) {
            transaction.setWeightStatus("OK");
            transaction.setStatus(CartonScanStatus.COMPLETED);
            transaction.setWarningMessage(null);
        } else if (difference.signum() < 0) {
            transaction.setWeightStatus("UNDER");
            transaction.setStatus(CartonScanStatus.WEIGHT_WARNING);
            transaction.setWarningMessage("Under by " + difference.abs().toPlainString() + " kg");
        } else {
            transaction.setWeightStatus("OVER");
            transaction.setStatus(CartonScanStatus.WEIGHT_WARNING);
            transaction.setWarningMessage("Over by " + difference.toPlainString() + " kg");
        }
    }

    private boolean qrMatchesItem(ParsedQr parsed, String normalizedBarcode, CartonScanTransaction row) {
        if (parsed.hasBusinessData()) {
            if (parsed.poNumber() != null && !sameKey(parsed.poNumber(), row.getPoNumber())) return false;
            if (parsed.articleNumber() != null && !sameKey(parsed.articleNumber(), row.getArticleNumber())) return false;
            if (parsed.supplierNumber() != null && !sameKey(parsed.supplierNumber(), row.getSupplierNumber())) return false;
            if (parsed.quantity() != null && row.getQtyPerCarton() != null
                    && parsed.quantity().compareTo(row.getQtyPerCarton()) != 0) return false;
            return parsed.poNumber() != null || parsed.articleNumber() != null;
        }
        return barcodeMatches(normalizedBarcode, row.getArticleNumber())
                || barcodeMatches(normalizedBarcode, row.getPoNumber())
                || normalizeBarcode(row.getItemKey()).equals(normalizedBarcode);
    }

    private ParsedQr parseQr(String raw) {
        String value = raw == null ? "" : raw.trim();

        /*
         * Actual Engelbert Strauss carton QR format from the supplied label:
         * 0000000000008589132-0000000040-0001571490-00000000000000000000
         * ARTICLE (19) - QTY (10) - PO (10) - reserved (20)
         */
        if (value.matches("\\d{19}-\\d{10}-\\d{10}-\\d{20}")) {
            String[] parts = value.split("-");
            return new ParsedQr(
                    null,
                    stripLeadingZeros(parts[2]),
                    stripLeadingZeros(parts[0]),
                    decimal(stripLeadingZeros(parts[1]))
            );
        }

        Map<String, String> fields = new HashMap<>();
        for (String token : value.split("[|;\n\r]+")) {
            String[] pair = token.split("[:=]", 2);
            if (pair.length == 2) fields.put(normalizeLabel(pair[0]), pair[1].trim());
        }
        String supplier = first(fields, "SUPPLIERNO", "SUPPLIER", "ESSUPPLIER", "LIEFERANT");
        String po = first(fields, "ORDERNO", "ORDER", "PONO", "PO", "ESPO", "AUFTRAGSNR", "AUFTRAG");
        String article = first(fields, "ARTICLENO", "ARTICLE", "ART", "ESARTICLE", "ARTIKELNR", "ARTIKEL");
        BigDecimal quantity = decimal(first(fields, "QUANTITY", "QTY", "ANZAHL", "QTYPERCTN"));
        if (supplier == null && po == null && article == null && value.contains("|")) {
            String[] parts = value.split("\\|");
            if (parts.length >= 4 && parts[0].trim().matches("[A-Za-z0-9._/-]+")) {
                supplier = parts[0].trim();
                po = parts[1].trim();
                article = parts[2].trim();
                quantity = decimal(parts[3]);
            }
        }
        return new ParsedQr(supplier, po, article, quantity);
    }


    private String stripLeadingZeros(String value) {
        if (value == null) return null;
        String stripped = value.trim().replaceFirst("^0+(?!$)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.replace(",", ".").replaceAll("[^0-9.-]", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ParsedQr(String supplierNumber, String poNumber, String articleNumber, BigDecimal quantity) {
        boolean hasBusinessData() {
            return supplierNumber != null || poNumber != null || articleNumber != null || quantity != null;
        }
    }

    private boolean hasOperationalData(CartonScanTransaction row) {
        return row.getStatus() == CartonScanStatus.WAITING_WEIGHT
                || row.getStatus() == CartonScanStatus.COMPLETED
                || row.getStatus() == CartonScanStatus.WEIGHT_WARNING
                || row.getJobId() != null
                || row.getWeightKg() != null
                || !blank(row.getFactoryBarcode());
    }

    private Criteria assignmentCriteria(String value) {
        String clean = clean(value);
        if (clean == null || "ALL".equalsIgnoreCase(clean)) return null;
        if ("ASSIGNED".equalsIgnoreCase(clean)) return assignedBarcodeCriteria();
        if ("UNASSIGNED".equalsIgnoreCase(clean)) return unassignedBarcodeCriteria();
        throw new IllegalArgumentException("Unsupported assignment filter: " + value);
    }

    private Criteria assignedBarcodeCriteria() {
        return new Criteria().andOperator(
                Criteria.where("factoryBarcode").exists(true),
                Criteria.where("factoryBarcode").ne(null),
                Criteria.where("factoryBarcode").ne("")
        );
    }

    private Criteria unassignedBarcodeCriteria() {
        return new Criteria().orOperator(
                Criteria.where("factoryBarcode").exists(false),
                Criteria.where("factoryBarcode").is(null),
                Criteria.where("factoryBarcode").is("")
        );
    }

    /**
     * Builds a MongoDB-side keyword filter so large Orders are never loaded into JVM memory merely
     * to search them. The Order/Buyer equality criteria are applied separately and use the compound
     * Order indexes before this filter is evaluated.
     */
    private Criteria cartonKeywordCriteria(String value) {
        String clean = clean(value);
        if (clean == null) return null;
        Pattern pattern = Pattern.compile(Pattern.quote(clean), Pattern.CASE_INSENSITIVE);
        List<Criteria> alternatives = new ArrayList<>();
        for (String field : List.of(
                "factoryBarcode",
                "cartonCode",
                "itemKey",
                "poNumber",
                "articleNumber",
                "styleNumber",
                "style",
                "color",
                "size",
                "supplierNumber"
        )) {
            alternatives.add(Criteria.where(field).regex(pattern));
        }
        try {
            int number = Integer.parseInt(clean);
            alternatives.add(Criteria.where("cartonNumber").is(number));
            alternatives.add(Criteria.where("cartonSequence").is(number));
            alternatives.add(Criteria.where("orderCartonSequence").is(number));
        } catch (NumberFormatException ignored) {
            // Normal text search.
        }
        return new Criteria().orOperator(alternatives.toArray(new Criteria[0]));
    }

    private CartonScanStatus parseStatus(String value) {
        String clean = clean(value);
        if (clean == null || "ALL".equalsIgnoreCase(clean)) return null;
        try {
            return CartonScanStatus.valueOf(clean.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported item status: " + value);
        }
    }

    private boolean matchesCartonKeyword(CartonScanTransaction row, String keyword) {
        return containsIgnoreCase(row.getItemKey(), keyword)
                || containsIgnoreCase(row.getCartonCode(), keyword)
                || (row.getCartonNumber() != null && String.valueOf(row.getCartonNumber()).contains(keyword))
                || containsIgnoreCase(row.getFactoryBarcode(), keyword)
                || containsIgnoreCase(row.getPoNumber(), keyword)
                || containsIgnoreCase(row.getArticleNumber(), keyword)
                || containsIgnoreCase(row.getStyleNumber(), keyword)
                || containsIgnoreCase(row.getStyle(), keyword)
                || containsIgnoreCase(row.getColor(), keyword)
                || containsIgnoreCase(row.getSize(), keyword);
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(keyword);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal requirePositiveWeight(BigDecimal weight) {
        if (weight == null || weight.signum() <= 0) {
            throw new IllegalArgumentException("Weight must be greater than 0");
        }
        return weight;
    }

    private BigDecimal requireWeight(BigDecimal weight, ScaleStation station) {
        if (weight == null || weight.signum() <= 0) throw new IllegalArgumentException("Weight must be greater than 0");
        BigDecimal minimum = station.getMinimumWeightKg() == null ? new BigDecimal("0.50") : station.getMinimumWeightKg();
        if (weight.compareTo(minimum) < 0) {
            throw new IllegalArgumentException("Weight is below station minimum " + minimum + " kg");
        }
        return weight;
    }

    private long nextCounter(String counterId) {
        Query query = Query.query(Criteria.where("_id").is(counterId));
        Update update = new Update().inc("value", 1L);
        org.bson.Document counter = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                org.bson.Document.class,
                "system_counters"
        );
        Number value = counter == null ? null : (Number) counter.get("value");
        if (value == null) throw new IllegalStateException("Unable to allocate carton Job ID");
        return value.longValue();
    }

    private boolean barcodeMatches(String normalizedBarcode, String articleNumber) {
        String article = normalizeBarcode(articleNumber);
        if (article.isEmpty() || normalizedBarcode.isEmpty()) return false;
        return normalizedBarcode.equals(article)
                || normalizedBarcode.endsWith(article)
                || tokenizedContains(normalizedBarcode, article);
    }

    private boolean tokenizedContains(String barcode, String article) {
        if (barcode.length() < article.length()) return false;
        return barcode.contains("ARTICLE" + article) || barcode.contains("ART" + article);
    }

    private String normalizeBarcode(String value) {
        if (value == null) return "";
        String clean = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (clean.matches("\\d+")) clean = clean.replaceFirst("^0+(?!$)", "");
        return clean;
    }

    private String requireBuyer(String value) {
        String buyer = BuyerAccess.normalize(value);
        if (buyer.isEmpty()) throw new IllegalArgumentException("Unsupported Buyer: " + value);
        return buyer;
    }

    private String normalizeStation(String value) {
        return required(value, "Station code is required").trim().toUpperCase(Locale.ROOT);
    }

    private String required(String value, String message) {
        String clean = clean(value);
        if (clean == null) throw new IllegalArgumentException(message);
        return clean;
    }

    private String clean(String value) {
        if (value == null) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.isEmpty() ? null : clean;
    }
}
