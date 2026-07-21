package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.ImportRowError;
import org.bsl.sales.dto.MatInfoRequest;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.CurrencyMaster;
import org.bsl.sales.model.MatInfo;
import org.bsl.sales.model.VendorCode;
import org.bsl.sales.repository.MatInfoRepository;
import org.bsl.sales.security.BuyerAccessService;
import org.bsl.sales.support.BuyerKeys;
import org.bsl.sales.support.ImportCandidate;
import org.bsl.sales.support.MasterDataBeanValidator;
import org.bsl.sales.support.MasterDataEditWorkbookExporter;
import org.bsl.sales.support.MasterDataExcelSupport;
import org.bsl.sales.support.MasterDataTextNormalizer;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MatInfoService {

    private static final String MASTER_DATA_NAME = "MAT_INFO";
    private static final String MASTER_KEY_PREFIX = "MI";
    private static final String SEQUENCE_NAME = "mat_info";
    private static final int MAX_PRICE_SCALE = 6;
    private static final int MAX_PRICE_INTEGER_DIGITS = 18;
    /** Excel stores numeric cells as binary doubles, which can turn 0.03552 into 0.035519999999999996. */
    private static final BigDecimal EXCEL_FLOATING_POINT_EPSILON = new BigDecimal("0.000000000001");

    private final MatInfoRepository matInfoRepository;
    private final VendorCodeService vendorCodeService;
    private final CurrencyMasterService currencyMasterService;
    private final MasterDataBeanValidator beanValidator;
    private final MasterDataExcelSupport excelSupport;
    private final BuyerAccessService buyerAccess;
    private final MongoTemplate mongoTemplate;
    private final MasterDataSequenceService sequenceService;
    private final Set<String> backfilledBuyers = ConcurrentHashMap.newKeySet();
    private final Set<String> masterKeyBackfilledBuyers = ConcurrentHashMap.newKeySet();

    public MatInfoService(
            MatInfoRepository matInfoRepository,
            VendorCodeService vendorCodeService,
            CurrencyMasterService currencyMasterService,
            MasterDataBeanValidator beanValidator,
            MasterDataExcelSupport excelSupport,
            BuyerAccessService buyerAccess,
            MongoTemplate mongoTemplate,
            MasterDataSequenceService sequenceService
    ) {
        this.matInfoRepository = matInfoRepository;
        this.vendorCodeService = vendorCodeService;
        this.currencyMasterService = currencyMasterService;
        this.beanValidator = beanValidator;
        this.excelSupport = excelSupport;
        this.buyerAccess = buyerAccess;
        this.mongoTemplate = mongoTemplate;
        this.sequenceService = sequenceService;
    }

    public MatInfo create(MatInfoRequest request) {
        String buyer = buyerAccess.requireBuyer(request.getBuyerKey());
        request.setBuyerKey(buyer);
        validateRequest(request);
        VendorCode vendor = requireVendor(request.getShortNameSupplier());
        CurrencyMaster currency = requireCurrency(request.getCurrency());

        Optional<MatInfo> duplicate = findExisting(request);
        if (duplicate.isPresent()) {
            MatInfo existing = duplicate.get();
            if (!existing.isActive()) {
                apply(existing, request, vendor, currency);
                existing.setActive(true);
                existing.setDeletedAt(null);
                existing.setDeletedBy(null);
                existing.setUpdatedAt(LocalDateTime.now());
                return saveWithDuplicateProtection(existing);
            }
            throw new MasterDataConflictException("MAT_INFO already exists for the same 8 import fields");
        }

        MatInfo entity = new MatInfo();
        entity.setMasterKey(nextMasterKey());
        apply(entity, request, vendor, currency);
        LocalDateTime now = LocalDateTime.now();
        entity.setActive(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return saveWithDuplicateProtection(entity);
    }

    public Page<MatInfo> list(
            String buyerKey,
            String masterKey,
            String flexId,
            String materialType,
            String matFullDescription,
            String matColor,
            String shortNameSupplier,
            int page,
            int size
    ) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillMissingMasterKeys(buyer);
        backfillLegacyIdentityKeys(buyer);
        Pageable pageable = toPageable(page, size);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("buyerKey").is(buyer),
                Criteria.where("active").ne(false)
        ));
        addContainsFilter(query, "masterKey", masterKey);
        addContainsFilter(query, "flexId", flexId);
        addContainsFilter(query, "materialType", materialType);
        addContainsFilter(query, "matFullDescription", matFullDescription);
        addContainsFilter(query, "matColor", matColor);
        addContainsFilter(query, "shortNameSupplier", shortNameSupplier);
        long total = mongoTemplate.count(query, MatInfo.class);
        query.with(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("matFullDescription")));
        query.skip(pageable.getOffset()).limit(pageable.getPageSize());
        return new PageImpl<>(mongoTemplate.find(query, MatInfo.class), pageable, total);
    }

    public MatInfo getById(String id) {
        MatInfo entity = matInfoRepository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("MAT_INFO not found"));
        buyerAccess.requireEntityAccess(entity.getBuyerKey());
        if (!entity.isActive()) throw new MasterDataNotFoundException("MAT_INFO not found or inactive");
        if (entity.getBuyerKey() == null || entity.getBuyerKey().isBlank()) entity.setBuyerKey(BuyerKeys.LL_BEAN);
        return ensureMasterKeyPersisted(entity);
    }

    public MatInfo update(String id, MatInfoRequest request) {
        MatInfo existing = getById(id);
        String currentBuyer = BuyerKeys.legacyDefault(existing.getBuyerKey());
        String targetBuyer = request.getBuyerKey() == null || request.getBuyerKey().isBlank()
                ? currentBuyer : buyerAccess.requireBuyer(request.getBuyerKey());
        if (!currentBuyer.equals(targetBuyer)) throw new MasterDataValidationException("MAT_INFO cannot be moved to another Buyer");
        request.setBuyerKey(currentBuyer);
        validateRequest(request);
        VendorCode vendor = requireVendor(request.getShortNameSupplier());
        CurrencyMaster currency = requireCurrency(request.getCurrency());

        Optional<MatInfo> duplicate = findExisting(request);
        if (duplicate.isPresent() && !existing.getId().equals(duplicate.get().getId())) {
            throw new MasterDataConflictException("MAT_INFO already exists for the same 8 import fields");
        }
        apply(existing, request, vendor, currency);
        existing.setUpdatedAt(LocalDateTime.now());
        return saveWithDuplicateProtection(existing);
    }

    /** Soft delete keeps historical master data traceable and prevents orphaned snapshots. */
    public void delete(String id) {
        MatInfo entity = getById(id);
        entity.setActive(false);
        entity.setDeletedAt(LocalDateTime.now());
        entity.setDeletedBy(RequestActor.current());
        entity.setUpdatedAt(LocalDateTime.now());
        matInfoRepository.save(entity);
    }

    public MasterDataImportResult upload(MultipartFile file, ImportMode mode, String buyerKey) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        ImportMode effectiveMode = mode == null ? ImportMode.UPSERT : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<MatInfoRequest>> rows = parseStandardWorkbook(file, buyer, errors);
        int totalRows = rows.size();
        int duplicateRows = deduplicateStandardRows(rows);

        Map<String, CurrencyMaster> currencies = currencyMasterService.currentCurrencyMap();
        validateReferences(rows, currencies, errors);

        Set<String> incomingIdentity = rows.stream().map(row -> identityKey(row.getValue())).collect(Collectors.toSet());
        Map<String, MatInfo> existingByIdentity = existingByIdentityKey(
                buyer, incomingIdentity, effectiveMode == ImportMode.REPLACE_ALL
        );
        if (!errors.isEmpty()) return MasterDataImportResult.rejected(MASTER_DATA_NAME, effectiveMode, totalRows, errors);

        Map<String, VendorCode> vendors = vendorMap(rows.stream()
                .map(row -> row.getValue().getShortNameSupplier()).collect(Collectors.toSet()));
        MasterDataImportResult result = baseResult(effectiveMode, totalRows);
        result.setSkipped(duplicateRows);
        LocalDateTime now = LocalDateTime.now();
        List<MatInfo> toSave = new ArrayList<>();

        if (effectiveMode == ImportMode.REPLACE_ALL) {
            for (MatInfo existing : uniqueEntities(existingByIdentity)) {
                if (existing.isActive() && !incomingIdentity.contains(identityKey(existing))) {
                    existing.setActive(false);
                    existing.setDeletedAt(now);
                    existing.setDeletedBy(RequestActor.current());
                    existing.setUpdatedAt(now);
                    toSave.add(existing);
                    result.setDeleted(result.getDeleted() + 1);
                }
            }
        }

        int createCount = (int) rows.stream().filter(row -> !existingByIdentity.containsKey(identityKey(row.getValue()))).count();
        List<String> newKeys = reserveMasterKeys(createCount);
        int keyIndex = 0;
        for (ImportCandidate<MatInfoRequest> row : rows) {
            MatInfoRequest request = row.getValue();
            String identity = identityKey(request);
            MatInfo entity = existingByIdentity.get(identity);
            if (entity != null) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            entity = new MatInfo();
            entity.setMasterKey(newKeys.get(keyIndex++));
            entity.setCreatedAt(now);
            result.setCreated(result.getCreated() + 1);
            apply(entity, request,
                    vendors.get(MasterDataTextNormalizer.key(request.getShortNameSupplier())),
                    currencies.get(MasterDataTextNormalizer.upper(request.getCurrency())));
            entity.setActive(true);
            entity.setDeletedAt(null);
            entity.setDeletedBy(null);
            entity.setUpdatedAt(now);
            toSave.add(entity);
        }
        saveAllWithDuplicateProtection(toSave);
        return result;
    }

    public byte[] exportForEdit(String buyerKey) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        backfillMissingMasterKeys(buyer);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("buyerKey").is(buyer),
                Criteria.where("active").ne(false)
        ));
        query.with(Sort.by(Sort.Order.asc("masterKey")));
        return MasterDataEditWorkbookExporter.matInfos(mongoTemplate.find(query, MatInfo.class));
    }

    public MasterDataImportResult uploadEdited(MultipartFile file, String buyerKey) {
        String buyer = buyerAccess.requireBuyer(buyerKey);
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<KeyedMatInfoRequest>> rows = parseEditedWorkbook(file, buyer, errors);
        int totalRows = rows.size();
        int duplicateRows = deduplicateEditedRows(rows, errors);

        Set<String> requestedKeys = rows.stream().map(row -> row.getValue().masterKey)
                .filter(value -> value != null).collect(Collectors.toSet());
        Map<String, MatInfo> allTargets = matInfoRepository.findAllByMasterKeyIn(requestedKeys).stream()
                .collect(Collectors.toMap(item -> normalizeMasterKey(item.getMasterKey()), item -> item));
        Set<String> editedIdentityKeys = rows.stream()
                .filter(row -> row.getValue().request != null && !"DELETE".equals(row.getValue().action))
                .map(row -> identityKey(row.getValue().request)).collect(Collectors.toSet());
        Map<String, MatInfo> existingByIdentity = existingByIdentityKey(buyer, editedIdentityKeys, false);
        Map<String, CurrencyMaster> currencies = currencyMasterService.currentCurrencyMap();

        validateEditedRows(rows, buyer, allTargets, existingByIdentity, currencies, errors);
        if (!errors.isEmpty()) return MasterDataImportResult.rejected(MASTER_DATA_NAME, ImportMode.UPSERT, totalRows, errors);

        Map<String, VendorCode> vendors = vendorMap(rows.stream()
                .filter(row -> row.getValue().request != null && !"DELETE".equals(row.getValue().action))
                .map(row -> row.getValue().request.getShortNameSupplier())
                .collect(Collectors.toSet()));
        MasterDataImportResult result = baseResult(ImportMode.UPSERT, totalRows);
        result.setSkipped(duplicateRows);
        int createCount = (int) rows.stream()
                .filter(row -> "CREATE".equals(row.getValue().action))
                .filter(row -> !existingByIdentity.containsKey(identityKey(row.getValue().request)))
                .count();
        List<String> newKeys = reserveMasterKeys(createCount);
        int keyIndex = 0;
        LocalDateTime now = LocalDateTime.now();
        List<MatInfo> toSave = new ArrayList<>();

        for (ImportCandidate<KeyedMatInfoRequest> row : rows) {
            KeyedMatInfoRequest keyed = row.getValue();
            MatInfo entity;
            if ("DELETE".equals(keyed.action)) {
                entity = allTargets.get(keyed.masterKey);
                entity.setActive(false);
                entity.setDeletedAt(now);
                entity.setDeletedBy(RequestActor.current());
                entity.setUpdatedAt(now);
                toSave.add(entity);
                result.setDeleted(result.getDeleted() + 1);
                continue;
            }
            MatInfo duplicate = existingByIdentity.get(identityKey(keyed.request));
            if (duplicate != null) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            if ("CREATE".equals(keyed.action)) {
                entity = new MatInfo();
                entity.setMasterKey(newKeys.get(keyIndex++));
                entity.setCreatedAt(now);
                result.setCreated(result.getCreated() + 1);
            } else {
                entity = allTargets.get(keyed.masterKey);
                result.setUpdated(result.getUpdated() + 1);
            }
            apply(entity, keyed.request,
                    vendors.get(MasterDataTextNormalizer.key(keyed.request.getShortNameSupplier())),
                    currencies.get(MasterDataTextNormalizer.upper(keyed.request.getCurrency())));
            entity.setActive(true);
            entity.setDeletedAt(null);
            entity.setDeletedBy(null);
            entity.setUpdatedAt(now);
            toSave.add(entity);
        }
        saveAllWithDuplicateProtection(toSave);
        return result;
    }

    private List<ImportCandidate<MatInfoRequest>> parseStandardWorkbook(
            MultipartFile file,
            String buyer,
            List<ImportRowError> errors
    ) {
        List<ImportCandidate<MatInfoRequest>> rows = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            requireStandardHeaders(sheet, evaluator, 0);
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 12, evaluator)) continue;
                int excelRow = rowIndex + 1;
                try {
                    MatInfoRequest request = toRequest(row, evaluator, 0);
                    request.setBuyerKey(buyer);
                    List<String> beanErrors = beanValidator.validate(request);
                    addBeanErrors(errors, excelRow, beanErrors);
                    if (beanErrors.isEmpty()) validateRequest(request);
                    rows.add(new ImportCandidate<>(excelRow, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import MAT_INFO: " + cleanMessage(ex)));
        }
        return rows;
    }

    private List<ImportCandidate<KeyedMatInfoRequest>> parseEditedWorkbook(
            MultipartFile file,
            String buyer,
            List<ImportRowError> errors
    ) {
        List<ImportCandidate<KeyedMatInfoRequest>> rows = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(sheet, evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Key"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Row Version"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "Action"));
            requireStandardHeaders(sheet, evaluator, 3);
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 15, evaluator)) continue;
                int excelRow = rowIndex + 1;
                try {
                    KeyedMatInfoRequest keyed = new KeyedMatInfoRequest();
                    keyed.masterKey = normalizeUploadedMasterKey(excelSupport.text(row, 0, evaluator));
                    keyed.rowVersion = optionalLong(excelSupport.text(row, 1, evaluator));
                    keyed.action = normalizeAction(excelSupport.text(row, 2, evaluator), keyed.masterKey);
                    keyed.request = toRequest(row, evaluator, 3);
                    keyed.request.setBuyerKey(buyer);
                    if (!"DELETE".equals(keyed.action)) {
                        List<String> beanErrors = beanValidator.validate(keyed.request);
                        addBeanErrors(errors, excelRow, beanErrors);
                        if (beanErrors.isEmpty()) validateRequest(keyed.request);
                    }
                    rows.add(new ImportCandidate<>(excelRow, keyed));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import edited MAT_INFO: " + cleanMessage(ex)));
        }
        return rows;
    }

    private void requireStandardHeaders(Sheet sheet, FormulaEvaluator evaluator, int offset) {
        excelSupport.requireHeaders(sheet, evaluator,
                new MasterDataExcelSupport.HeaderRequirement(offset, "FLEX ID"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 1, "Material type"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 2, "MAT FULL DESCRIPTION"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 3, "MAT COLOR"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 4, "MAT UNIT"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 5, "CUR"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 6, "MAT PRICE (W/O TAX)"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 7, "Short name supplier"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 8, "Remark"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 9, "Updated Date"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 10, "Updated PIC"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 11, "Style Desc"));
    }

    private MatInfoRequest toRequest(Row row, FormulaEvaluator evaluator, int offset) {
        MatInfoRequest request = new MatInfoRequest();
        request.setFlexId(excelSupport.text(row, offset, evaluator));
        request.setMaterialType(excelSupport.text(row, offset + 1, evaluator));
        request.setMatFullDescription(excelSupport.text(row, offset + 2, evaluator));
        request.setMatColor(excelSupport.text(row, offset + 3, evaluator));
        request.setMatUnit(excelSupport.text(row, offset + 4, evaluator));
        request.setCurrency(excelSupport.text(row, offset + 5, evaluator));
        request.setMatPriceWithoutTax(excelSupport.decimal(row, offset + 6, evaluator, request.getCurrency()));
        request.setShortNameSupplier(excelSupport.text(row, offset + 7, evaluator));
        request.setRemark(excelSupport.text(row, offset + 8, evaluator));
        request.setUpdatedDate(excelSupport.localDate(row, offset + 9, evaluator));
        request.setUpdatedPic(excelSupport.text(row, offset + 10, evaluator));
        request.setStyleDesc(excelSupport.text(row, offset + 11, evaluator));
        return request;
    }

    private void validateReferences(
            List<ImportCandidate<MatInfoRequest>> rows,
            Map<String, CurrencyMaster> currencies,
            List<ImportRowError> errors
    ) {
        for (ImportCandidate<MatInfoRequest> row : rows) {
            String currency = MasterDataTextNormalizer.upper(row.getValue().getCurrency());
            if (currency != null && !currencies.containsKey(currency)) {
                errors.add(new ImportRowError(row.getRowNumber(), "currency", "Currency does not exist in Currency Master: " + currency));
            }
        }
    }

    private void validateEditedRows(
            List<ImportCandidate<KeyedMatInfoRequest>> rows,
            String buyer,
            Map<String, MatInfo> allTargets,
            Map<String, MatInfo> existingByIdentity,
            Map<String, CurrencyMaster> currencies,
            List<ImportRowError> errors
    ) {
        for (ImportCandidate<KeyedMatInfoRequest> row : rows) {
            KeyedMatInfoRequest keyed = row.getValue();
            MatInfo target = keyed.masterKey == null ? null : allTargets.get(keyed.masterKey);
            if ("CREATE".equals(keyed.action)) {
                if (keyed.masterKey != null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "CREATE must have a blank Key"));
                if (keyed.rowVersion != null) errors.add(new ImportRowError(row.getRowNumber(), "rowVersion", "CREATE must have a blank Row Version"));
            } else {
                if (keyed.masterKey == null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", keyed.action + " requires a Key"));
                else if (target == null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "Key does not exist: " + keyed.masterKey));
                else if (!buyer.equals(BuyerKeys.legacyDefault(target.getBuyerKey()))) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "Key belongs to another Buyer"));
                else if (!sameVersion(keyed.rowVersion, target.getVersion())) errors.add(new ImportRowError(row.getRowNumber(), "rowVersion", "Data has changed. Download a new edit file."));
            }
            if ("DELETE".equals(keyed.action) || keyed.request == null) continue;
            String currency = MasterDataTextNormalizer.upper(keyed.request.getCurrency());
            if (currency != null && !currencies.containsKey(currency)) errors.add(new ImportRowError(row.getRowNumber(), "currency", "Currency does not exist in Currency Master: " + currency));
        }
    }

    private void apply(MatInfo target, MatInfoRequest request, VendorCode vendor, CurrencyMaster currency) {
        validateRequest(request);
        if (vendor == null) throw new MasterDataValidationException("Vendor Code does not exist: " + request.getShortNameSupplier());
        if (currency == null) throw new MasterDataValidationException("Currency does not exist in Currency Master: " + request.getCurrency());

        String materialType = required(request.getMaterialType(), "Material type is required");
        String fullDescription = required(request.getMatFullDescription(), "MAT FULL DESCRIPTION is required");
        String matColor = required(request.getMatColor(), "MAT COLOR is required");
        String matUnit = required(request.getMatUnit(), "MAT UNIT is required").toUpperCase(Locale.ROOT);
        String currencyCode = required(request.getCurrency(), "Currency is required").toUpperCase(Locale.ROOT);
        String updatedPic = required(request.getUpdatedPic(), "Updated PIC is required");
        BigDecimal price = normalizePrice(request.getMatPriceWithoutTax());

        target.setBuyerKey(BuyerKeys.normalize(request.getBuyerKey()));
        target.setCheckingKey(identityKey(request));
        target.setFlexId(MasterDataTextNormalizer.trimToNull(request.getFlexId()));
        target.setMaterialType(materialType);
        target.setMaterialTypeKey(MasterDataTextNormalizer.materialGroupKey(materialType));
        target.setMatFullDescription(fullDescription);
        target.setMatColor(matColor);
        target.setMatUnit(matUnit);
        target.setCurrencyMasterId(currency.getId());
        target.setCurrency(currencyCode);
        target.setMatPriceWithoutTax(price);
        target.setShortNameSupplier(vendor.getShortNameSupplier());
        target.setShortNameSupplierKey(vendor.getShortNameSupplierKey());
        target.setRemark(MasterDataTextNormalizer.trimToNull(request.getRemark()));
        target.setUpdatedDate(request.getUpdatedDate() == null ? LocalDate.now() : request.getUpdatedDate());
        target.setUpdatedPic(updatedPic);
        target.setStyleDesc(MasterDataTextNormalizer.trimToNull(request.getStyleDesc()));
    }

    private void validateRequest(MatInfoRequest request) {
        if (request == null) throw new MasterDataValidationException("MAT_INFO request is required");
        String materialType = required(request.getMaterialType(), "Material type is required");
        required(request.getMatFullDescription(), "MAT FULL DESCRIPTION is required");
        required(request.getMatColor(), "MAT COLOR is required");
        String unit = required(request.getMatUnit(), "MAT UNIT is required").toUpperCase(Locale.ROOT);
        if (!unit.matches("^[A-Z0-9._/\\-]{1,20}$")) throw new MasterDataValidationException("MAT UNIT contains invalid characters");
        String currency = required(request.getCurrency(), "Currency is required").toUpperCase(Locale.ROOT);
        if (!currency.matches("^[A-Z]{3}$")) throw new MasterDataValidationException("Currency must be a 3-letter code");
        required(request.getShortNameSupplier(), "Short name supplier is required");
        required(request.getUpdatedPic(), "Updated PIC is required");
        normalizePrice(request.getMatPriceWithoutTax());
        if (MasterDataTextNormalizer.materialGroupKey(materialType) == null) throw new MasterDataValidationException("Material type is required");
    }

    private BigDecimal normalizePrice(BigDecimal price) {
        if (price == null) return null;
        if (price.signum() < 0) throw new MasterDataValidationException("MAT PRICE (W/O TAX) must not be negative");

        // Apache POI reads numeric Excel cells as double. Remove only microscopic binary floating-point
        // noise, while keeping the validation strict for values that genuinely contain > 6 decimals.
        BigDecimal roundedToAllowedScale = price.setScale(MAX_PRICE_SCALE, RoundingMode.HALF_UP);
        if (price.subtract(roundedToAllowedScale).abs().compareTo(EXCEL_FLOATING_POINT_EPSILON) <= 0) {
            price = roundedToAllowedScale;
        }

        BigDecimal normalized = price.stripTrailingZeros();
        int scale = Math.max(0, normalized.scale());
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (scale > MAX_PRICE_SCALE) throw new MasterDataValidationException("MAT PRICE (W/O TAX) supports at most 6 decimal places");
        if (integerDigits > MAX_PRICE_INTEGER_DIGITS) throw new MasterDataValidationException("MAT PRICE (W/O TAX) is too large");
        return MasterDataTextNormalizer.normalizeMoney(price);
    }

    private VendorCode requireVendor(String supplierName) {
        return vendorCodeService.resolveOrCreateFromMatInfo(supplierName);
    }

    private CurrencyMaster requireCurrency(String currencyCode) {
        try { return currencyMasterService.resolveCurrent(currencyCode); }
        catch (RuntimeException ex) { throw new MasterDataValidationException("Currency does not exist in Currency Master: " + currencyCode); }
    }

    private Map<String, VendorCode> vendorMap(Set<String> supplierNames) {
        return vendorCodeService.resolveOrCreateFromMatInfo(supplierNames);
    }

    private String identityKey(MatInfoRequest request) {
        if (request == null) throw new MasterDataValidationException("MAT_INFO request is required");
        return String.join("\u001F",
                identityText(request.getFlexId()),
                identityText(required(request.getMaterialType(), "Material type is required")),
                identityText(required(request.getMatFullDescription(), "MAT FULL DESCRIPTION is required")),
                identityText(required(request.getMatColor(), "MAT COLOR is required")),
                identityText(required(request.getMatUnit(), "MAT UNIT is required")),
                identityText(required(request.getCurrency(), "Currency is required")),
                identityDecimal(normalizePrice(request.getMatPriceWithoutTax())),
                identityText(required(request.getShortNameSupplier(), "Short name supplier is required"))
        );
    }

    private String identityKey(MatInfo item) {
        if (item == null) return null;
        if (MasterDataTextNormalizer.trimToNull(item.getMaterialType()) == null
                || MasterDataTextNormalizer.trimToNull(item.getMatFullDescription()) == null
                || MasterDataTextNormalizer.trimToNull(item.getMatColor()) == null
                || MasterDataTextNormalizer.trimToNull(item.getMatUnit()) == null
                || MasterDataTextNormalizer.trimToNull(item.getCurrency()) == null
                || MasterDataTextNormalizer.trimToNull(item.getShortNameSupplier()) == null) {
            return MasterDataTextNormalizer.key(item.getCheckingKey());
        }
        return String.join("\u001F",
                identityText(item.getFlexId()),
                identityText(item.getMaterialType()),
                identityText(item.getMatFullDescription()),
                identityText(item.getMatColor()),
                identityText(item.getMatUnit()),
                identityText(item.getCurrency()),
                identityDecimal(item.getMatPriceWithoutTax()),
                identityText(item.getShortNameSupplier())
        );
    }

    private int deduplicateStandardRows(List<ImportCandidate<MatInfoRequest>> rows) {
        LinkedHashMap<String, ImportCandidate<MatInfoRequest>> unique = new LinkedHashMap<>();
        int skipped = 0;
        for (ImportCandidate<MatInfoRequest> row : rows) {
            String key = identityKey(row.getValue());
            if (unique.putIfAbsent(key, row) != null) skipped++;
        }
        rows.clear();
        rows.addAll(unique.values());
        return skipped;
    }

    private int deduplicateEditedRows(
            List<ImportCandidate<KeyedMatInfoRequest>> rows,
            List<ImportRowError> errors
    ) {
        LinkedHashMap<String, ImportCandidate<KeyedMatInfoRequest>> unique = new LinkedHashMap<>();
        Map<String, String> fingerprintByMasterKey = new HashMap<>();
        int skipped = 0;
        for (ImportCandidate<KeyedMatInfoRequest> row : rows) {
            KeyedMatInfoRequest keyed = row.getValue();
            String dataIdentity = "DELETE".equals(keyed.action) || keyed.request == null
                    ? ""
                    : identityKey(keyed.request);
            String fingerprint = keyed.action + "|" + dataIdentity;
            if (keyed.masterKey != null) {
                String previous = fingerprintByMasterKey.putIfAbsent(keyed.masterKey, fingerprint);
                if (previous != null && !previous.equals(fingerprint)) {
                    errors.add(new ImportRowError(
                            row.getRowNumber(),
                            "masterKey",
                            "The same Key appears more than once with different data"
                    ));
                }
            }
            String rowIdentity = (keyed.masterKey == null ? "" : keyed.masterKey) + "|" + fingerprint;
            if (unique.putIfAbsent(rowIdentity, row) != null) skipped++;
        }
        rows.clear();
        rows.addAll(unique.values());
        return skipped;
    }

    private String identityText(String value) {
        String normalized = MasterDataTextNormalizer.key(value);
        return normalized == null ? "" : normalized;
    }

    private String identityDecimal(BigDecimal value) {
        if (value == null) return "";
        return value.stripTrailingZeros().toPlainString();
    }

    private Map<String, MatInfo> existingByIdentityKey(String buyer, Set<String> identities, boolean includeAll) {
        backfillLegacyIdentityKeys(buyer);
        List<MatInfo> rows = includeAll
                ? matInfoRepository.findByBuyerKey(buyer)
                : (identities == null || identities.isEmpty()
                    ? List.of()
                    : matInfoRepository.findAllByBuyerKeyAndCheckingKeyIn(buyer, identities));
        Map<String, MatInfo> result = new LinkedHashMap<>();
        for (MatInfo item : rows) {
            String derived = identityKey(item);
            if (derived != null) result.putIfAbsent(derived, item);
            String stored = MasterDataTextNormalizer.key(item.getCheckingKey());
            if (stored != null) result.putIfAbsent(stored, item);
        }
        return result;
    }

    private Collection<MatInfo> uniqueEntities(Map<String, MatInfo> values) {
        LinkedHashMap<String, MatInfo> unique = new LinkedHashMap<>();
        for (MatInfo item : values.values()) {
            if (item != null) unique.putIfAbsent(item.getId(), item);
        }
        return unique.values();
    }

    private void backfillLegacyIdentityKeys(String buyer) {
        if (!backfilledBuyers.add(buyer)) return;
        List<MatInfo> rows = matInfoRepository.findByBuyerKey(buyer);
        Set<String> occupied = rows.stream()
                .map(MatInfo::getCheckingKey).map(MasterDataTextNormalizer::key)
                .filter(value -> value != null).collect(Collectors.toSet());
        List<MatInfo> changed = new ArrayList<>();
        for (MatInfo item : rows) {
            String expected = identityKey(item);
            String current = MasterDataTextNormalizer.key(item.getCheckingKey());
            if (expected == null || expected.equals(current)) continue;
            if (occupied.contains(expected)) continue;
            if (current != null) occupied.remove(current);
            item.setCheckingKey(expected);
            occupied.add(expected);
            changed.add(item);
        }
        if (!changed.isEmpty()) saveAllWithDuplicateProtection(changed);
    }

    private Optional<MatInfo> findExisting(MatInfoRequest request) {
        String buyer = BuyerKeys.normalize(request.getBuyerKey());
        String key = identityKey(request);
        Optional<MatInfo> stored = matInfoRepository.findByBuyerKeyAndCheckingKey(buyer, key);
        if (stored.isPresent()) return stored;
        Query query = Query.query(Criteria.where("buyerKey").is(buyer));
        return mongoTemplate.find(query, MatInfo.class).stream().filter(item -> key.equals(identityKey(item))).findFirst();
    }

    private MatInfo saveWithDuplicateProtection(MatInfo entity) {
        try { return matInfoRepository.save(entity); }
        catch (DuplicateKeyException ex) { throw new MasterDataConflictException("MAT_INFO already exists or has a duplicate Key"); }
    }

    private void saveAllWithDuplicateProtection(List<MatInfo> rows) {
        if (rows.isEmpty()) return;
        try { matInfoRepository.saveAll(rows); }
        catch (DuplicateKeyException ex) { throw new MasterDataConflictException("MAT_INFO contains a duplicate business identity or Key"); }
    }

    private void backfillMissingMasterKeys(String buyer) {
        if (!masterKeyBackfilledBuyers.add(buyer)) return;
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("buyerKey").is(buyer),
                new Criteria().orOperator(
                        Criteria.where("masterKey").exists(false),
                        Criteria.where("masterKey").is(null),
                        Criteria.where("masterKey").is("")
                )
        ));
        List<MatInfo> missing = mongoTemplate.find(query, MatInfo.class);
        if (missing.isEmpty()) return;
        List<String> keys = reserveMasterKeys(missing.size());
        for (int i = 0; i < missing.size(); i++) missing.get(i).setMasterKey(keys.get(i));
        saveAllWithDuplicateProtection(missing);
    }

    private MatInfo ensureMasterKeyPersisted(MatInfo entity) {
        if (entity.getMasterKey() == null || entity.getMasterKey().isBlank()) {
            entity.setMasterKey(nextMasterKey());
            return saveWithDuplicateProtection(entity);
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
        MatInfo latest = mongoTemplate.findOne(query, MatInfo.class);
        return parseSequence(latest == null ? null : latest.getMasterKey());
    }

    private long parseSequence(String key) {
        if (key == null || !key.matches("^" + MASTER_KEY_PREFIX + "\\d+$")) return 0;
        try { return Long.parseLong(key.substring(MASTER_KEY_PREFIX.length())); }
        catch (NumberFormatException ex) { return 0; }
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
        if (!Set.of("CREATE", "UPDATE", "DELETE").contains(value)) throw new MasterDataValidationException("Action must be CREATE, UPDATE or DELETE");
        return value;
    }

    private Long optionalLong(String raw) {
        String clean = MasterDataTextNormalizer.trimToNull(raw);
        if (clean == null) return null;
        try { return new BigDecimal(clean.replace(",", "")).longValueExact(); }
        catch (RuntimeException ex) { throw new MasterDataValidationException("Row Version must be a whole number"); }
    }

    private boolean sameVersion(Long uploaded, Long current) {
        return uploaded == null ? current == null : uploaded.equals(current);
    }

    private void addContainsFilter(Query query, String field, String value) {
        String clean = MasterDataTextNormalizer.trimToNull(value);
        if (clean != null) query.addCriteria(Criteria.where(field).regex(Pattern.compile(Pattern.quote(clean), Pattern.CASE_INSENSITIVE)));
    }

    private String required(String value, String message) {
        String clean = MasterDataTextNormalizer.trimToNull(value);
        if (clean == null) throw new MasterDataValidationException(message);
        return clean;
    }

    private Pageable toPageable(int page, int size) {
        if (page < 0) throw new MasterDataValidationException("page must be >= 0");
        if (size < 1 || size > 200) throw new MasterDataValidationException("size must be between 1 and 200");
        return PageRequest.of(page, size);
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

    private void addBeanErrors(List<ImportRowError> errors, int row, Collection<String> messages) {
        for (String message : messages) {
            String[] parts = message.split(": ", 2);
            errors.add(new ImportRowError(row, parts.length == 2 ? parts[0] : "row", parts.length == 2 ? parts[1] : message));
        }
    }

    private String cleanMessage(Exception ex) {
        String message = MasterDataTextNormalizer.trimToNull(ex.getMessage());
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private static class KeyedMatInfoRequest {
        private String masterKey;
        private Long rowVersion;
        private String action;
        private MatInfoRequest request;
    }
}
