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
import org.bsl.sales.model.MatInfo;
import org.bsl.sales.model.VendorCode;
import org.bsl.sales.repository.MatInfoRepository;
import org.bsl.sales.repository.VendorCodeRepository;
import org.bsl.sales.support.ImportCandidate;
import org.bsl.sales.support.MasterDataBeanValidator;
import org.bsl.sales.support.MasterDataExcelSupport;
import org.bsl.sales.support.MasterDataEditWorkbookExporter;
import org.bsl.sales.support.MasterDataSequentialKey;
import org.bsl.sales.support.MasterDataTextNormalizer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * MAT_INFO service for the current MAT_INFO sheet:
 * Flex ID | Material type | Mat full description | Mat color | Mat unit |
 * Cur | Mat price (w/o tax) | Short name supplier | Remark | Updated date |
 * Updated pic | Style desc.
 *
 * Checking and DEV were removed from the business form. The legacy
 * checkingKey Mongo column is retained only as an internal unique key derived
 * from Mat Full Description + Mat Color, so old data remains compatible.
 */
@Service
public class MatInfoService {

    private static final String MASTER_DATA_NAME = "MAT_INFO";
    private static final String MASTER_KEY_PREFIX = "MI";
    private static final String VENDOR_MASTER_KEY_PREFIX = "VC";

    private final MatInfoRepository matInfoRepository;
    private final VendorCodeRepository vendorCodeRepository;
    private final CurrencyMasterService currencyMasterService;
    private final MasterDataBeanValidator beanValidator;
    private final MasterDataExcelSupport excelSupport;

    public MatInfoService(
            MatInfoRepository matInfoRepository,
            VendorCodeRepository vendorCodeRepository,
            CurrencyMasterService currencyMasterService,
            MasterDataBeanValidator beanValidator,
            MasterDataExcelSupport excelSupport
    ) {
        this.matInfoRepository = matInfoRepository;
        this.vendorCodeRepository = vendorCodeRepository;
        this.currencyMasterService = currencyMasterService;
        this.beanValidator = beanValidator;
        this.excelSupport = excelSupport;
    }

    public MatInfo create(MatInfoRequest request) {
        validateRequest(request);

        if (findExisting(request).isPresent()) {
            throw new MasterDataConflictException(
                    "MAT_INFO already exists for the same Mat Full Description and Mat Color"
            );
        }

        MatInfo entity = new MatInfo();
        apply(entity, request);
        assignMasterKeyIfMissing(entity);

        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        return saveWithDuplicateProtection(entity);
    }

    /**
     * Separate filters are used instead of one all-column keyword search.
     * All non-blank filters are combined with AND logic.
     */
    public Page<MatInfo> list(
            String masterKey,
            String flexId,
            String materialType,
            String matFullDescription,
            String matColor,
            String shortNameSupplier,
            int page,
            int size
    ) {
        backfillMissingMasterKeys();
        Pageable pageable = toPageable(page, size);

        String masterKeySearch = MasterDataTextNormalizer.key(masterKey);
        String flexIdSearch = MasterDataTextNormalizer.key(flexId);
        String materialTypeSearch = MasterDataTextNormalizer.key(materialType);
        String descriptionSearch = MasterDataTextNormalizer.key(matFullDescription);
        String colorSearch = MasterDataTextNormalizer.key(matColor);
        String supplierSearch = MasterDataTextNormalizer.key(shortNameSupplier);

        List<MatInfo> filtered = matInfoRepository.findAll().stream()
                .filter(item -> contains(item.getMasterKey(), masterKeySearch))
                .filter(item -> contains(item.getFlexId(), flexIdSearch))
                .filter(item -> contains(item.getMaterialType(), materialTypeSearch))
                .filter(item -> contains(item.getMatFullDescription(), descriptionSearch))
                .filter(item -> contains(item.getMatColor(), colorSearch))
                .filter(item -> contains(item.getShortNameSupplier(), supplierSearch))
                .sorted(
                        Comparator.comparing(
                                MatInfo::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())
                        ).thenComparing(
                                MatInfo::getMatFullDescription,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                        )
                )
                .collect(Collectors.toList());

