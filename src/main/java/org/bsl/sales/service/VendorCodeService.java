package org.bsl.sales.service;

import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.ImportRowError;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.dto.VendorCodeRequest;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.VendorCode;
import org.bsl.sales.repository.MatInfoRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.bsl.sales.repository.VendorCodeRepository;
import org.bsl.sales.security.BuyerAccessService;
import org.bsl.sales.support.BuyerKeys;
import org.bsl.sales.support.ImportCandidate;
import org.bsl.sales.support.MasterDataBeanValidator;
import org.bsl.sales.support.MasterDataEditWorkbookExporter;
import org.bsl.sales.support.MasterDataExcelSupport;
import org.bsl.sales.support.MasterDataTextNormalizer;
import org.bsl.sales.support.NewestFirstSort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class VendorCodeService {

    private static final String MASTER_DATA_NAME = "VENDER CODE";
    private static final String LEGACY_MASTER_DATA_NAME = "VENDOR CODE";
    private static final String MASTER_KEY_PREFIX = "VC";
    private static final String SEQUENCE_NAME = "vendor_code";
    private static final String AUTO_CREATED_REMARK = "Auto-created from MAT_INFO. Please complete Vendor Code, Vendor Name and Mat Charger.";
    private static final String BUYER_KEY_SEPARATOR = "\u001F";

    private final VendorCodeRepository vendorCodeRepository;
    private final MatInfoRepository matInfoRepository;
    private final MprDocumentRepository mprDocumentRepository;
    private final MasterDataBeanValidator beanValidator;
    private final MasterDataExcelSupport excelSupport;
    private final MongoTemplate mongoTemplate;
    private final MasterDataSequenceService sequenceService;
    private final BuyerAccessService buyerAccess;
    private volatile boolean masterKeysBackfilled;
    private volatile boolean buyerScopeBackfilled;

    public VendorCodeService(
            VendorCodeRepository vendorCodeRepository,
            MatInfoRepository matInfoRepository,
            MprDocumentRepository mprDocumentRepository,
            MasterDataBeanValidator beanValidator,
            MasterDataExcelSupport excelSupport,
            MongoTemplate mongoTemplate,
            MasterDataSequenceService sequenceService,
            BuyerAccessService buyerAccess
    ) {
        this.vendorCodeRepository = vendorCodeRepository;
        this.matInfoRepository = matInfoRepository;
        this.mprDocumentRepository = mprDocumentRepository;
        this.beanValidator = beanValidator;
        this.excelSupport = excelSupport;
        this.mongoTemplate = mongoTemplate;
        this.sequenceService = sequenceService;
        this.buyerAccess = buyerAccess;
    }

    @PostConstruct
    public void initializeBuyerScope() {
        backfillLegacyBuyerScope();
    }

    public VendorCode create(String buyerKey, VendorCodeRequest request) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        String rawKey = requireSupplierKey(request);
        String scopedKey = scopedSupplierKey(buyer, rawKey);
        if (vendorCodeRepository.existsByShortNameSupplierKey(scopedKey)) {
            throw new MasterDataConflictException("Short name supplier already exists for Buyer " + buyer + ": " + request.getShortNameSupplier());
        }
        VendorCode entity = new VendorCode();
        entity.setBuyerKey(buyer);
        apply(entity, request);
        entity.setMasterKey(nextMasterKey());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return vendorCodeRepository.save(entity);
    }

    public Page<VendorCode> list(
            String buyerKey,
            String masterKey,
            String shortNameSupplier,
            String vendorCode,
            String vendorName,
            String matCharger,
            int page,
            int size
    ) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        backfillMissingMasterKeys();
        Pageable pageable = toPageable(page, size);
        Query query = new Query(Criteria.where("buyerKey").is(buyer));
        addContainsFilter(query, "masterKey", masterKey);
        addContainsFilter(query, "shortNameSupplier", shortNameSupplier);
        addContainsFilter(query, "vendorCode", vendorCode);
        addContainsFilter(query, "vendorName", vendorName);
        addContainsFilter(query, "matCharger", matCharger);
        long total = mongoTemplate.count(query, VendorCode.class);
        query.with(NewestFirstSort.mongo());
        query.skip(pageable.getOffset()).limit(pageable.getPageSize());
        return new PageImpl<>(mongoTemplate.find(query, VendorCode.class), pageable, total);
    }

    public Page<VendorCode> list(String buyerKey, String keyword, int page, int size) {
        String clean = MasterDataTextNormalizer.trimToNull(keyword);
        if (clean == null) return list(buyerKey, null, null, null, null, null, page, size);
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        backfillMissingMasterKeys();
        Pageable pageable = toPageable(page, size);
        Pattern pattern = containsPattern(clean);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("buyerKey").is(buyer),
                new Criteria().orOperator(
                        Criteria.where("masterKey").regex(pattern),
                        Criteria.where("shortNameSupplier").regex(pattern),
                        Criteria.where("vendorCode").regex(pattern),
                        Criteria.where("vendorName").regex(pattern),
                        Criteria.where("matCharger").regex(pattern)
                )
        ));
        long total = mongoTemplate.count(query, VendorCode.class);
        query.with(NewestFirstSort.mongo());
        query.skip(pageable.getOffset()).limit(pageable.getPageSize());
        return new PageImpl<>(mongoTemplate.find(query, VendorCode.class), pageable, total);
    }

    /** Lightweight lookup used by MAT_INFO and MPR Vendor Code selectors. */
    public List<VendorCode> options(String buyerKey, String keyword, int limit) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        int safeLimit = Math.max(1, Math.min(limit, 5000));
        Query query = new Query(Criteria.where("buyerKey").is(buyer));
        String clean = MasterDataTextNormalizer.trimToNull(keyword);
        if (clean != null) {
            Pattern pattern = containsPattern(clean);
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("masterKey").regex(pattern),
                    Criteria.where("shortNameSupplier").regex(pattern),
                    Criteria.where("vendorCode").regex(pattern),
                    Criteria.where("vendorName").regex(pattern)
            ));
        }
        query.with(Sort.by(Sort.Order.asc("pendingCompletion"), Sort.Order.asc("shortNameSupplier")));
        query.limit(safeLimit);
        query.fields()
                .include("buyerKey")
                .include("masterKey")
                .include("shortNameSupplier")
                .include("vendorCode")
                .include("vendorName")
                .include("matCharger")
                .include("pendingCompletion")
                .include("autoCreatedFromMatInfo");
        return mongoTemplate.find(query, VendorCode.class);
    }

    public VendorCode getById(String buyerKey, String id) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        VendorCode entity = vendorCodeRepository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Vendor Code not found"));
        if (!buyer.equals(BuyerKeys.legacyDefault(entity.getBuyerKey()))) {
            throw new MasterDataNotFoundException("Vendor Code not found for Buyer " + buyer);
        }
        return ensureMasterKeyPersisted(entity);
    }

    public VendorCode resolve(String buyerKey, String shortNameSupplier) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        String rawKey = MasterDataTextNormalizer.key(shortNameSupplier);
        if (rawKey == null) throw new MasterDataValidationException("shortNameSupplier is required");
        VendorCode entity = vendorCodeRepository.findByShortNameSupplierKey(scopedSupplierKey(buyer, rawKey))
                .orElseThrow(() -> new MasterDataNotFoundException("Supplier not found for Buyer " + buyer + ": " + shortNameSupplier));
        return ensureMasterKeyPersisted(entity);
    }

    /**
     * MAT_INFO is allowed to introduce a new supplier name inside the current Buyer.
     * Missing suppliers are created in one batch with a generated VC key and marked as pending completion.
     */
    public Map<String, VendorCode> resolveOrCreateFromMatInfo(String buyerKey, Collection<String> supplierNames) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        LinkedHashMap<String, String> requested = new LinkedHashMap<>();
        if (supplierNames != null) {
            for (String supplierName : supplierNames) {
                String cleanName = MasterDataTextNormalizer.trimToNull(supplierName);
                String key = MasterDataTextNormalizer.key(cleanName);
                if (key != null) requested.putIfAbsent(key, cleanName);
            }
        }
        if (requested.isEmpty()) return Map.of();

        Set<String> scopedKeys = requested.keySet().stream()
                .map(key -> scopedSupplierKey(buyer, key))
                .collect(Collectors.toSet());
        final Map<String, VendorCode> result = new LinkedHashMap<>();
        putVendorsBySupplierKey(result, vendorCodeRepository.findAllByShortNameSupplierKeyIn(scopedKeys));

        List<String> missingKeys = requested.keySet().stream().filter(key -> !result.containsKey(key)).toList();
        if (!missingKeys.isEmpty()) {
            List<String> masterKeys = reserveMasterKeys(missingKeys.size());
            LocalDateTime now = LocalDateTime.now();
            List<VendorCode> newVendors = new ArrayList<>(missingKeys.size());
            for (int index = 0; index < missingKeys.size(); index++) {
                String supplierKey = missingKeys.get(index);
                VendorCode vendor = new VendorCode();
                vendor.setBuyerKey(buyer);
                vendor.setMasterKey(masterKeys.get(index));
                vendor.setShortNameSupplier(requested.get(supplierKey));
                vendor.setShortNameSupplierKey(scopedSupplierKey(buyer, supplierKey));
                vendor.setPendingCompletion(true);
                vendor.setAutoCreatedFromMatInfo(true);
                vendor.setRemark(AUTO_CREATED_REMARK);
                vendor.setCreatedAt(now);
                vendor.setUpdatedAt(now);
                newVendors.add(vendor);
            }
            try {
                vendorCodeRepository.saveAll(newVendors);
            } catch (DuplicateKeyException ignored) {
                // Concurrent MAT_INFO requests may create the same Buyer/supplier pair.
            }
            result.clear();
            putVendorsBySupplierKey(result, vendorCodeRepository.findAllByShortNameSupplierKeyIn(scopedKeys));

            List<String> remaining = requested.keySet().stream().filter(key -> !result.containsKey(key)).toList();
            if (!remaining.isEmpty()) {
                List<String> retryMasterKeys = reserveMasterKeys(remaining.size());
                for (int index = 0; index < remaining.size(); index++) {
                    String supplierKey = remaining.get(index);
                    VendorCode vendor = new VendorCode();
                    vendor.setBuyerKey(buyer);
                    vendor.setMasterKey(retryMasterKeys.get(index));
                    vendor.setShortNameSupplier(requested.get(supplierKey));
                    vendor.setShortNameSupplierKey(scopedSupplierKey(buyer, supplierKey));
                    vendor.setPendingCompletion(true);
                    vendor.setAutoCreatedFromMatInfo(true);
                    vendor.setRemark(AUTO_CREATED_REMARK);
                    vendor.setCreatedAt(now);
                    vendor.setUpdatedAt(now);
                    try {
                        VendorCode saved = vendorCodeRepository.save(vendor);
                        result.put(supplierKey, saved);
                    } catch (DuplicateKeyException ignored) {
                        vendorCodeRepository.findByShortNameSupplierKey(scopedSupplierKey(buyer, supplierKey))
                                .ifPresent(existing -> result.put(supplierKey, existing));
                    }
                }
            }
        }

        for (Map.Entry<String, String> entry : requested.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                throw new MasterDataValidationException("Unable to create Vendor Code for supplier: " + entry.getValue());
            }
        }
        return result;
    }

    public VendorCode resolveOrCreateFromMatInfo(String buyerKey, String supplierName) {
        String key = MasterDataTextNormalizer.key(supplierName);
        if (key == null) throw new MasterDataValidationException("Short name supplier is required");
        return resolveOrCreateFromMatInfo(buyerKey, List.of(supplierName)).get(key);
    }

    public VendorCode update(String buyerKey, String id, VendorCodeRequest request) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        VendorCode existing = getById(buyer, id);
        String nextKey = requireSupplierKey(request);
        String currentKey = MasterDataTextNormalizer.key(existing.getShortNameSupplier());
        if (!nextKey.equals(currentKey)
                && vendorCodeRepository.existsByShortNameSupplierKey(scopedSupplierKey(buyer, nextKey))) {
            throw new MasterDataConflictException("Short name supplier already exists for Buyer " + buyer + ": " + request.getShortNameSupplier());
        }
        if (!nextKey.equals(currentKey) && isVendorCodeUsed(existing)) {
            throw new MasterDataConflictException("Cannot change Short Name Supplier because MAT_INFO or MPR records are using it");
        }
        apply(existing, request);
        existing.setUpdatedAt(LocalDateTime.now());
        return vendorCodeRepository.save(existing);
    }

    public void delete(String buyerKey, String id) {
        VendorCode existing = getById(buyerKey, id);
        if (isVendorCodeUsed(existing)) {
            throw new MasterDataConflictException("Cannot delete Vendor Code because MAT_INFO or MPR records are using it");
        }
        vendorCodeRepository.delete(existing);
    }

    public MasterDataImportResult upload(MultipartFile file, ImportMode mode, String buyerKey) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        ImportMode effectiveMode = mode == null ? ImportMode.UPSERT : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<VendorCodeRequest>> rows = parseStandardWorkbook(file, errors);
        int totalRows = rows.size();
        int duplicateRows = deduplicateVendorRows(rows);

        Set<String> rawSupplierKeys = rows.stream()
                .map(row -> MasterDataTextNormalizer.key(row.getValue().getShortNameSupplier()))
                .filter(value -> value != null)
                .collect(Collectors.toSet());
        Set<String> scopedKeys = rawSupplierKeys.stream()
                .map(key -> scopedSupplierKey(buyer, key))
                .collect(Collectors.toSet());
        Map<String, VendorCode> existingBySupplier = new LinkedHashMap<>();
        putVendorsBySupplierKey(existingBySupplier, vendorCodeRepository.findAllByShortNameSupplierKeyIn(scopedKeys));

        int existingDuplicateRows;
        if (effectiveMode == ImportMode.CREATE_ONLY) {
            existingDuplicateRows = removeExistingSuppliersForCreateOnly(rows, existingBySupplier);
        } else if (effectiveMode == ImportMode.REPLACE_ALL) {
            existingDuplicateRows = 0;
        } else {
            existingDuplicateRows = removeExistingVendorDuplicates(rows, existingBySupplier);
        }

        if (effectiveMode == ImportMode.REPLACE_ALL
                && (!matInfoRepository.findByBuyerKey(buyer).isEmpty() || mprDocumentRepository.existsByBuyerKey(buyer))) {
            errors.add(new ImportRowError(1, "mode", "Cannot use REPLACE_ALL while MAT_INFO or MPR data exists for this Buyer. Use UPSERT instead."));
        }
        if (!errors.isEmpty()) return MasterDataImportResult.rejected(MASTER_DATA_NAME, effectiveMode, totalRows, errors);

        MasterDataImportResult result = baseResult(effectiveMode, totalRows);
        result.setSkipped(duplicateRows + existingDuplicateRows);
        LocalDateTime now = LocalDateTime.now();
        List<VendorCode> toSave = new ArrayList<>();

        if (effectiveMode == ImportMode.REPLACE_ALL) {
            Set<String> incomingSupplierKeys = rows.stream()
                    .map(row -> MasterDataTextNormalizer.key(row.getValue().getShortNameSupplier()))
                    .filter(value -> value != null)
                    .collect(Collectors.toSet());
            List<VendorCode> toDelete = vendorCodeRepository.findByBuyerKey(buyer).stream()
                    .filter(item -> !incomingSupplierKeys.contains(MasterDataTextNormalizer.key(item.getShortNameSupplier())))
                    .collect(Collectors.toList());
            if (!toDelete.isEmpty()) {
                vendorCodeRepository.deleteAll(toDelete);
                result.setDeleted(toDelete.size());
            }
        }

        int newCount = (int) rows.stream()
                .filter(row -> !existingBySupplier.containsKey(MasterDataTextNormalizer.key(row.getValue().getShortNameSupplier())))
                .count();
        List<String> newKeys = reserveMasterKeys(newCount);
        int keyIndex = 0;
        for (ImportCandidate<VendorCodeRequest> row : rows) {
            VendorCodeRequest request = row.getValue();
            String supplierKey = MasterDataTextNormalizer.key(request.getShortNameSupplier());
            VendorCode entity = existingBySupplier.get(supplierKey);
            if (entity == null) {
                entity = new VendorCode();
                entity.setBuyerKey(buyer);
                entity.setMasterKey(newKeys.get(keyIndex++));
                entity.setCreatedAt(now);
                result.setCreated(result.getCreated() + 1);
            } else {
                if (effectiveMode == ImportMode.CREATE_ONLY || sameVendorData(entity, request)) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                result.setUpdated(result.getUpdated() + 1);
            }
            apply(entity, request);
            entity.setUpdatedAt(now);
            toSave.add(entity);
        }
        if (!toSave.isEmpty()) vendorCodeRepository.saveAll(toSave);
        return result;
    }

    public byte[] exportForEdit(String buyerKey) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        backfillMissingMasterKeys();
        return MasterDataEditWorkbookExporter.vendorCodes(vendorCodeRepository.findByBuyerKey(buyer));
    }

    public MasterDataImportResult uploadEdited(MultipartFile file, String buyerKey) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillLegacyBuyerScope();
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<KeyedVendorCodeRequest>> rows = parseEditedWorkbook(file, errors);
        int totalRows = rows.size();
        int duplicateRows = deduplicateEditedVendorRows(rows);

        Set<String> masterKeys = rows.stream().map(row -> row.getValue().masterKey)
                .filter(value -> value != null).collect(Collectors.toSet());
        Map<String, VendorCode> existingByMasterKey = vendorCodeRepository.findAllByMasterKeyIn(masterKeys).stream()
                .filter(item -> buyer.equals(BuyerKeys.legacyDefault(item.getBuyerKey())))
                .collect(Collectors.toMap(item -> normalizeMasterKey(item.getMasterKey()), item -> item));

        Set<String> rawSupplierKeys = rows.stream()
                .filter(row -> row.getValue().request != null)
                .map(row -> MasterDataTextNormalizer.key(row.getValue().request.getShortNameSupplier()))
                .filter(value -> value != null).collect(Collectors.toSet());
        Set<String> scopedKeys = rawSupplierKeys.stream()
                .map(key -> scopedSupplierKey(buyer, key))
                .collect(Collectors.toSet());
        Map<String, VendorCode> existingBySupplier = new LinkedHashMap<>();
        putVendorsBySupplierKey(existingBySupplier, vendorCodeRepository.findAllByShortNameSupplierKeyIn(scopedKeys));
        int existingDuplicateRows = removeExistingEditedVendorDuplicates(rows, existingBySupplier);

        validateEditedRows(rows, existingByMasterKey, existingBySupplier, errors);
        if (!errors.isEmpty()) return MasterDataImportResult.rejected(MASTER_DATA_NAME, ImportMode.UPSERT, totalRows, errors);

        MasterDataImportResult result = baseResult(ImportMode.UPSERT, totalRows);
        result.setSkipped(duplicateRows + existingDuplicateRows);
        LocalDateTime now = LocalDateTime.now();
        int createCount = (int) rows.stream()
                .filter(row -> "CREATE".equals(row.getValue().action))
                .filter(row -> {
                    String supplierKey = MasterDataTextNormalizer.key(row.getValue().request.getShortNameSupplier());
                    VendorCode duplicate = existingBySupplier.get(supplierKey);
                    return duplicate == null || !sameVendorData(duplicate, row.getValue().request);
                })
                .count();
        List<String> newKeys = reserveMasterKeys(createCount);
        int keyIndex = 0;
        List<VendorCode> toSave = new ArrayList<>();
        List<VendorCode> toDelete = new ArrayList<>();

        for (ImportCandidate<KeyedVendorCodeRequest> row : rows) {
            KeyedVendorCodeRequest keyed = row.getValue();
            if ("DELETE".equals(keyed.action)) {
                toDelete.add(existingByMasterKey.get(keyed.masterKey));
                result.setDeleted(result.getDeleted() + 1);
                continue;
            }
            String supplierKey = MasterDataTextNormalizer.key(keyed.request.getShortNameSupplier());
            VendorCode duplicate = existingBySupplier.get(supplierKey);
            if (duplicate != null && sameVendorData(duplicate, keyed.request)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            VendorCode entity;
            if ("CREATE".equals(keyed.action)) {
                entity = new VendorCode();
                entity.setBuyerKey(buyer);
                entity.setMasterKey(newKeys.get(keyIndex++));
                entity.setCreatedAt(now);
                result.setCreated(result.getCreated() + 1);
            } else {
                entity = existingByMasterKey.get(keyed.masterKey);
                result.setUpdated(result.getUpdated() + 1);
            }
            apply(entity, keyed.request);
            entity.setUpdatedAt(now);
            toSave.add(entity);
        }
        if (!toSave.isEmpty()) vendorCodeRepository.saveAll(toSave);
        if (!toDelete.isEmpty()) vendorCodeRepository.deleteAll(toDelete);
        return result;
    }

    private List<ImportCandidate<VendorCodeRequest>> parseStandardWorkbook(MultipartFile file, List<ImportRowError> errors) {
        List<ImportCandidate<VendorCodeRequest>> rows = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME, LEGACY_MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(sheet, evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Short name supplier"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Vender Code", "Vendor Code"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "Vender Name", "Vendor Name"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "MAT CHARGER"));
            Map<String, String> incoming = new HashMap<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 5, evaluator)) continue;
                int excelRow = rowIndex + 1;
                try {
                    VendorCodeRequest request = request(row, evaluator, 0);
                    addBeanErrors(errors, excelRow, beanValidator.validate(request));
                    String key = MasterDataTextNormalizer.key(request.getShortNameSupplier());
                    if (key == null) errors.add(new ImportRowError(excelRow, "shortNameSupplier", "Short name supplier is required"));
                    else {
                        String fingerprint = vendorDataKey(request);
                        String previous = incoming.putIfAbsent(key, fingerprint);
                        if (previous != null && !previous.equals(fingerprint)) {
                            errors.add(new ImportRowError(
                                    excelRow,
                                    "shortNameSupplier",
                                    "The same Short name supplier appears more than once with different Vendor data"
                            ));
                        }
                    }
                    rows.add(new ImportCandidate<>(excelRow, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import VENDER CODE: " + cleanMessage(ex)));
        }
        return rows;
    }

    private List<ImportCandidate<KeyedVendorCodeRequest>> parseEditedWorkbook(MultipartFile file, List<ImportRowError> errors) {
        List<ImportCandidate<KeyedVendorCodeRequest>> rows = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME, LEGACY_MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            boolean legacyHasRowVersion = "ROW VERSION".equals(
                    MasterDataTextNormalizer.upper(excelSupport.text(sheet.getRow(sheet.getFirstRowNum()), 1, evaluator))
            );
            int actionColumn = legacyHasRowVersion ? 2 : 1;
            int dataOffset = actionColumn + 1;
            excelSupport.requireHeaders(sheet, evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Key"),
                    new MasterDataExcelSupport.HeaderRequirement(actionColumn, "Action"),
                    new MasterDataExcelSupport.HeaderRequirement(dataOffset, "Short name supplier"),
                    new MasterDataExcelSupport.HeaderRequirement(dataOffset + 1, "Vender Code", "Vendor Code"),
                    new MasterDataExcelSupport.HeaderRequirement(dataOffset + 2, "Vender Name", "Vendor Name"),
                    new MasterDataExcelSupport.HeaderRequirement(dataOffset + 3, "MAT CHARGER"),
                    new MasterDataExcelSupport.HeaderRequirement(dataOffset + 4, "Remark"));
            Map<String, String> fingerprintByMasterKey = new HashMap<>();
            Map<String, String> fingerprintBySupplierKey = new HashMap<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, dataOffset + 5, evaluator)) continue;
                int excelRow = rowIndex + 1;
                try {
                    KeyedVendorCodeRequest keyed = new KeyedVendorCodeRequest();
                    keyed.masterKey = normalizeUploadedMasterKey(excelSupport.text(row, 0, evaluator));
                    keyed.action = normalizeAction(excelSupport.text(row, actionColumn, evaluator), keyed.masterKey);
                    keyed.request = request(row, evaluator, dataOffset);

                    if (!"DELETE".equals(keyed.action)) {
                        addBeanErrors(errors, excelRow, beanValidator.validate(keyed.request));
                        String supplierKey = MasterDataTextNormalizer.key(keyed.request.getShortNameSupplier());
                        if (supplierKey == null) errors.add(new ImportRowError(excelRow, "shortNameSupplier", "Short name supplier is required"));
                        else {
                            String fingerprint = vendorDataKey(keyed.request);
                            String previous = fingerprintBySupplierKey.putIfAbsent(supplierKey, fingerprint);
                            if (previous != null && !previous.equals(fingerprint)) {
                                errors.add(new ImportRowError(
                                        excelRow,
                                        "shortNameSupplier",
                                        "The same Short name supplier appears more than once with different Vendor data"
                                ));
                            }
                        }
                    }
                    if (keyed.masterKey != null) {
                        String fingerprint = keyed.action + "|" + (keyed.request == null ? "" : vendorDataKey(keyed.request));
                        String previous = fingerprintByMasterKey.putIfAbsent(keyed.masterKey, fingerprint);
                        if (previous != null && !previous.equals(fingerprint)) {
                            errors.add(new ImportRowError(
                                    excelRow,
                                    "masterKey",
                                    "The same Key appears more than once with different Vendor data"
                            ));
                        }
                    }
                    rows.add(new ImportCandidate<>(excelRow, keyed));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import edited VENDER CODE: " + cleanMessage(ex)));
        }
        return rows;
    }

    private void validateEditedRows(
            List<ImportCandidate<KeyedVendorCodeRequest>> rows,
            Map<String, VendorCode> existingByMasterKey,
            Map<String, VendorCode> existingBySupplier,
            List<ImportRowError> errors
    ) {
        for (ImportCandidate<KeyedVendorCodeRequest> row : rows) {
            KeyedVendorCodeRequest keyed = row.getValue();
            VendorCode target = keyed.masterKey == null ? null : existingByMasterKey.get(keyed.masterKey);
            if ("CREATE".equals(keyed.action)) {
                if (keyed.masterKey != null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "CREATE must have a blank Key"));
            } else {
                if (keyed.masterKey == null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", keyed.action + " requires a Key"));
                else if (target == null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "Key does not exist: " + keyed.masterKey));
            }
            if (target != null && "DELETE".equals(keyed.action) && isVendorCodeUsed(target)) {
                errors.add(new ImportRowError(row.getRowNumber(), "action", "Cannot delete Vendor Code because MAT_INFO or MPR records are using it"));
            }
            if ("DELETE".equals(keyed.action) || keyed.request == null) continue;
            String supplierKey = MasterDataTextNormalizer.key(keyed.request.getShortNameSupplier());
            VendorCode duplicate = existingBySupplier.get(supplierKey);
            if (duplicate != null
                    && (target == null || !duplicate.getId().equals(target.getId()))
                    && !sameVendorData(duplicate, keyed.request)) {
                errors.add(new ImportRowError(row.getRowNumber(), "shortNameSupplier", "Supplier already exists. Keep its Key to update the existing row."));
            }
            if (target != null && !supplierKey.equals(MasterDataTextNormalizer.key(target.getShortNameSupplier())) && isVendorCodeUsed(target)) {
                errors.add(new ImportRowError(row.getRowNumber(), "shortNameSupplier", "Cannot change Short Name Supplier because MAT_INFO or MPR records are using it"));
            }
        }
    }

    private VendorCodeRequest request(Row row, FormulaEvaluator evaluator, int offset) {
        VendorCodeRequest request = new VendorCodeRequest();
        request.setShortNameSupplier(excelSupport.text(row, offset, evaluator));
        request.setVendorCode(excelSupport.text(row, offset + 1, evaluator));
        request.setVendorName(excelSupport.text(row, offset + 2, evaluator));
        request.setMatCharger(excelSupport.text(row, offset + 3, evaluator));
        request.setRemark(excelSupport.text(row, offset + 4, evaluator));
        return request;
    }

    private void apply(VendorCode target, VendorCodeRequest request) {
        String shortName = MasterDataTextNormalizer.trimToNull(request == null ? null : request.getShortNameSupplier());
        if (shortName == null) throw new MasterDataValidationException("Short name supplier is required");
        String buyer = BuyerKeys.legacyDefault(target.getBuyerKey());
        target.setBuyerKey(buyer);
        target.setShortNameSupplier(shortName);
        target.setShortNameSupplierKey(scopedSupplierKey(buyer, MasterDataTextNormalizer.key(shortName)));
        target.setVendorCode(vendorCodeText(request.getVendorCode()));
        target.setVendorName(MasterDataTextNormalizer.trimToNull(request.getVendorName()));
        target.setMatCharger(MasterDataTextNormalizer.trimToNull(request.getMatCharger()));
        target.setRemark(MasterDataTextNormalizer.trimToNull(request.getRemark()));
        target.setPendingCompletion(
                MasterDataTextNormalizer.trimToNull(target.getVendorCode()) == null
                        || MasterDataTextNormalizer.trimToNull(target.getVendorName()) == null
                        || MasterDataTextNormalizer.trimToNull(target.getMatCharger()) == null
        );
        if (!target.isPendingCompletion()) target.setAutoCreatedFromMatInfo(false);
    }

    /**
     * CREATE_ONLY can only create new suppliers. Any row whose normalized Short Name Supplier
     * already exists is skipped instead of rejecting the entire Excel import.
     */
    private int removeExistingSuppliersForCreateOnly(
            List<ImportCandidate<VendorCodeRequest>> rows,
            Map<String, VendorCode> existingBySupplier
    ) {
        int skipped = 0;
        for (int index = rows.size() - 1; index >= 0; index--) {
            VendorCodeRequest request = rows.get(index).getValue();
            String supplierKey = MasterDataTextNormalizer.key(request.getShortNameSupplier());
            if (supplierKey != null && existingBySupplier.containsKey(supplierKey)) {
                rows.remove(index);
                skipped++;
            }
        }
        return skipped;
    }

    /** Removes rows that are already identical in the database before validation. */
    private int removeExistingVendorDuplicates(
            List<ImportCandidate<VendorCodeRequest>> rows,
            Map<String, VendorCode> existingBySupplier
    ) {
        int skipped = 0;
        for (int index = rows.size() - 1; index >= 0; index--) {
            VendorCodeRequest request = rows.get(index).getValue();
            String supplierKey = MasterDataTextNormalizer.key(request.getShortNameSupplier());
            VendorCode existing = existingBySupplier.get(supplierKey);
            if (existing != null && sameVendorData(existing, request)) {
                rows.remove(index);
                skipped++;
            }
        }
        return skipped;
    }

    /** Edited rows that are already identical are skipped before Key validation. */
    private int removeExistingEditedVendorDuplicates(
            List<ImportCandidate<KeyedVendorCodeRequest>> rows,
            Map<String, VendorCode> existingBySupplier
    ) {
        int skipped = 0;
        for (int index = rows.size() - 1; index >= 0; index--) {
            KeyedVendorCodeRequest keyed = rows.get(index).getValue();
            if (keyed == null || keyed.request == null || "DELETE".equals(keyed.action)) continue;
            String supplierKey = MasterDataTextNormalizer.key(keyed.request.getShortNameSupplier());
            VendorCode existing = existingBySupplier.get(supplierKey);
            if (existing != null && sameVendorData(existing, keyed.request)) {
                rows.remove(index);
                skipped++;
            }
        }
        return skipped;
    }

    private int deduplicateVendorRows(List<ImportCandidate<VendorCodeRequest>> rows) {
        LinkedHashMap<String, ImportCandidate<VendorCodeRequest>> unique = new LinkedHashMap<>();
        int skipped = 0;
        for (ImportCandidate<VendorCodeRequest> row : rows) {
            if (unique.putIfAbsent(vendorDataKey(row.getValue()), row) != null) skipped++;
        }
        rows.clear();
        rows.addAll(unique.values());
        return skipped;
    }

    private int deduplicateEditedVendorRows(List<ImportCandidate<KeyedVendorCodeRequest>> rows) {
        LinkedHashMap<String, ImportCandidate<KeyedVendorCodeRequest>> unique = new LinkedHashMap<>();
        int skipped = 0;
        for (ImportCandidate<KeyedVendorCodeRequest> row : rows) {
            KeyedVendorCodeRequest keyed = row.getValue();
            String key = (keyed.masterKey == null ? "" : keyed.masterKey)
                    + "|" + keyed.action
                    + "|" + (keyed.request == null ? "" : vendorDataKey(keyed.request));
            if (unique.putIfAbsent(key, row) != null) skipped++;
        }
        rows.clear();
        rows.addAll(unique.values());
        return skipped;
    }

    private boolean sameVendorData(VendorCode existing, VendorCodeRequest request) {
        if (existing == null || request == null) return false;
        return vendorDataKey(existing).equals(vendorDataKey(request));
    }

    private String vendorDataKey(VendorCodeRequest request) {
        if (request == null) return "";
        return String.join("\u001F",
                normalizedText(request.getShortNameSupplier()),
                normalizedText(vendorCodeText(request.getVendorCode())),
                normalizedText(request.getVendorName()),
                normalizedText(request.getMatCharger()),
                normalizedText(request.getRemark())
        );
    }

    private String vendorDataKey(VendorCode item) {
        if (item == null) return "";
        return String.join("\u001F",
                normalizedText(item.getShortNameSupplier()),
                normalizedText(vendorCodeText(item.getVendorCode())),
                normalizedText(item.getVendorName()),
                normalizedText(item.getMatCharger()),
                normalizedText(item.getRemark())
        );
    }

    private String normalizedText(String value) {
        String normalized = MasterDataTextNormalizer.key(value);
        return normalized == null ? "" : normalized;
    }


    private void putVendorsBySupplierKey(
            Map<String, VendorCode> target,
            Collection<VendorCode> vendors
    ) {
        if (target == null || vendors == null) return;
        for (VendorCode vendor : vendors) {
            if (vendor == null) continue;
            String supplierKey = MasterDataTextNormalizer.key(vendor.getShortNameSupplier());
            if (supplierKey != null && !supplierKey.isBlank()) {
                target.putIfAbsent(supplierKey, vendor);
            }
        }
    }

    private boolean isVendorCodeUsed(VendorCode vendor) {
        if (vendor == null) return false;
        String buyer = BuyerKeys.legacyDefault(vendor.getBuyerKey());
        String key = MasterDataTextNormalizer.key(vendor.getShortNameSupplier());
        return (key != null && matInfoRepository.existsByBuyerKeyAndShortNameSupplierKey(buyer, key))
                || mprDocumentRepository.existsByBuyerKeyAndLinesShortNameSupplierIgnoreCase(buyer, vendor.getShortNameSupplier())
                || (vendor.getId() != null && mprDocumentRepository.existsByLinesShipToIdsContaining(vendor.getId()));
    }

    private String scopedSupplierKey(String buyerKey, String supplierKey) {
        String raw = MasterDataTextNormalizer.key(supplierKey);
        if (raw == null) throw new MasterDataValidationException("Short name supplier is required");
        return BuyerKeys.legacyDefault(buyerKey) + BUYER_KEY_SEPARATOR + raw;
    }

    private synchronized void backfillLegacyBuyerScope() {
        if (buyerScopeBackfilled) return;
        List<VendorCode> all = vendorCodeRepository.findAll();
        List<VendorCode> changed = new ArrayList<>();
        for (VendorCode item : all) {
            if (item == null) continue;
            String buyer = BuyerKeys.legacyDefault(item.getBuyerKey());
            String supplierKey = MasterDataTextNormalizer.key(item.getShortNameSupplier());
            boolean dirty = !buyer.equals(item.getBuyerKey());
            if (supplierKey != null) {
                String scoped = scopedSupplierKey(buyer, supplierKey);
                if (!scoped.equals(item.getShortNameSupplierKey())) {
                    item.setShortNameSupplierKey(scoped);
                    dirty = true;
                }
            }
            if (dirty) {
                item.setBuyerKey(buyer);
                changed.add(item);
            }
        }
        if (!changed.isEmpty()) vendorCodeRepository.saveAll(changed);
        buyerScopeBackfilled = true;
    }

    private synchronized void backfillMissingMasterKeys() {
        if (masterKeysBackfilled) return;
        Query query = new Query(new Criteria().orOperator(
                Criteria.where("masterKey").exists(false),
                Criteria.where("masterKey").is(null),
                Criteria.where("masterKey").is("")
        ));
        List<VendorCode> missing = mongoTemplate.find(query, VendorCode.class);
        if (!missing.isEmpty()) {
            List<String> keys = reserveMasterKeys(missing.size());
            for (int i = 0; i < missing.size(); i++) missing.get(i).setMasterKey(keys.get(i));
            vendorCodeRepository.saveAll(missing);
        }
        masterKeysBackfilled = true;
    }

    private VendorCode ensureMasterKeyPersisted(VendorCode entity) {
        if (entity.getMasterKey() == null || entity.getMasterKey().isBlank()) {
            entity.setMasterKey(nextMasterKey());
            return vendorCodeRepository.save(entity);
        }
        return entity;
    }

    private String nextMasterKey() {
        return sequenceService.next(SEQUENCE_NAME, MASTER_KEY_PREFIX, this::maxExistingSequence);
    }

    private List<String> reserveMasterKeys(int count) {
        return sequenceService.reserve(SEQUENCE_NAME, MASTER_KEY_PREFIX, count, this::maxExistingSequence);
    }

    private long maxExistingSequence() {
        Query query = new Query(Criteria.where("masterKey").regex("^" + MASTER_KEY_PREFIX + "\\d+$"));
        query.with(Sort.by(Sort.Direction.DESC, "masterKey")).limit(1);
        query.fields().include("masterKey");
        VendorCode latest = mongoTemplate.findOne(query, VendorCode.class);
        return parseSequence(latest == null ? null : latest.getMasterKey());
    }

    private long parseSequence(String key) {
        if (key == null || !key.matches("^" + MASTER_KEY_PREFIX + "\\d+$")) return 0;
        try { return Long.parseLong(key.substring(MASTER_KEY_PREFIX.length())); }
        catch (NumberFormatException ex) { return 0; }
    }

    private MasterDataImportResult baseResult(ImportMode mode, int totalRows) {
        MasterDataImportResult result = new MasterDataImportResult();
        result.setMasterData(MASTER_DATA_NAME);
        result.setMode(mode);
        result.setApplied(true);
        result.setTotalRows(totalRows);
        result.setValidRows(totalRows);
        return result;
    }

    private void addContainsFilter(Query query, String field, String value) {
        String clean = MasterDataTextNormalizer.trimToNull(value);
        if (clean != null) query.addCriteria(Criteria.where(field).regex(containsPattern(clean)));
    }

    private Pattern containsPattern(String value) {
        return Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE);
    }

    private String normalizeUploadedMasterKey(String raw) {
        String value = MasterDataTextNormalizer.trimToNull(raw);
        if (value == null) return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.matches("^" + MASTER_KEY_PREFIX + "\\d+$")) {
            throw new MasterDataValidationException("Invalid Key format: " + value + ". Expected " + MASTER_KEY_PREFIX + "000001 style.");
        }
        return normalized;
    }

    private String normalizeMasterKey(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeAction(String raw, String masterKey) {
        String value = MasterDataTextNormalizer.upper(raw);
        if (value == null) return masterKey == null ? "CREATE" : "UPDATE";
        if (!Set.of("CREATE", "UPDATE", "DELETE").contains(value)) {
            throw new MasterDataValidationException("Action must be CREATE, UPDATE or DELETE");
        }
        return value;
    }


    private String vendorCodeText(String value) {
        String text = MasterDataTextNormalizer.trimToNull(value);
        if (text == null) return null;
        return text.matches("^[0-9,]+$") ? text.replace(",", "") : text;
    }

    private String requireSupplierKey(VendorCodeRequest request) {
        String key = MasterDataTextNormalizer.key(request == null ? null : request.getShortNameSupplier());
        if (key == null) throw new MasterDataValidationException("Short name supplier is required");
        return key;
    }

    private Pageable toPageable(int page, int size) {
        if (page < 0) throw new MasterDataValidationException("page must be >= 0");
        if (size < 1 || size > 200) throw new MasterDataValidationException("size must be between 1 and 200");
        return PageRequest.of(page, size);
    }

    private void addBeanErrors(List<ImportRowError> errors, int row, Collection<String> messages) {
        for (String message : messages) {
            String[] parts = message.split(": ", 2);
            errors.add(new ImportRowError(row, parts[0], parts.length > 1 ? parts[1] : message));
        }
    }

    private String cleanMessage(Exception ex) {
        String message = MasterDataTextNormalizer.trimToNull(ex.getMessage());
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private static class KeyedVendorCodeRequest {
        private String masterKey;
        private String action;
        private VendorCodeRequest request;
    }
}
