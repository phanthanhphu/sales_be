package org.bsl.sales.service;

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
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.model.MprLine;
import org.bsl.sales.model.VendorCode;
import org.bsl.sales.repository.MatInfoRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.bsl.sales.repository.VendorCodeRepository;
import org.bsl.sales.support.ImportCandidate;
import org.bsl.sales.support.MasterDataBeanValidator;
import org.bsl.sales.support.MasterDataExcelSupport;
import org.bsl.sales.support.MasterDataEditWorkbookExporter;
import org.bsl.sales.support.MasterDataSequentialKey;
import org.bsl.sales.support.MasterDataTextNormalizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class VendorCodeService {

    private static final String MASTER_DATA_NAME = "VENDOR CODE";
    private static final String MASTER_KEY_PREFIX = "VC";

    private final VendorCodeRepository vendorCodeRepository;
    private final MatInfoRepository matInfoRepository;
    private final MprDocumentRepository mprDocumentRepository;
    private final MasterDataBeanValidator beanValidator;
    private final MasterDataExcelSupport excelSupport;

    public VendorCodeService(
            VendorCodeRepository vendorCodeRepository,
            MatInfoRepository matInfoRepository,
            MprDocumentRepository mprDocumentRepository,
            MasterDataBeanValidator beanValidator,
            MasterDataExcelSupport excelSupport
    ) {
        this.vendorCodeRepository = vendorCodeRepository;
        this.matInfoRepository = matInfoRepository;
        this.mprDocumentRepository = mprDocumentRepository;
        this.beanValidator = beanValidator;
        this.excelSupport = excelSupport;
    }

    public VendorCode create(VendorCodeRequest request) {
        String key = requireSupplierKey(request);
        if (vendorCodeRepository.existsByShortNameSupplierKey(key)) {
            throw new MasterDataConflictException("Short name supplier already exists: " + request.getShortNameSupplier());
        }

        VendorCode vendorCode = new VendorCode();
        apply(vendorCode, request);
        assignMasterKeyIfMissing(vendorCode);
        LocalDateTime now = LocalDateTime.now();
        vendorCode.setCreatedAt(now);
        vendorCode.setUpdatedAt(now);
        return vendorCodeRepository.save(vendorCode);
    }

    /**
     * Field-specific filters avoid one broad all-column keyword search.
     * All supplied filters are combined with AND logic.
     */
    public Page<VendorCode> list(
            String masterKey,
            String shortNameSupplier,
            String vendorCode,
            String vendorName,
            String matCharger,
            int page,
            int size
    ) {
        backfillMissingMasterKeys();
        Pageable pageable = toPageable(page, size);
        String masterKeySearch = MasterDataTextNormalizer.key(masterKey);
        String shortNameSearch = MasterDataTextNormalizer.key(shortNameSupplier);
        String vendorCodeSearch = MasterDataTextNormalizer.key(vendorCode);
        String vendorNameSearch = MasterDataTextNormalizer.key(vendorName);
        String chargerSearch = MasterDataTextNormalizer.key(matCharger);

        List<VendorCode> filtered = vendorCodeRepository.findAll().stream()
                .filter(item -> contains(item.getMasterKey(), masterKeySearch))
                .filter(item -> contains(item.getShortNameSupplier(), shortNameSearch))
                .filter(item -> contains(item.getVendorCode(), vendorCodeSearch))
                .filter(item -> contains(item.getVendorName(), vendorNameSearch))
                .filter(item -> contains(item.getMatCharger(), chargerSearch))
                .sorted(Comparator.comparing(VendorCode::getShortNameSupplier, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        return page(filtered, pageable);
    }

    /**
     * Compatibility overload for the legacy Supplier endpoint.
     * The main Vendor Code screen uses the field-specific method above.
     */
    public Page<VendorCode> list(String keyword, int page, int size) {
        String search = MasterDataTextNormalizer.trimToNull(keyword);
        if (search == null) {
            return list(null, null, null, null, null, page, size);
        }

        backfillMissingMasterKeys();
        Pageable pageable = toPageable(page, size);
        String normalized = MasterDataTextNormalizer.key(search);
        List<VendorCode> filtered = vendorCodeRepository.findAll().stream()
                .filter(item -> contains(item.getMasterKey(), normalized)
                        || contains(item.getShortNameSupplier(), normalized)
                        || contains(item.getVendorCode(), normalized)
                        || contains(item.getVendorName(), normalized)
                        || contains(item.getMatCharger(), normalized))
                .sorted(Comparator.comparing(VendorCode::getShortNameSupplier, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        return page(filtered, pageable);
    }

    public VendorCode getById(String id) {
        VendorCode entity = vendorCodeRepository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Vendor Code not found"));
        return ensureMasterKeyPersisted(entity);
    }

    public VendorCode resolve(String shortNameSupplier) {
        String key = MasterDataTextNormalizer.key(shortNameSupplier);
        if (key == null) {
            throw new MasterDataValidationException("shortNameSupplier is required");
        }
        VendorCode entity = vendorCodeRepository.findByShortNameSupplierKey(key)
                .orElseThrow(() -> new MasterDataNotFoundException("Supplier not found: " + shortNameSupplier));
        return ensureMasterKeyPersisted(entity);
    }

    public VendorCode update(String id, VendorCodeRequest request) {
        VendorCode existing = getById(id);
        String nextKey = requireSupplierKey(request);

        if (!existing.getShortNameSupplierKey().equals(nextKey)
                && vendorCodeRepository.existsByShortNameSupplierKey(nextKey)) {
            throw new MasterDataConflictException("Short name supplier already exists: " + request.getShortNameSupplier());
        }

        if (!existing.getShortNameSupplierKey().equals(nextKey)
                && isVendorCodeUsed(existing.getShortNameSupplierKey())) {
            throw new MasterDataConflictException(
                    "Cannot change Short Name Supplier because MAT_INFO or MPR records are using it"
            );
        }

        apply(existing, request);
        assignMasterKeyIfMissing(existing);
        existing.setUpdatedAt(LocalDateTime.now());
        return vendorCodeRepository.save(existing);
    }

    public void delete(String id) {
        VendorCode existing = getById(id);
        if (isVendorCodeUsed(existing.getShortNameSupplierKey())) {
            throw new MasterDataConflictException(
                    "Cannot delete Vendor Code because MAT_INFO or MPR records are using it"
            );
        }
        vendorCodeRepository.delete(existing);
    }

    public MasterDataImportResult upload(MultipartFile file, ImportMode mode) {
        ImportMode effectiveMode = mode == null ? ImportMode.UPSERT : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<VendorCodeRequest>> rows = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(
                    sheet,
                    evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Short name supplier"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Vendor Code"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "Vendor name"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "MAT CHARGER")
            );

            Set<String> incomingKeys = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 5, evaluator)) {
                    continue;
                }
                totalRows++;
                int excelRow = rowIndex + 1;
                try {
                    VendorCodeRequest request = new VendorCodeRequest();
                    request.setShortNameSupplier(excelSupport.text(row, 0, evaluator));
                    request.setVendorCode(excelSupport.text(row, 1, evaluator));
                    request.setVendorName(excelSupport.text(row, 2, evaluator));
                    request.setMatCharger(excelSupport.text(row, 3, evaluator));
                    request.setRemark(excelSupport.text(row, 4, evaluator));

                    addBeanErrors(errors, excelRow, beanValidator.validate(request));
                    String key = MasterDataTextNormalizer.key(request.getShortNameSupplier());
                    if (key == null) {
                        errors.add(new ImportRowError(excelRow, "shortNameSupplier", "Short name supplier is required"));
                    } else if (!incomingKeys.add(key)) {
                        errors.add(new ImportRowError(excelRow, "shortNameSupplier", "Duplicate short name supplier inside uploaded file"));
                    }
                    rows.add(new ImportCandidate<>(excelRow, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", ex.getMessage()));
                }
            }
        } catch (MasterDataValidationException ex) {
            errors.add(new ImportRowError(1, "file", ex.getMessage()));
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import VENDOR CODE: " + ex.getMessage()));
        }

        validateModeBeforeApply(effectiveMode, rows, errors);
        if (!errors.isEmpty()) {
            return MasterDataImportResult.rejected(MASTER_DATA_NAME, effectiveMode, totalRows, errors);
        }

        MasterDataImportResult result = new MasterDataImportResult();
        result.setMasterData(MASTER_DATA_NAME);
        result.setMode(effectiveMode);
        result.setApplied(true);
        result.setTotalRows(totalRows);
        result.setValidRows(rows.size());

        if (effectiveMode == ImportMode.REPLACE_ALL) {
            vendorCodeRepository.deleteAll();
            for (ImportCandidate<VendorCodeRequest> row : rows) {
                VendorCode entity = new VendorCode();
                apply(entity, row.getValue());
                assignMasterKeyIfMissing(entity);
                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                vendorCodeRepository.save(entity);
                result.setCreated(result.getCreated() + 1);
            }
            return result;
        }

        for (ImportCandidate<VendorCodeRequest> row : rows) {
            VendorCodeRequest request = row.getValue();
            String key = MasterDataTextNormalizer.key(request.getShortNameSupplier());
            Optional<VendorCode> existing = vendorCodeRepository.findByShortNameSupplierKey(key);
            if (existing.isPresent()) {
                if (effectiveMode == ImportMode.CREATE_ONLY) {
                    // This case was already returned as a row error by validateModeBeforeApply.
                    continue;
                }
                apply(existing.get(), request);
                assignMasterKeyIfMissing(existing.get());
                existing.get().setUpdatedAt(LocalDateTime.now());
                vendorCodeRepository.save(existing.get());
                result.setUpdated(result.getUpdated() + 1);
            } else {
                VendorCode entity = new VendorCode();
                apply(entity, request);
                assignMasterKeyIfMissing(entity);
                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                vendorCodeRepository.save(entity);
                result.setCreated(result.getCreated() + 1);
            }
        }
        return result;
    }


    public byte[] exportForEdit() {
        backfillMissingMasterKeys();
        return MasterDataEditWorkbookExporter.vendorCodes(vendorCodeRepository.findAll());
    }

    /**
     * Edited-workbook upload. Existing Key updates that exact row. Blank Key
     * creates a new row and receives the next VC key automatically.
     */
    public MasterDataImportResult uploadEdited(MultipartFile file) {
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<KeyedVendorCodeRequest>> rows = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(
                    sheet,
                    evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Key"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Short name supplier"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "Vendor Code"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "Vendor name"),
                    new MasterDataExcelSupport.HeaderRequirement(4, "MAT CHARGER"),
                    new MasterDataExcelSupport.HeaderRequirement(5, "Remark")
            );

            Set<String> incomingMasterKeys = new HashSet<>();
            Set<String> incomingSupplierKeys = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 6, evaluator)) {
                    continue;
                }
                totalRows++;
                int excelRow = rowIndex + 1;
                try {
                    KeyedVendorCodeRequest keyed = new KeyedVendorCodeRequest();
                    keyed.masterKey = normalizeUploadedMasterKey(excelSupport.text(row, 0, evaluator));

                    VendorCodeRequest request = new VendorCodeRequest();
                    request.setShortNameSupplier(excelSupport.text(row, 1, evaluator));
                    request.setVendorCode(excelSupport.text(row, 2, evaluator));
                    request.setVendorName(excelSupport.text(row, 3, evaluator));
                    request.setMatCharger(excelSupport.text(row, 4, evaluator));
                    request.setRemark(excelSupport.text(row, 5, evaluator));
                    keyed.request = request;

                    addBeanErrors(errors, excelRow, beanValidator.validate(request));

                    String supplierKey = MasterDataTextNormalizer.key(request.getShortNameSupplier());
                    if (supplierKey == null) {
                        errors.add(new ImportRowError(excelRow, "shortNameSupplier", "Short name supplier is required"));
                    } else if (!incomingSupplierKeys.add(supplierKey)) {
                        errors.add(new ImportRowError(excelRow, "shortNameSupplier", "Duplicate short name supplier inside uploaded file"));
                    }

                    if (keyed.masterKey != null && !incomingMasterKeys.add(keyed.masterKey)) {
                        errors.add(new ImportRowError(excelRow, "masterKey", "Duplicate Key inside uploaded file"));
                    }

                    rows.add(new ImportCandidate<>(excelRow, keyed));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", ex.getMessage()));
                }
            }
        } catch (MasterDataValidationException ex) {
            errors.add(new ImportRowError(1, "file", ex.getMessage()));
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import edited VENDOR CODE: " + ex.getMessage()));
        }

        validateEditedRows(rows, errors);
        if (!errors.isEmpty()) {
            return MasterDataImportResult.rejected(MASTER_DATA_NAME, ImportMode.UPSERT, totalRows, errors);
        }

        MasterDataImportResult result = new MasterDataImportResult();
        result.setMasterData(MASTER_DATA_NAME);
        result.setMode(ImportMode.UPSERT);
        result.setApplied(true);
        result.setTotalRows(totalRows);
        result.setValidRows(rows.size());

        for (ImportCandidate<KeyedVendorCodeRequest> row : rows) {
            KeyedVendorCodeRequest keyed = row.getValue();
            VendorCodeRequest request = keyed.request;
            Optional<VendorCode> existing = keyed.masterKey == null
                    ? Optional.empty()
                    : vendorCodeRepository.findByMasterKey(keyed.masterKey);

            if (existing.isPresent()) {
                apply(existing.get(), request);
                assignMasterKeyIfMissing(existing.get());
                existing.get().setUpdatedAt(LocalDateTime.now());
                vendorCodeRepository.save(existing.get());
                result.setUpdated(result.getUpdated() + 1);
            } else {
                VendorCode entity = new VendorCode();
                apply(entity, request);
                entity.setMasterKey(keyed.masterKey);
                assignMasterKeyIfMissing(entity);
                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                vendorCodeRepository.save(entity);
                result.setCreated(result.getCreated() + 1);
            }
        }
        return result;
    }

    private void validateModeBeforeApply(
            ImportMode mode,
            List<ImportCandidate<VendorCodeRequest>> rows,
            List<ImportRowError> errors
    ) {
        if (mode == ImportMode.REPLACE_ALL && (matInfoRepository.count() > 0 || mprDocumentRepository.count() > 0)) {
            errors.add(new ImportRowError(
                    1,
                    "mode",
                    "Cannot use REPLACE_ALL for Vendor Code while MAT_INFO or MPR data exists. Use UPSERT instead."
            ));
        }

        if (mode == ImportMode.CREATE_ONLY) {
            for (ImportCandidate<VendorCodeRequest> row : rows) {
                String key = MasterDataTextNormalizer.key(row.getValue().getShortNameSupplier());
                if (key != null && vendorCodeRepository.existsByShortNameSupplierKey(key)) {
                    errors.add(new ImportRowError(
                            row.getRowNumber(),
                            "shortNameSupplier",
                            "Supplier already exists; CREATE_ONLY does not allow updates"
                    ));
                }
            }
        }
    }


    private void validateEditedRows(
            List<ImportCandidate<KeyedVendorCodeRequest>> rows,
            List<ImportRowError> errors
    ) {
        Map<String, VendorCode> existingByMasterKey = new HashMap<>();
        Map<String, VendorCode> existingBySupplierKey = new HashMap<>();
        for (VendorCode item : vendorCodeRepository.findAll()) {
            if (item.getMasterKey() != null && !item.getMasterKey().isBlank()) {
                existingByMasterKey.put(item.getMasterKey().trim().toUpperCase(Locale.ROOT), item);
            }
            if (item.getShortNameSupplierKey() != null && !item.getShortNameSupplierKey().isBlank()) {
                existingBySupplierKey.put(item.getShortNameSupplierKey(), item);
            }
        }

        for (ImportCandidate<KeyedVendorCodeRequest> row : rows) {
            KeyedVendorCodeRequest keyed = row.getValue();
            if (keyed == null || keyed.request == null) {
                continue;
            }
            String supplierKey = MasterDataTextNormalizer.key(keyed.request.getShortNameSupplier());
            if (supplierKey == null) {
                continue;
            }

            VendorCode target = keyed.masterKey == null ? null : existingByMasterKey.get(keyed.masterKey);
            VendorCode duplicateSupplier = existingBySupplierKey.get(supplierKey);
            if (duplicateSupplier != null && (target == null || !duplicateSupplier.getId().equals(target.getId()))) {
                errors.add(new ImportRowError(
                        row.getRowNumber(),
                        "shortNameSupplier",
                        "Supplier already exists. Keep its Key to update the existing row, or use a new supplier name."
                ));
            }

            if (target != null
                    && target.getShortNameSupplierKey() != null
                    && !target.getShortNameSupplierKey().equals(supplierKey)
                    && isVendorCodeUsed(target.getShortNameSupplierKey())) {
                errors.add(new ImportRowError(
                        row.getRowNumber(),
                        "shortNameSupplier",
                        "Cannot change Short Name Supplier because MAT_INFO or MPR records are using it"
                ));
            }
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

    private AtomicLong masterKeyCounter() {
        return MasterDataSequentialKey.counter(
                vendorCodeRepository.findAll().stream()
                        .map(VendorCode::getMasterKey)
                        .collect(Collectors.toSet()),
                MASTER_KEY_PREFIX
        );
    }

    private void assignMasterKeyIfMissing(VendorCode entity) {
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

    private VendorCode ensureMasterKeyPersisted(VendorCode entity) {
        if (entity == null || (entity.getMasterKey() != null && !entity.getMasterKey().isBlank())) {
            return entity;
        }
        assignMasterKeyIfMissing(entity);
        return vendorCodeRepository.save(entity);
    }

    private void backfillMissingMasterKeys() {
        AtomicLong counter = masterKeyCounter();
        boolean changed = false;
        for (VendorCode entity : vendorCodeRepository.findAll()) {
            if (entity.getMasterKey() == null || entity.getMasterKey().isBlank()) {
                MasterDataSequentialKey.ensure(entity::getMasterKey, entity::setMasterKey, counter, MASTER_KEY_PREFIX);
                vendorCodeRepository.save(entity);
                changed = true;
            }
        }
        if (changed) {
            // Recalculate the next counter after saving legacy rows.
        }
    }

    private void apply(VendorCode target, VendorCodeRequest request) {
        String shortName = MasterDataTextNormalizer.trimToNull(request.getShortNameSupplier());
        if (shortName == null) {
            throw new MasterDataValidationException("Short name supplier is required");
        }
        target.setShortNameSupplier(shortName);
        target.setShortNameSupplierKey(MasterDataTextNormalizer.key(shortName));
        target.setVendorCode(vendorCodeText(request.getVendorCode()));
        target.setVendorName(MasterDataTextNormalizer.trimToNull(request.getVendorName()));
        target.setMatCharger(MasterDataTextNormalizer.trimToNull(request.getMatCharger()));
        target.setRemark(MasterDataTextNormalizer.trimToNull(request.getRemark()));
    }

    private String vendorCodeText(String value) {
        String text = MasterDataTextNormalizer.trimToNull(value);
        if (text == null) {
            return null;
        }
        return text.matches("^[0-9,]+$") ? text.replace(",", "") : text;
    }

    private String requireSupplierKey(VendorCodeRequest request) {
        String key = MasterDataTextNormalizer.key(request == null ? null : request.getShortNameSupplier());
        if (key == null) {
            throw new MasterDataValidationException("Short name supplier is required");
        }
        return key;
    }

    private boolean isVendorCodeUsed(String shortNameSupplierKey) {
        if (shortNameSupplierKey == null || shortNameSupplierKey.isBlank()) {
            return false;
        }

        // Scan the visible value too so old MAT_INFO documents without a
        // normalized helper key are protected as well.
        if (matInfoRepository.findAll().stream()
                .map(item -> MasterDataTextNormalizer.key(item.getShortNameSupplier()))
                .anyMatch(shortNameSupplierKey::equals)) {
            return true;
        }

        for (MprDocument mpr : mprDocumentRepository.findAll()) {
            if (mpr.getLines() == null) {
                continue;
            }

            for (MprLine line : mpr.getLines()) {
                String lineKey = MasterDataTextNormalizer.key(line == null ? null : line.getShortNameSupplier());
                if (shortNameSupplierKey != null && shortNameSupplierKey.equals(lineKey)) {
                    return true;
                }
            }
        }

        return false;
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
            errors.add(new ImportRowError(row, parts[0], parts.length > 1 ? parts[1] : message));
        }
    }


    private static class KeyedVendorCodeRequest {
        private String masterKey;
        private VendorCodeRequest request;
    }

}
