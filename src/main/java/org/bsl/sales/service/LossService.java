package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.ImportRowError;
import org.bsl.sales.dto.LossRequest;
import org.bsl.sales.dto.LossResolutionResponse;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.BomLine;
import org.bsl.sales.model.BomPacking;
import org.bsl.sales.model.Loss;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.model.MprLine;
import org.bsl.sales.repository.BomDocumentRepository;
import org.bsl.sales.repository.LossRepository;
import org.bsl.sales.repository.MatInfoRepository;
import org.bsl.sales.repository.MprDocumentRepository;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class LossService {

    private static final String MASTER_DATA_NAME = "Loss";
    private static final String MASTER_KEY_PREFIX = "L";
    private static final BigDecimal TIER_501 = BigDecimal.valueOf(501);
    private static final BigDecimal TIER_1501 = BigDecimal.valueOf(1501);
    private static final BigDecimal TIER_3001 = BigDecimal.valueOf(3001);
    private static final BigDecimal FACTOR_TOLERANCE = new BigDecimal("0.000001");

    private final LossRepository lossRepository;
    private final MatInfoRepository matInfoRepository;
    private final BomDocumentRepository bomDocumentRepository;
    private final MprDocumentRepository mprDocumentRepository;
    private final MasterDataBeanValidator beanValidator;
    private final MasterDataExcelSupport excelSupport;

    public LossService(
            LossRepository lossRepository,
            MatInfoRepository matInfoRepository,
            BomDocumentRepository bomDocumentRepository,
            MprDocumentRepository mprDocumentRepository,
            MasterDataBeanValidator beanValidator,
            MasterDataExcelSupport excelSupport
    ) {
        this.lossRepository = lossRepository;
        this.matInfoRepository = matInfoRepository;
        this.bomDocumentRepository = bomDocumentRepository;
        this.mprDocumentRepository = mprDocumentRepository;
        this.beanValidator = beanValidator;
        this.excelSupport = excelSupport;
    }

    public Loss create(LossRequest request) {
        String key = materialGroupKey(request);
        if (lossRepository.existsByMaterialGroupKey(key)) {
            throw new MasterDataConflictException("Loss master already exists for material group: " + request.getMaterialGroup());
        }

        Loss entity = new Loss();
        apply(entity, request);
        assignMasterKeyIfMissing(entity);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return lossRepository.save(entity);
    }

    /**
     * Field-specific filters. Loss values are submitted as normal percentage
     * values from the UI (for example 3 means 3%) and compared to the stored
     * decimal values (0.03).
     */
    public Page<Loss> list(
            String masterKey,
            String materialGroup,
            BigDecimal lossLt501Percent,
            BigDecimal lossLt1501Percent,
            BigDecimal lossLt3001Percent,
            BigDecimal lossGte3001Percent,
            int page,
            int size
    ) {
        backfillMissingMasterKeys();
        Pageable pageable = toPageable(page, size);
        String masterKeySearch = MasterDataTextNormalizer.key(masterKey);
        String materialGroupSearch = MasterDataTextNormalizer.materialGroupKey(materialGroup);

        BigDecimal lossLt501 = percentToDecimal(lossLt501Percent, "Loss <501");
        BigDecimal lossLt1501 = percentToDecimal(lossLt1501Percent, "Loss <1501");
        BigDecimal lossLt3001 = percentToDecimal(lossLt3001Percent, "Loss <3001");
        BigDecimal lossGte3001 = percentToDecimal(lossGte3001Percent, "Loss ≥3001");

        List<Loss> filtered = lossRepository.findAll().stream()
                .filter(item -> contains(item.getMasterKey(), masterKeySearch))
                .filter(item -> materialGroupSearch == null || item.getMaterialGroupKey().contains(materialGroupSearch))
                .filter(item -> sameDecimal(item.getLossLt501(), lossLt501))
                .filter(item -> sameDecimal(item.getLossLt1501(), lossLt1501))
                .filter(item -> sameDecimal(item.getLossLt3001(), lossLt3001))
                .filter(item -> sameDecimal(item.getLossGte3001(), lossGte3001))
                .sorted(Comparator.comparing(Loss::getMaterialGroup, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        return page(filtered, pageable);
    }

    public Loss getById(String id) {
        Loss entity = lossRepository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Loss master record not found"));
        return ensureMasterKeyPersisted(entity);
    }

    public Loss update(String id, LossRequest request) {
        Loss existing = getById(id);
        String nextKey = materialGroupKey(request);
        if (!existing.getMaterialGroupKey().equals(nextKey) && lossRepository.existsByMaterialGroupKey(nextKey)) {
            throw new MasterDataConflictException("Loss master already exists for material group: " + request.getMaterialGroup());
        }
        if (!existing.getMaterialGroupKey().equals(nextKey)
                && isLossUsed(existing.getMaterialGroupKey())) {
            throw new MasterDataConflictException(
                    "Cannot change Material Group because MAT_INFO, BOM or MPR records are using this loss group"
            );
        }

        apply(existing, request);
        assignMasterKeyIfMissing(existing);
        existing.setUpdatedAt(LocalDateTime.now());
        return lossRepository.save(existing);
    }

    public void delete(String id) {
        Loss existing = getById(id);
        if (isLossUsed(existing.getMaterialGroupKey())) {
            throw new MasterDataConflictException(
                    "Cannot delete Loss because MAT_INFO, BOM or MPR records are using this Material Group"
            );
        }
        lossRepository.delete(existing);
    }

    public LossResolutionResponse resolve(String materialType, BigDecimal totalQuantity) {
        String key = MasterDataTextNormalizer.materialGroupKey(materialType);
        if (key == null) {
            throw new MasterDataValidationException("materialType is required");
        }
        if (totalQuantity == null || totalQuantity.signum() < 0) {
            throw new MasterDataValidationException("totalQuantity must be greater than or equal to 0");
        }

        Loss loss = lossRepository.findByMaterialGroupKey(key)
                .orElseThrow(() -> new MasterDataNotFoundException("No loss master found for material type: " + materialType));
        loss = ensureMasterKeyPersisted(loss);

        LossResolutionResponse response = new LossResolutionResponse();
        response.setMaterialType(materialType);
        response.setMaterialGroup(loss.getMaterialGroup());
        response.setTotalQuantity(totalQuantity);

        if (totalQuantity.compareTo(TIER_501) < 0) {
            response.setTier("<501");
            response.setLossRate(loss.getLossLt501());
            response.setFactor(loss.getFactorLt501());
        } else if (totalQuantity.compareTo(TIER_1501) < 0) {
            response.setTier("<1501");
            response.setLossRate(loss.getLossLt1501());
            response.setFactor(loss.getFactorLt1501());
        } else if (totalQuantity.compareTo(TIER_3001) < 0) {
            response.setTier("<3001");
            response.setLossRate(loss.getLossLt3001());
            response.setFactor(loss.getFactorLt3001());
        } else {
            response.setTier(">=3001");
            response.setLossRate(loss.getLossGte3001());
            response.setFactor(loss.getFactorGte3001());
        }
        return response;
    }

    public MasterDataImportResult upload(MultipartFile file, ImportMode mode) {
        ImportMode effectiveMode = mode == null ? ImportMode.UPSERT : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<LossRequest>> rows = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(
                    sheet,
                    evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Order Q'ty", "Material group"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "<501"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "<1501"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "<3001"),
                    new MasterDataExcelSupport.HeaderRequirement(4, ">=3001")
            );

            Set<String> incomingKeys = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 13, evaluator)) {
                    continue;
                }
                totalRows++;
                int excelRow = rowIndex + 1;
                try {
                    LossRequest request = toLossRequest(row, evaluator);
                    addBeanErrors(errors, excelRow, beanValidator.validate(request));
                    validateTierOrder(request);

                    String key = materialGroupKey(request);
                    if (!incomingKeys.add(key)) {
                        errors.add(new ImportRowError(excelRow, "materialGroup", "Duplicate material group inside uploaded file"));
                    }
                    rows.add(new ImportCandidate<>(excelRow, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", ex.getMessage()));
                }
            }
        } catch (MasterDataValidationException ex) {
            errors.add(new ImportRowError(1, "file", ex.getMessage()));
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import Loss: " + ex.getMessage()));
        }

        validateBeforeApply(effectiveMode, rows, errors);
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
            lossRepository.deleteAll();
            for (ImportCandidate<LossRequest> row : rows) {
                Loss entity = new Loss();
                apply(entity, row.getValue());
                assignMasterKeyIfMissing(entity);
                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                lossRepository.save(entity);
                result.setCreated(result.getCreated() + 1);
            }
            return result;
        }

        for (ImportCandidate<LossRequest> row : rows) {
            LossRequest request = row.getValue();
            String key = materialGroupKey(request);
            Optional<Loss> existing = lossRepository.findByMaterialGroupKey(key);
            if (existing.isPresent()) {
                apply(existing.get(), request);
                assignMasterKeyIfMissing(existing.get());
                existing.get().setUpdatedAt(LocalDateTime.now());
                lossRepository.save(existing.get());
                result.setUpdated(result.getUpdated() + 1);
            } else {
                Loss entity = new Loss();
                apply(entity, request);
                assignMasterKeyIfMissing(entity);
                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                lossRepository.save(entity);
                result.setCreated(result.getCreated() + 1);
            }
        }
        return result;
    }


    public byte[] exportForEdit() {
        backfillMissingMasterKeys();
        return MasterDataEditWorkbookExporter.losses(lossRepository.findAll());
    }

    /**
     * Edited-workbook upload. Existing L key updates that exact row. Blank key
     * creates a new Loss row and receives the next L key automatically.
     */
    public MasterDataImportResult uploadEdited(MultipartFile file) {
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<KeyedLossRequest>> rows = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(
                    sheet,
                    evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Key"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Order Q'ty", "Material group"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "<501"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "<1501"),
                    new MasterDataExcelSupport.HeaderRequirement(4, "<3001"),
                    new MasterDataExcelSupport.HeaderRequirement(5, ">=3001")
            );

            Set<String> incomingMasterKeys = new HashSet<>();
            Set<String> incomingMaterialKeys = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 13, evaluator)) {
                    continue;
                }
                totalRows++;
                int excelRow = rowIndex + 1;
                try {
                    KeyedLossRequest keyed = new KeyedLossRequest();
                    keyed.masterKey = normalizeUploadedMasterKey(excelSupport.text(row, 0, evaluator));
                    keyed.request = toEditedLossRequest(row, evaluator);

                    addBeanErrors(errors, excelRow, beanValidator.validate(keyed.request));
                    validateTierOrder(keyed.request);

                    String materialKey = materialGroupKey(keyed.request);
                    if (!incomingMaterialKeys.add(materialKey)) {
                        errors.add(new ImportRowError(excelRow, "materialGroup", "Duplicate material group inside uploaded file"));
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
            errors.add(new ImportRowError(1, "file", "Cannot import edited Loss: " + ex.getMessage()));
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

        for (ImportCandidate<KeyedLossRequest> row : rows) {
            KeyedLossRequest keyed = row.getValue();
            Optional<Loss> existing = keyed.masterKey == null
                    ? Optional.empty()
                    : lossRepository.findByMasterKey(keyed.masterKey);
            if (existing.isPresent()) {
                apply(existing.get(), keyed.request);
                assignMasterKeyIfMissing(existing.get());
                existing.get().setUpdatedAt(LocalDateTime.now());
                lossRepository.save(existing.get());
                result.setUpdated(result.getUpdated() + 1);
            } else {
                Loss entity = new Loss();
                apply(entity, keyed.request);
                entity.setMasterKey(keyed.masterKey);
                assignMasterKeyIfMissing(entity);
                LocalDateTime now = LocalDateTime.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                lossRepository.save(entity);
                result.setCreated(result.getCreated() + 1);
            }
        }
        return result;
    }

    private LossRequest toLossRequest(Row row, FormulaEvaluator evaluator) {
        String directGroup = excelSupport.text(row, 0, evaluator);
        BigDecimal lossLt501 = excelSupport.decimal(row, 1, evaluator);
        BigDecimal lossLt1501 = excelSupport.decimal(row, 2, evaluator);
        BigDecimal lossLt3001 = excelSupport.decimal(row, 3, evaluator);
        BigDecimal lossGte3001 = excelSupport.decimal(row, 4, evaluator);

        String factorGroup = excelSupport.text(row, 7, evaluator);
        BigDecimal factorLt501 = excelSupport.decimal(row, 8, evaluator);
        BigDecimal factorLt1501 = excelSupport.decimal(row, 9, evaluator);
        BigDecimal factorLt3001 = excelSupport.decimal(row, 10, evaluator);
        BigDecimal factorGte3001 = excelSupport.decimal(row, 11, evaluator);

        String group = MasterDataTextNormalizer.trimToNull(directGroup);
        if (group == null) {
            group = MasterDataTextNormalizer.trimToNull(factorGroup);
            lossLt501 = lossFromFactor(factorLt501, "I");
            lossLt1501 = lossFromFactor(factorLt1501, "J");
            lossLt3001 = lossFromFactor(factorLt3001, "K");
            lossGte3001 = lossFromFactor(factorGte3001, "L");
        } else if (factorGroup != null) {
            String directKey = MasterDataTextNormalizer.materialGroupKey(group);
            String factorKey = MasterDataTextNormalizer.materialGroupKey(factorGroup);
            if (!directKey.equals(factorKey)) {
                throw new MasterDataValidationException(
                        "Material group in direct-loss table and factor table do not match: " + group + " / " + factorGroup
                );
            }
            validateFactorMatchesLoss(lossLt501, factorLt501, "<501");
            validateFactorMatchesLoss(lossLt1501, factorLt1501, "<1501");
            validateFactorMatchesLoss(lossLt3001, factorLt3001, "<3001");
            validateFactorMatchesLoss(lossGte3001, factorGte3001, ">=3001");
        }

        LossRequest request = new LossRequest();
        request.setMaterialGroup(group);
        request.setLossLt501(lossLt501);
        request.setLossLt1501(lossLt1501);
        request.setLossLt3001(lossLt3001);
        request.setLossGte3001(lossGte3001);
        return request;
    }


    private LossRequest toEditedLossRequest(Row row, FormulaEvaluator evaluator) {
        String directGroup = excelSupport.text(row, 1, evaluator);
        BigDecimal lossLt501 = excelSupport.decimal(row, 2, evaluator);
        BigDecimal lossLt1501 = excelSupport.decimal(row, 3, evaluator);
        BigDecimal lossLt3001 = excelSupport.decimal(row, 4, evaluator);
        BigDecimal lossGte3001 = excelSupport.decimal(row, 5, evaluator);

        String factorGroup = excelSupport.text(row, 7, evaluator);
        BigDecimal factorLt501 = excelSupport.decimal(row, 8, evaluator);
        BigDecimal factorLt1501 = excelSupport.decimal(row, 9, evaluator);
        BigDecimal factorLt3001 = excelSupport.decimal(row, 10, evaluator);
        BigDecimal factorGte3001 = excelSupport.decimal(row, 11, evaluator);

        String group = MasterDataTextNormalizer.trimToNull(directGroup);
        if (group == null) {
            group = MasterDataTextNormalizer.trimToNull(factorGroup);
            lossLt501 = lossFromFactor(factorLt501, "I");
            lossLt1501 = lossFromFactor(factorLt1501, "J");
            lossLt3001 = lossFromFactor(factorLt3001, "K");
            lossGte3001 = lossFromFactor(factorGte3001, "L");
        } else if (factorGroup != null) {
            String directKey = MasterDataTextNormalizer.materialGroupKey(group);
            String factorKey = MasterDataTextNormalizer.materialGroupKey(factorGroup);
            if (!directKey.equals(factorKey)) {
                throw new MasterDataValidationException(
                        "Material group in direct-loss table and factor table do not match: " + group + " / " + factorGroup
                );
            }
            validateFactorMatchesLoss(lossLt501, factorLt501, "<501");
            validateFactorMatchesLoss(lossLt1501, factorLt1501, "<1501");
            validateFactorMatchesLoss(lossLt3001, factorLt3001, "<3001");
            validateFactorMatchesLoss(lossGte3001, factorGte3001, ">=3001");
        }

        LossRequest request = new LossRequest();
        request.setMaterialGroup(group);
        request.setLossLt501(lossLt501);
        request.setLossLt1501(lossLt1501);
        request.setLossLt3001(lossLt3001);
        request.setLossGte3001(lossGte3001);
        return request;
    }

    private void validateEditedRows(
            List<ImportCandidate<KeyedLossRequest>> rows,
            List<ImportRowError> errors
    ) {
        Map<String, Loss> existingByMasterKey = new HashMap<>();
        Map<String, Loss> existingByMaterialKey = new HashMap<>();
        for (Loss item : lossRepository.findAll()) {
            if (item.getMasterKey() != null && !item.getMasterKey().isBlank()) {
                existingByMasterKey.put(item.getMasterKey().trim().toUpperCase(Locale.ROOT), item);
            }
            if (item.getMaterialGroupKey() != null && !item.getMaterialGroupKey().isBlank()) {
                existingByMaterialKey.put(item.getMaterialGroupKey(), item);
            }
        }

        for (ImportCandidate<KeyedLossRequest> row : rows) {
            KeyedLossRequest keyed = row.getValue();
            if (keyed == null || keyed.request == null) {
                continue;
            }
            String materialKey;
            try {
                materialKey = materialGroupKey(keyed.request);
            } catch (RuntimeException ignored) {
                continue;
            }

            Loss target = keyed.masterKey == null ? null : existingByMasterKey.get(keyed.masterKey);
            Loss duplicate = existingByMaterialKey.get(materialKey);
            if (duplicate != null && (target == null || !duplicate.getId().equals(target.getId()))) {
                errors.add(new ImportRowError(
                        row.getRowNumber(),
                        "materialGroup",
                        "Loss material group already exists. Keep its Key to update the existing row, or use a new material group."
                ));
            }

            if (target != null
                    && target.getMaterialGroupKey() != null
                    && !target.getMaterialGroupKey().equals(materialKey)
                    && isLossUsed(target.getMaterialGroupKey())) {
                errors.add(new ImportRowError(
                        row.getRowNumber(),
                        "materialGroup",
                        "Cannot change Material Group because MAT_INFO, BOM or MPR records are using this loss group"
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

    private void validateBeforeApply(
            ImportMode mode,
            List<ImportCandidate<LossRequest>> rows,
            List<ImportRowError> errors
    ) {
        if (mode == ImportMode.CREATE_ONLY) {
            for (ImportCandidate<LossRequest> row : rows) {
                String key = materialGroupKey(row.getValue());
                if (lossRepository.existsByMaterialGroupKey(key)) {
                    errors.add(new ImportRowError(
                            row.getRowNumber(),
                            "materialGroup",
                            "Loss master already exists; CREATE_ONLY does not allow updates"
                    ));
                }
            }
        }

        if (mode == ImportMode.REPLACE_ALL) {
            Set<String> incomingKeys = rows.stream()
                    .map(row -> materialGroupKey(row.getValue()))
                    .collect(Collectors.toSet());
            for (Loss current : lossRepository.findAll()) {
                if (isLossUsed(current.getMaterialGroupKey())
                        && !incomingKeys.contains(current.getMaterialGroupKey())) {
                    errors.add(new ImportRowError(
                            1,
                            "mode",
                            "REPLACE_ALL would remove a Loss group used by MAT_INFO, BOM or MPR: " + current.getMaterialGroup()
                    ));
                }
            }
        }
    }

    private void apply(Loss target, LossRequest request) {
        String materialGroup = required(request.getMaterialGroup(), "Material group is required");
        validateTierOrder(request);

        target.setMaterialGroup(materialGroup);
        target.setMaterialGroupKey(MasterDataTextNormalizer.materialGroupKey(materialGroup));
        target.setLossLt501(normalizeRate(request.getLossLt501()));
        target.setLossLt1501(normalizeRate(request.getLossLt1501()));
        target.setLossLt3001(normalizeRate(request.getLossLt3001()));
        target.setLossGte3001(normalizeRate(request.getLossGte3001()));
    }

    private AtomicLong masterKeyCounter() {
        return MasterDataSequentialKey.counter(
                lossRepository.findAll().stream()
                        .map(Loss::getMasterKey)
                        .collect(Collectors.toSet()),
                MASTER_KEY_PREFIX
        );
    }

    private void assignMasterKeyIfMissing(Loss entity) {
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

    private Loss ensureMasterKeyPersisted(Loss entity) {
        if (entity == null || (entity.getMasterKey() != null && !entity.getMasterKey().isBlank())) {
            return entity;
        }
        assignMasterKeyIfMissing(entity);
        return lossRepository.save(entity);
    }

    private void backfillMissingMasterKeys() {
        AtomicLong counter = masterKeyCounter();
        for (Loss entity : lossRepository.findAll()) {
            if (entity.getMasterKey() == null || entity.getMasterKey().isBlank()) {
                MasterDataSequentialKey.ensure(entity::getMasterKey, entity::setMasterKey, counter, MASTER_KEY_PREFIX);
                lossRepository.save(entity);
            }
        }
    }

    private boolean contains(String value, String normalizedSearch) {
        if (normalizedSearch == null) {
            return true;
        }
        return value != null && value.toUpperCase(java.util.Locale.ROOT).contains(normalizedSearch);
    }

    /**
     * A loss group is considered in use as soon as it is referenced by MAT_INFO,
     * BOM material lines, or a generated MPR snapshot. Deleting or renaming it
     * would otherwise make historical BOM/MPR calculations impossible to trace.
     */
    private boolean isLossUsed(String materialGroupKey) {
        if (materialGroupKey == null || materialGroupKey.isBlank()) {
            return false;
        }

        if (matInfoRepository.countByMaterialTypeKey(materialGroupKey) > 0) {
            return true;
        }

        // Protect legacy MAT_INFO rows that do not yet have materialTypeKey.
        if (matInfoRepository.findAll().stream()
                .map(item -> MasterDataTextNormalizer.materialGroupKey(item.getMaterialType()))
                .anyMatch(materialGroupKey::equals)) {
            return true;
        }

        for (BomDocument bom : bomDocumentRepository.findAll()) {
            if (containsMaterialType(bom.getCoreLines(), materialGroupKey)) {
                return true;
            }

            if (bom.getPackings() == null) {
                continue;
            }

            for (BomPacking packing : bom.getPackings()) {
                if (packing != null && containsMaterialType(packing.getLines(), materialGroupKey)) {
                    return true;
                }
            }
        }

        for (MprDocument mpr : mprDocumentRepository.findAll()) {
            if (containsMaterialType(mpr.getLines(), materialGroupKey)) {
                return true;
            }
        }

        return false;
    }

    private boolean containsMaterialType(List<? extends Object> lines, String materialGroupKey) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }

        for (Object rawLine : lines) {
            String materialType = null;
            if (rawLine instanceof BomLine bomLine) {
                materialType = bomLine.getMaterialType();
            } else if (rawLine instanceof MprLine mprLine) {
                materialType = mprLine.getMaterialType();
            }

            String lineKey = MasterDataTextNormalizer.materialGroupKey(materialType);
            if (materialGroupKey.equals(lineKey)) {
                return true;
            }
        }

        return false;
    }

    private BigDecimal percentToDecimal(BigDecimal percent, String label) {
        if (percent == null) {
            return null;
        }

        if (percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0) {
            throw new MasterDataValidationException(label + " must be between 0 and 100");
        }

        return percent.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private boolean sameDecimal(BigDecimal actual, BigDecimal expected) {
        return expected == null || (actual != null && actual.compareTo(expected) == 0);
    }

    private String materialGroupKey(LossRequest request) {
        String materialGroup = required(request == null ? null : request.getMaterialGroup(), "Material group is required");
        return MasterDataTextNormalizer.materialGroupKey(materialGroup);
    }

    private void validateTierOrder(LossRequest request) {
        BigDecimal a = request.getLossLt501();
        BigDecimal b = request.getLossLt1501();
        BigDecimal c = request.getLossLt3001();
        BigDecimal d = request.getLossGte3001();
        if (a == null || b == null || c == null || d == null) {
            return;
        }
        if (a.compareTo(b) < 0 || b.compareTo(c) < 0 || c.compareTo(d) < 0) {
            throw new MasterDataValidationException(
                    "Loss must not increase as order quantity increases: <501 >= <1501 >= <3001 >= >=3001"
            );
        }
    }

    private BigDecimal lossFromFactor(BigDecimal factor, String column) {
        if (factor == null) {
            return null;
        }
        BigDecimal loss = factor.subtract(BigDecimal.ONE);
        if (loss.signum() < 0) {
            throw new MasterDataValidationException("Factor in column " + column + " must be greater than or equal to 1");
        }
        return loss;
    }

    private void validateFactorMatchesLoss(BigDecimal loss, BigDecimal factor, String tier) {
        if (factor == null) {
            return;
        }
        if (loss == null) {
            throw new MasterDataValidationException("Loss value is missing for tier " + tier + " while a factor is provided");
        }
        BigDecimal expected = BigDecimal.ONE.add(loss);
        if (expected.subtract(factor).abs().compareTo(FACTOR_TOLERANCE) > 0) {
            throw new MasterDataValidationException(
                    "Factor for tier " + tier + " must equal 1 + loss (expected " + expected + ", received " + factor + ")"
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

    private BigDecimal normalizeRate(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new MasterDataValidationException("Loss rate must be between 0 and 1");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
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


    private static class KeyedLossRequest {
        private String masterKey;
        private LossRequest request;
    }

}