        return page(filtered, pageable);
    }

    public MatInfo getById(String id) {
        MatInfo entity = matInfoRepository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("MAT_INFO not found"));
        return ensureMasterKeyPersisted(entity);
    }

    public MatInfo update(String id, MatInfoRequest request) {
        validateRequest(request);

        MatInfo existing = getById(id);
        Optional<MatInfo> duplicate = findExisting(request);

        if (duplicate.isPresent() && !existing.getId().equals(duplicate.get().getId())) {
            throw new MasterDataConflictException(
                    "MAT_INFO already exists for the same Mat Full Description and Mat Color"
            );
        }

        apply(existing, request);
        assignMasterKeyIfMissing(existing);
        existing.setUpdatedAt(LocalDateTime.now());

        return saveWithDuplicateProtection(existing);
    }

    public void delete(String id) {
        matInfoRepository.delete(getById(id));
    }

    public MasterDataImportResult upload(MultipartFile file, ImportMode mode) {
        ImportMode effectiveMode = mode == null ? ImportMode.UPSERT : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<MatInfoRequest>> rows = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);

            excelSupport.requireHeaders(
                    sheet,
                    evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "FLEX ID"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Material type"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "MAT FULL DESCRIPTION"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "MAT COLOR"),
                    new MasterDataExcelSupport.HeaderRequirement(4, "MAT UNIT"),
                    new MasterDataExcelSupport.HeaderRequirement(5, "CUR"),
                    new MasterDataExcelSupport.HeaderRequirement(6, "MAT PRICE (W/O TAX)"),
                    new MasterDataExcelSupport.HeaderRequirement(7, "Short name supplier"),
                    new MasterDataExcelSupport.HeaderRequirement(8, "Remark"),
                    new MasterDataExcelSupport.HeaderRequirement(9, "Updated Date"),
                    new MasterDataExcelSupport.HeaderRequirement(10, "Updated PIC"),
                    new MasterDataExcelSupport.HeaderRequirement(11, "Style Desc")
            );

            Set<String> incomingIdentityKeys = new HashSet<>();

            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);

                /*
                 * Excel often contains formatted rows after the last material.
                 * Stop at the first row where every required business field is
                 * blank. A partially filled row remains an error.
                 */
                if (isEndOfMatInfoData(row, evaluator)) {
                    break;
                }

                totalRows++;
                int excelRow = rowIndex + 1;

                try {
                    MatInfoRequest request = toRequest(row, evaluator);
                    List<String> beanErrors = beanValidator.validate(request);
                    addBeanErrors(errors, excelRow, beanErrors);

                    if (beanErrors.isEmpty()) {
                        validateRequest(request);
                    }

                    String identityKey = identityKey(request);
                    if (!incomingIdentityKeys.add(identityKey)) {
                        errors.add(new ImportRowError(
                                excelRow,
                                "matInfo",
                                "Duplicate Mat Full Description and Mat Color inside uploaded file"
                        ));
                    }

                    rows.add(new ImportCandidate<>(excelRow, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (MasterDataValidationException ex) {
            errors.add(new ImportRowError(1, "file", cleanMessage(ex)));
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import MAT_INFO: " + cleanMessage(ex)));
        }

        Map<String, MatInfo> existingByKey = existingByIdentityKey();
        validateBeforeApply(effectiveMode, rows, existingByKey, errors);

        if (!errors.isEmpty()) {
            return MasterDataImportResult.rejected(MASTER_DATA_NAME, effectiveMode, totalRows, errors);
        }

        /*
         * Only after every row is valid do we add missing Vendor Code names.
         * This avoids creating a stray vendor if another MAT_INFO row is invalid.
         */
        ensureVendorCodesExist(rows);

        MasterDataImportResult result = new MasterDataImportResult();
        result.setMasterData(MASTER_DATA_NAME);
        result.setMode(effectiveMode);
        result.setApplied(true);
        result.setTotalRows(totalRows);
        result.setValidRows(rows.size());

        if (effectiveMode == ImportMode.REPLACE_ALL) {
            matInfoRepository.deleteAll();

            for (ImportCandidate<MatInfoRequest> row : rows) {
                MatInfo entity = new MatInfo();
                apply(entity, row.getValue());
                assignMasterKeyIfMissing(entity);

                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);

                saveWithDuplicateProtection(entity);
                result.setCreated(result.getCreated() + 1);
            }

            return result;
        }

        for (ImportCandidate<MatInfoRequest> row : rows) {
            MatInfoRequest request = row.getValue();
            String key = identityKey(request);
            MatInfo existing = existingByKey.get(key);

            if (existing != null) {
                apply(existing, request);
                assignMasterKeyIfMissing(existing);
                existing.setUpdatedAt(LocalDateTime.now());

                saveWithDuplicateProtection(existing);
                result.setUpdated(result.getUpdated() + 1);
            } else {
                MatInfo entity = new MatInfo();
                apply(entity, request);
                assignMasterKeyIfMissing(entity);

                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);

                MatInfo saved = saveWithDuplicateProtection(entity);
                existingByKey.put(key, saved);
                result.setCreated(result.getCreated() + 1);
            }
        }

        return result;
    }


    public byte[] exportForEdit() {
        backfillMissingMasterKeys();
        return MasterDataEditWorkbookExporter.matInfos(matInfoRepository.findAll());
    }

    /**
     * Edited-workbook upload. Existing MI key updates that exact row. Blank
     * key creates a new MAT_INFO row and receives the next MI key.
     */
    public MasterDataImportResult uploadEdited(MultipartFile file) {
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<KeyedMatInfoRequest>> rows = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(
                    sheet,
                    evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Key"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "FLEX ID"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "Material type"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "MAT FULL DESCRIPTION"),
                    new MasterDataExcelSupport.HeaderRequirement(4, "MAT COLOR"),
                    new MasterDataExcelSupport.HeaderRequirement(5, "MAT UNIT"),
                    new MasterDataExcelSupport.HeaderRequirement(6, "CUR"),
                    new MasterDataExcelSupport.HeaderRequirement(7, "MAT PRICE (W/O TAX)"),
                    new MasterDataExcelSupport.HeaderRequirement(8, "Short name supplier"),
                    new MasterDataExcelSupport.HeaderRequirement(9, "Remark"),
                    new MasterDataExcelSupport.HeaderRequirement(10, "Updated Date"),
                    new MasterDataExcelSupport.HeaderRequirement(11, "Updated PIC"),
                    new MasterDataExcelSupport.HeaderRequirement(12, "Style Desc")
            );

            Set<String> incomingMasterKeys = new HashSet<>();
            Set<String> incomingIdentityKeys = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (isEndOfMatInfoEditData(row, evaluator)) {
                    break;
                }

                totalRows++;
                int excelRow = rowIndex + 1;
                try {
                    KeyedMatInfoRequest keyed = new KeyedMatInfoRequest();
                    keyed.masterKey = normalizeUploadedMasterKey(excelSupport.text(row, 0, evaluator));
                    keyed.request = toEditedRequest(row, evaluator);

                    List<String> beanErrors = beanValidator.validate(keyed.request);
                    addBeanErrors(errors, excelRow, beanErrors);

                    if (beanErrors.isEmpty()) {
                        validateRequest(keyed.request);
                    }

                    String identityKey = identityKey(keyed.request);
                    if (!incomingIdentityKeys.add(identityKey)) {
                        errors.add(new ImportRowError(
                                excelRow,
                                "matInfo",
                                "Duplicate Mat Full Description and Mat Color inside uploaded file"
                        ));
                    }

                    if (keyed.masterKey != null && !incomingMasterKeys.add(keyed.masterKey)) {
                        errors.add(new ImportRowError(excelRow, "masterKey", "Duplicate Key inside uploaded file"));
                    }

                    rows.add(new ImportCandidate<>(excelRow, keyed));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (MasterDataValidationException ex) {
            errors.add(new ImportRowError(1, "file", cleanMessage(ex)));
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import edited MAT_INFO: " + cleanMessage(ex)));
        }

        validateEditedRows(rows, errors);
        if (!errors.isEmpty()) {
            return MasterDataImportResult.rejected(MASTER_DATA_NAME, ImportMode.UPSERT, totalRows, errors);
        }

        ensureVendorCodesExistForEdited(rows);

        MasterDataImportResult result = new MasterDataImportResult();
        result.setMasterData(MASTER_DATA_NAME);
        result.setMode(ImportMode.UPSERT);
        result.setApplied(true);
        result.setTotalRows(totalRows);
        result.setValidRows(rows.size());

        for (ImportCandidate<KeyedMatInfoRequest> row : rows) {
            KeyedMatInfoRequest keyed = row.getValue();
            Optional<MatInfo> existing = keyed.masterKey == null
                    ? Optional.empty()
                    : matInfoRepository.findByMasterKey(keyed.masterKey);

            if (existing.isPresent()) {
                apply(existing.get(), keyed.request);
                assignMasterKeyIfMissing(existing.get());
                existing.get().setUpdatedAt(LocalDateTime.now());
                saveWithDuplicateProtection(existing.get());
                result.setUpdated(result.getUpdated() + 1);
            } else {
                MatInfo entity = new MatInfo();
                apply(entity, keyed.request);
                entity.setMasterKey(keyed.masterKey);
                assignMasterKeyIfMissing(entity);
                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                saveWithDuplicateProtection(entity);
                result.setCreated(result.getCreated() + 1);
            }
        }

        return result;
    }

    private MatInfoRequest toRequest(Row row, FormulaEvaluator evaluator) {
        MatInfoRequest request = new MatInfoRequest();
        request.setFlexId(excelSupport.text(row, 0, evaluator));
        request.setMaterialType(excelSupport.text(row, 1, evaluator));
        request.setMatFullDescription(excelSupport.text(row, 2, evaluator));
        request.setMatColor(excelSupport.text(row, 3, evaluator));
        request.setMatUnit(excelSupport.text(row, 4, evaluator));
        request.setCurrency(excelSupport.text(row, 5, evaluator));
        request.setMatPriceWithoutTax(excelSupport.decimal(row, 6, evaluator));
        request.setShortNameSupplier(excelSupport.text(row, 7, evaluator));
        request.setRemark(excelSupport.text(row, 8, evaluator));
        request.setUpdatedDate(excelSupport.localDate(row, 9, evaluator));
        request.setUpdatedPic(excelSupport.text(row, 10, evaluator));
        request.setStyleDesc(excelSupport.text(row, 11, evaluator));
        return request;
    }


    private MatInfoRequest toEditedRequest(Row row, FormulaEvaluator evaluator) {
        MatInfoRequest request = new MatInfoRequest();
        request.setFlexId(excelSupport.text(row, 1, evaluator));
        request.setMaterialType(excelSupport.text(row, 2, evaluator));
        request.setMatFullDescription(excelSupport.text(row, 3, evaluator));
        request.setMatColor(excelSupport.text(row, 4, evaluator));
        request.setMatUnit(excelSupport.text(row, 5, evaluator));
        request.setCurrency(excelSupport.text(row, 6, evaluator));
        request.setMatPriceWithoutTax(excelSupport.decimal(row, 7, evaluator));
        request.setShortNameSupplier(excelSupport.text(row, 8, evaluator));
        request.setRemark(excelSupport.text(row, 9, evaluator));
        request.setUpdatedDate(excelSupport.localDate(row, 10, evaluator));
        request.setUpdatedPic(excelSupport.text(row, 11, evaluator));
        request.setStyleDesc(excelSupport.text(row, 12, evaluator));
        return request;
    }

    private void validateEditedRows(
            List<ImportCandidate<KeyedMatInfoRequest>> rows,
            List<ImportRowError> errors
    ) {
        Set<String> currencyKeys = currencyMasterService.currentCurrencyCodes();
        Map<String, MatInfo> existingByMasterKey = new HashMap<>();
        for (MatInfo item : matInfoRepository.findAll()) {
            if (item.getMasterKey() != null && !item.getMasterKey().isBlank()) {
                existingByMasterKey.put(item.getMasterKey().trim().toUpperCase(Locale.ROOT), item);
            }
        }
        Map<String, MatInfo> existingByIdentityKey = existingByIdentityKey();

        for (ImportCandidate<KeyedMatInfoRequest> row : rows) {
            KeyedMatInfoRequest keyed = row.getValue();
            if (keyed == null || keyed.request == null) {
                continue;
            }

            String currency = MasterDataTextNormalizer.upper(keyed.request.getCurrency());
            if (currency != null && !currencyKeys.contains(currency)) {
                errors.add(new ImportRowError(
                        row.getRowNumber(),
                        "currency",
                        "Currency does not exist in Currency Master: " + keyed.request.getCurrency()
                ));
            }

            MatInfo target = keyed.masterKey == null ? null : existingByMasterKey.get(keyed.masterKey);
            try {
                MatInfo duplicate = existingByIdentityKey.get(identityKey(keyed.request));
                if (duplicate != null && (target == null || !duplicate.getId().equals(target.getId()))) {
                    errors.add(new ImportRowError(
                            row.getRowNumber(),
                            "matInfo",
                            "MAT_INFO already exists. Keep its Key to update the existing row, or change Mat Full Description / Mat Color."
                    ));
                }
            } catch (RuntimeException ignored) {
                // Required field errors are already collected per row.
            }
        }
    }

    private void ensureVendorCodesExistForEdited(List<ImportCandidate<KeyedMatInfoRequest>> rows) {
        Set<String> supplierNames = new LinkedHashSet<>();
        for (ImportCandidate<KeyedMatInfoRequest> row : rows) {
            String supplier = MasterDataTextNormalizer.trimToNull(row.getValue().request.getShortNameSupplier());
            if (supplier != null) {
                supplierNames.add(supplier);
            }
        }
        for (String supplier : supplierNames) {
            ensureVendorCodeExists(supplier);
        }
    }

    private String normalizeUploadedMasterKey(String raw) {
        String value = MasterDataTextNormalizer.trimToNull(raw);
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.matches("^" + MASTER_KEY_PREFIX + "\\d+$")) {
            throw new MasterDataValidationException("Invalid Key format: " + value + ". Expected " + MASTER_KEY_PREFIX + "000001 style.");
        }
        return normalized;
    }

    private void validateBeforeApply(
            ImportMode mode,
            List<ImportCandidate<MatInfoRequest>> rows,
            Map<String, MatInfo> existingByKey,
            List<ImportRowError> errors
    ) {
        Set<String> currencyKeys = currencyMasterService.currentCurrencyCodes();

        for (ImportCandidate<MatInfoRequest> row : rows) {
            MatInfoRequest request = row.getValue();

            String currency = MasterDataTextNormalizer.upper(request.getCurrency());
            if (currency != null && !currencyKeys.contains(currency)) {
                errors.add(new ImportRowError(
                        row.getRowNumber(),
                        "currency",
                        "Currency does not exist in Currency Master: " + request.getCurrency()
                ));
            }

            if (mode == ImportMode.CREATE_ONLY && existingByKey.containsKey(identityKey(request))) {
                errors.add(new ImportRowError(
                        row.getRowNumber(),
                        "matInfo",
                        "MAT_INFO already exists; CREATE_ONLY does not allow updates"
                ));
            }
        }
    }

    private void ensureVendorCodesExist(List<ImportCandidate<MatInfoRequest>> rows) {
        Set<String> supplierNames = new LinkedHashSet<>();

        for (ImportCandidate<MatInfoRequest> row : rows) {
            String supplier = MasterDataTextNormalizer.trimToNull(row.getValue().getShortNameSupplier());
            if (supplier != null) {
                supplierNames.add(supplier);
            }
        }

        for (String supplier : supplierNames) {
            ensureVendorCodeExists(supplier);
        }
    }

    /**
     * MAT_INFO can introduce a supplier. If Vendor Code does not exist, a
     * minimal Vendor Code record is created with its Short Name Supplier only.
     */
    private VendorCode ensureVendorCodeExists(String rawSupplierName) {
        String supplierName = required(rawSupplierName, "Short name supplier is required");
        String supplierKey = MasterDataTextNormalizer.key(supplierName);

        Optional<VendorCode> existing = vendorCodeRepository.findByShortNameSupplierKey(supplierKey);
        if (existing.isPresent()) {
            VendorCode vendor = existing.get();
            if (vendor.getMasterKey() == null || vendor.getMasterKey().isBlank()) {
                vendor.setMasterKey(nextVendorMasterKey());
                return vendorCodeRepository.save(vendor);
            }
            return vendor;
        }

        LocalDateTime now = LocalDateTime.now();
        VendorCode created = new VendorCode();
        created.setMasterKey(nextVendorMasterKey());
        created.setShortNameSupplier(supplierName);
        created.setShortNameSupplierKey(supplierKey);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);

        try {
            return vendorCodeRepository.save(created);
        } catch (DuplicateKeyException ex) {
            return vendorCodeRepository.findByShortNameSupplierKey(supplierKey)
                    .orElseThrow(() -> ex);
        }
    }

    private AtomicLong masterKeyCounter() {
        return MasterDataSequentialKey.counter(
                matInfoRepository.findAll().stream()
                        .map(MatInfo::getMasterKey)
                        .collect(Collectors.toSet()),
                MASTER_KEY_PREFIX
        );
    }

    private void assignMasterKeyIfMissing(MatInfo entity) {
        if (entity == null) {
            return;
        }
        MasterDataSequentialKey.ensure(
                entity::getMasterKey,
                entity::setMasterKey,
                masterKeyCounter(),
                MASTER_KEY_PREFIX
        );
    }

    private MatInfo ensureMasterKeyPersisted(MatInfo entity) {
        if (entity == null || (entity.getMasterKey() != null && !entity.getMasterKey().isBlank())) {
            return entity;
        }
        assignMasterKeyIfMissing(entity);
        return saveWithDuplicateProtection(entity);
    }

    private void backfillMissingMasterKeys() {
        AtomicLong counter = masterKeyCounter();
        for (MatInfo entity : matInfoRepository.findAll()) {
            if (entity.getMasterKey() == null || entity.getMasterKey().isBlank()) {
                MasterDataSequentialKey.ensure(entity::getMasterKey, entity::setMasterKey, counter, MASTER_KEY_PREFIX);
                saveWithDuplicateProtection(entity);
            }
        }
    }

    private String nextVendorMasterKey() {
        AtomicLong counter = MasterDataSequentialKey.counter(
                vendorCodeRepository.findAll().stream()
                        .map(VendorCode::getMasterKey)
                        .collect(Collectors.toSet()),
                VENDOR_MASTER_KEY_PREFIX
        );
        return MasterDataSequentialKey.next(counter, VENDOR_MASTER_KEY_PREFIX);
    }


    /**
     * End-of-data rule for the edited MAT_INFO workbook.
     * Column 0 is the optional Key. If only formatting remains after the last
     * data row, the Key and required business fields are all blank, so import
     * stops. If a user leaves a Key but deletes required data, the row is still
     * processed and validation will report the missing fields.
     */
    private boolean isEndOfMatInfoEditData(Row row, FormulaEvaluator evaluator) {
        if (row == null) {
            return true;
        }

        int[] importantColumns = {
                0,  // Key
                2,  // Material type
                3,  // Mat full description
                4,  // Mat color
                5,  // Mat unit
                6,  // Cur
                8,  // Short name supplier
                11  // Updated pic
        };

        for (int column : importantColumns) {
            if (MasterDataTextNormalizer.trimToNull(excelSupport.text(row, column, evaluator)) != null) {
                return false;
            }
        }

        return true;
    }

    /**
     * End-of-data rule for the current 12-column MAT_INFO sheet.
     * Required columns: Material Type, Mat Full Description, Mat Color,
     * Mat Unit, Cur, Short Name Supplier and Updated Pic.
     */
    private boolean isEndOfMatInfoData(Row row, FormulaEvaluator evaluator) {
        if (row == null) {
            return true;
        }

        int[] requiredColumns = {
                1,  // Material type
                2,  // Mat full description
                3,  // Mat color
                4,  // Mat unit
                5,  // Cur
                7,  // Short name supplier
                10  // Updated pic
        };

        for (int column : requiredColumns) {
            if (MasterDataTextNormalizer.trimToNull(excelSupport.text(row, column, evaluator)) != null) {
                return false;
            }
        }

        return true;
    }

    private void apply(MatInfo target, MatInfoRequest request) {
        validateRequest(request);

        String materialType = required(request.getMaterialType(), "Material type is required");
        String fullDescription = required(request.getMatFullDescription(), "MAT FULL DESCRIPTION is required");
        String matColor = required(request.getMatColor(), "MAT COLOR is required");
        String matUnit = required(request.getMatUnit(), "MAT UNIT is required").toUpperCase(Locale.ROOT);
        String currency = required(request.getCurrency(), "Currency is required").toUpperCase(Locale.ROOT);
        String supplier = required(request.getShortNameSupplier(), "Short name supplier is required");
        String updatedPic = required(request.getUpdatedPic(), "Updated PIC is required");

        org.bsl.sales.model.CurrencyMaster currentCurrency;
        try {
            currentCurrency = currencyMasterService.resolveCurrent(currency);
        } catch (RuntimeException ex) {
            throw new MasterDataValidationException(
                    "Currency does not exist in Currency Master: " + currency
            );
        }

        if (!matUnit.matches("^[A-Z0-9._/\\-]{1,20}$")) {
            throw new MasterDataValidationException("MAT UNIT contains invalid characters");
        }

        BigDecimal price = request.getMatPriceWithoutTax();
        if (price != null && price.signum() < 0) {
            throw new MasterDataValidationException("MAT PRICE (W/O TAX) must not be negative");
        }

        ensureVendorCodeExists(supplier);

        target.setCheckingKey(identityKey(request));
        target.setFlexId(MasterDataTextNormalizer.trimToNull(request.getFlexId()));
        target.setMaterialType(materialType);
        target.setMaterialTypeKey(MasterDataTextNormalizer.materialGroupKey(materialType));
        target.setMatFullDescription(fullDescription);
        target.setMatColor(matColor);
        target.setMatUnit(matUnit);
        target.setCurrencyMasterId(currentCurrency.getId());
        target.setCurrency(currency);
        target.setMatPriceWithoutTax(MasterDataTextNormalizer.normalizeMoney(price));
        target.setShortNameSupplier(supplier);
        target.setShortNameSupplierKey(MasterDataTextNormalizer.key(supplier));
        target.setRemark(MasterDataTextNormalizer.trimToNull(request.getRemark()));
        target.setUpdatedDate(request.getUpdatedDate() == null ? LocalDate.now() : request.getUpdatedDate());
        target.setUpdatedPic(updatedPic);
        target.setStyleDesc(MasterDataTextNormalizer.trimToNull(request.getStyleDesc()));
    }

    private void validateRequest(MatInfoRequest request) {
        if (request == null) {
            throw new MasterDataValidationException("MAT_INFO request is required");
        }

        String materialType = required(request.getMaterialType(), "Material type is required");
        required(request.getMatFullDescription(), "MAT FULL DESCRIPTION is required");
        required(request.getMatColor(), "MAT COLOR is required");

        String unit = required(request.getMatUnit(), "MAT UNIT is required").toUpperCase(Locale.ROOT);
        if (!unit.matches("^[A-Z0-9._/\\-]{1,20}$")) {
            throw new MasterDataValidationException("MAT UNIT contains invalid characters");
        }

        String currency = required(request.getCurrency(), "Currency is required").toUpperCase(Locale.ROOT);
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new MasterDataValidationException("Currency must be a 3-letter code");
        }

        required(request.getShortNameSupplier(), "Short name supplier is required");
        required(request.getUpdatedPic(), "Updated PIC is required");

        if (request.getMatPriceWithoutTax() != null && request.getMatPriceWithoutTax().signum() < 0) {
            throw new MasterDataValidationException("MAT PRICE (W/O TAX) must not be negative");
        }

        if (MasterDataTextNormalizer.materialGroupKey(materialType) == null) {
            throw new MasterDataValidationException("Material type is required");
        }
    }

    /**
     * Business identity after Checking was removed.
     * The same description + color represents the same MAT_INFO record, which
     * matches the former Checking fallback behavior.
     */
    private String identityKey(MatInfoRequest request) {
        String description = required(
                request == null ? null : request.getMatFullDescription(),
                "MAT FULL DESCRIPTION is required"
        );
        String color = required(
                request == null ? null : request.getMatColor(),
                "MAT COLOR is required"
        );

        return MasterDataTextNormalizer.key(description + " " + color);
    }

    private String identityKey(MatInfo item) {
        if (item == null) {
            return null;
        }

        String description = MasterDataTextNormalizer.trimToNull(item.getMatFullDescription());
        String color = MasterDataTextNormalizer.trimToNull(item.getMatColor());

        if (description == null || color == null) {
            return MasterDataTextNormalizer.key(item.getCheckingKey());
        }

        return MasterDataTextNormalizer.key(description + " " + color);
    }

    private Map<String, MatInfo> existingByIdentityKey() {
        Map<String, MatInfo> result = new HashMap<>();

        for (MatInfo item : matInfoRepository.findAll()) {
            String derivedKey = identityKey(item);
            if (derivedKey != null) {
                result.putIfAbsent(derivedKey, item);
            }

            String storedKey = MasterDataTextNormalizer.key(item.getCheckingKey());
            if (storedKey != null) {
                result.putIfAbsent(storedKey, item);
            }
        }

        return result;
    }

    private Optional<MatInfo> findExisting(MatInfoRequest request) {
        String key = identityKey(request);

        Optional<MatInfo> byStoredKey = matInfoRepository.findByCheckingKey(key);
        if (byStoredKey.isPresent()) {
            return byStoredKey;
        }

        return matInfoRepository.findAll().stream()
                .filter(item -> key.equals(identityKey(item)))
                .findFirst();
    }

    private MatInfo saveWithDuplicateProtection(MatInfo entity) {
        try {
            return matInfoRepository.save(entity);
        } catch (DuplicateKeyException ex) {
            throw new MasterDataConflictException(
                    "MAT_INFO already exists for the same Mat Full Description and Mat Color"
            );
        }
    }

    private String required(String value, String message) {
        String result = MasterDataTextNormalizer.trimToNull(value);
        if (result == null) {
            throw new MasterDataValidationException(message);
        }
        return result;
    }

    private boolean contains(String value, String normalizedSearch) {
        if (normalizedSearch == null) {
            return true;
        }
        return value != null && value.toUpperCase(Locale.ROOT).contains(normalizedSearch);
    }

    private Pageable toPageable(int page, int size) {
        if (page < 0) {
            throw new MasterDataValidationException("page must be >= 0");
        }
        if (size < 1 || size > 200) {
            throw new MasterDataValidationException("size must be between 1 and 200");
        }
        return PageRequest.of(page, size);
    }

    private <T> Page<T> page(List<T> items, Pageable pageable) {
        int from = Math.min((int) pageable.getOffset(), items.size());
        int to = Math.min(from + pageable.getPageSize(), items.size());
        return new PageImpl<>(items.subList(from, to), pageable, items.size());
    }

    private void addBeanErrors(List<ImportRowError> errors, int row, List<String> messages) {
        for (String message : messages) {
            String[] parts = message.split(": ", 2);
            errors.add(new ImportRowError(
                    row,
                    parts.length == 2 ? parts[0] : "row",
                    parts.length == 2 ? parts[1] : message
            ));
        }
    }

    private String cleanMessage(Exception ex) {
        String message = MasterDataTextNormalizer.trimToNull(ex.getMessage());
        return message == null ? ex.getClass().getSimpleName() : message;
    }


    private static class KeyedMatInfoRequest {
        private String masterKey;
        private MatInfoRequest request;
    }

}
