package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.ImportRowError;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.dto.ShipToRequest;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.ShipTo;
import org.bsl.sales.repository.MprDocumentRepository;
import org.bsl.sales.repository.ShipToRepository;
import org.bsl.sales.support.ImportCandidate;
import org.bsl.sales.support.MasterDataBeanValidator;
import org.bsl.sales.support.MasterDataEditWorkbookExporter;
import org.bsl.sales.support.MasterDataExcelSupport;
import org.bsl.sales.support.MasterDataTextNormalizer;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ShipToService {
    private static final String MASTER_DATA_NAME = "SHIP TO";
    private static final String MASTER_KEY_PREFIX = "ST";
    private static final String SEQUENCE_NAME = "ship_to";

    private final ShipToRepository repository;
    private final MprDocumentRepository mprRepository;
    private final MasterDataBeanValidator beanValidator;
    private final MasterDataExcelSupport excelSupport;
    private final MongoTemplate mongoTemplate;
    private final MasterDataSequenceService sequenceService;
    private volatile boolean masterKeysBackfilled;

    public ShipToService(
            ShipToRepository repository,
            MprDocumentRepository mprRepository,
            MasterDataBeanValidator beanValidator,
            MasterDataExcelSupport excelSupport,
            MongoTemplate mongoTemplate,
            MasterDataSequenceService sequenceService
    ) {
        this.repository = repository;
        this.mprRepository = mprRepository;
        this.beanValidator = beanValidator;
        this.excelSupport = excelSupport;
        this.mongoTemplate = mongoTemplate;
        this.sequenceService = sequenceService;
    }

    public ShipTo create(ShipToRequest request) {
        String nameKey = nameKey(request.shipToName());
        String codeKey = codeKey(request.shipToCode());
        if (repository.findByShipToNameKey(nameKey).isPresent()) {
            throw new MasterDataConflictException("Ship To name already exists: " + request.shipToName());
        }
        if (codeKey != null && repository.findByShipToCodeKey(codeKey).isPresent()) {
            throw new MasterDataConflictException("Ship To code already exists: " + request.shipToCode());
        }
        ShipTo entity = new ShipTo();
        entity.setMasterKey(nextMasterKey());
        apply(entity, request);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return repository.save(entity);
    }

    public Page<ShipTo> list(String shipToName, String shipToCode, Boolean active, int page, int size) {
        backfillMissingMasterKeys();
        Pageable pageable = pageable(page, size);
        Query query = new Query();
        addContains(query, "shipToName", shipToName);
        addContains(query, "shipToCode", shipToCode);
        if (active != null) query.addCriteria(Criteria.where("active").is(active));
        long total = mongoTemplate.count(query, ShipTo.class);
        query.with(Sort.by(Sort.Order.asc("shipToName")));
        query.skip(pageable.getOffset()).limit(pageable.getPageSize());
        return new PageImpl<>(mongoTemplate.find(query, ShipTo.class), pageable, total);
    }

    public List<ShipTo> listActive() {
        return repository.findByActiveTrueOrderByShipToNameAsc();
    }

    public ShipTo get(String id) {
        ShipTo entity = repository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Ship To not found"));
        return ensureMasterKeyPersisted(entity);
    }

    public ShipTo update(String id, ShipToRequest request) {
        ShipTo entity = get(id);
        String nextNameKey = nameKey(request.shipToName());
        Optional<ShipTo> duplicateName = repository.findByShipToNameKey(nextNameKey);
        if (duplicateName.isPresent() && !duplicateName.get().getId().equals(id)) {
            throw new MasterDataConflictException("Ship To name already exists: " + request.shipToName());
        }
        String nextCodeKey = codeKey(request.shipToCode());
        if (nextCodeKey != null) {
            Optional<ShipTo> duplicateCode = repository.findByShipToCodeKey(nextCodeKey);
            if (duplicateCode.isPresent() && !duplicateCode.get().getId().equals(id)) {
                throw new MasterDataConflictException("Ship To code already exists: " + request.shipToCode());
            }
        }
        apply(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        return repository.save(entity);
    }

    public void delete(String id) {
        ShipTo entity = get(id);
        if (isUsed(entity)) {
            throw new MasterDataConflictException("Cannot delete Ship To because an MPR is using it. Set it to Inactive instead.");
        }
        repository.delete(entity);
    }

    public MasterDataImportResult upload(MultipartFile file, ImportMode mode) {
        ImportMode effectiveMode = mode == null ? ImportMode.CREATE_ONLY : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<ShipToRequest>> rows = parseStandardWorkbook(file, errors);
        int totalRows = rows.size();

        Set<String> incomingNameKeys = rows.stream()
                .map(row -> nameKey(row.getValue().shipToName())).collect(Collectors.toSet());
        Set<String> incomingCodeKeys = rows.stream()
                .map(row -> codeKey(row.getValue().shipToCode())).filter(value -> value != null).collect(Collectors.toSet());
        Map<String, ShipTo> existingByName = repository.findAllByShipToNameKeyIn(incomingNameKeys).stream()
                .collect(Collectors.toMap(ShipTo::getShipToNameKey, item -> item, (a, b) -> a));
        Map<String, ShipTo> existingByCode = repository.findAllByShipToCodeKeyIn(incomingCodeKeys).stream()
                .filter(item -> item.getShipToCodeKey() != null)
                .collect(Collectors.toMap(ShipTo::getShipToCodeKey, item -> item, (a, b) -> a));

        if (effectiveMode == ImportMode.REPLACE_ALL && mprRepository.count() > 0) {
            errors.add(new ImportRowError(1, "mode", "Cannot use REPLACE_ALL while MPR data exists. Use UPSERT instead."));
        }
        for (ImportCandidate<ShipToRequest> row : rows) {
            String nameKey = nameKey(row.getValue().shipToName());
            if (effectiveMode == ImportMode.CREATE_ONLY && existingByName.containsKey(nameKey)) {
                errors.add(new ImportRowError(row.getRowNumber(), "shipToName", "Ship To already exists; CREATE_ONLY does not allow updates"));
            }
            String codeKey = codeKey(row.getValue().shipToCode());
            ShipTo duplicateCode = codeKey == null ? null : existingByCode.get(codeKey);
            ShipTo target = existingByName.get(nameKey);
            if (duplicateCode != null && (target == null || !duplicateCode.getId().equals(target.getId()))) {
                errors.add(new ImportRowError(row.getRowNumber(), "shipToCode", "Ship To code already exists"));
            }
        }
        if (!errors.isEmpty()) return MasterDataImportResult.rejected(MASTER_DATA_NAME, effectiveMode, totalRows, errors);

        MasterDataImportResult result = baseResult(effectiveMode, totalRows);
        LocalDateTime now = LocalDateTime.now();
        List<ShipTo> toSave = new ArrayList<>();
        if (effectiveMode == ImportMode.REPLACE_ALL) {
            List<String> keys = reserveMasterKeys(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                ShipTo entity = new ShipTo();
                entity.setMasterKey(keys.get(i));
                apply(entity, rows.get(i).getValue());
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                toSave.add(entity);
            }
            repository.deleteAll();
            repository.saveAll(toSave);
            result.setCreated(toSave.size());
            return result;
        }

        int createCount = (int) rows.stream()
                .filter(row -> !existingByName.containsKey(nameKey(row.getValue().shipToName())))
                .count();
        List<String> newKeys = reserveMasterKeys(createCount);
        int keyIndex = 0;
        for (ImportCandidate<ShipToRequest> row : rows) {
            String nameKey = nameKey(row.getValue().shipToName());
            ShipTo entity = existingByName.get(nameKey);
            if (entity == null) {
                entity = new ShipTo();
                entity.setMasterKey(newKeys.get(keyIndex++));
                entity.setCreatedAt(now);
                result.setCreated(result.getCreated() + 1);
            } else {
                result.setUpdated(result.getUpdated() + 1);
            }
            apply(entity, row.getValue());
            entity.setUpdatedAt(now);
            toSave.add(entity);
        }
        repository.saveAll(toSave);
        return result;
    }

    public byte[] exportForEdit() {
        backfillMissingMasterKeys();
        return MasterDataEditWorkbookExporter.shipTos(repository.findAll());
    }

    public MasterDataImportResult uploadEdited(MultipartFile file) {
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<KeyedShipToRequest>> rows = parseEditedWorkbook(file, errors);
        int totalRows = rows.size();
        Set<String> requestedKeys = rows.stream().map(row -> row.getValue().masterKey)
                .filter(value -> value != null).collect(Collectors.toSet());
        Set<String> requestedNameKeys = rows.stream().filter(row -> row.getValue().request != null)
                .map(row -> nameKey(row.getValue().request.shipToName())).collect(Collectors.toSet());
        Set<String> requestedCodeKeys = rows.stream().filter(row -> row.getValue().request != null)
                .map(row -> codeKey(row.getValue().request.shipToCode())).filter(value -> value != null).collect(Collectors.toSet());
        Map<String, ShipTo> byKey = repository.findAllByMasterKeyIn(requestedKeys).stream()
                .collect(Collectors.toMap(item -> normalizeMasterKey(item.getMasterKey()), item -> item, (a, b) -> a));
        Map<String, ShipTo> byName = repository.findAllByShipToNameKeyIn(requestedNameKeys).stream()
                .collect(Collectors.toMap(ShipTo::getShipToNameKey, item -> item, (a, b) -> a));
        Map<String, ShipTo> byCode = repository.findAllByShipToCodeKeyIn(requestedCodeKeys).stream()
                .filter(item -> item.getShipToCodeKey() != null)
                .collect(Collectors.toMap(ShipTo::getShipToCodeKey, item -> item, (a, b) -> a));

        for (ImportCandidate<KeyedShipToRequest> row : rows) {
            KeyedShipToRequest keyed = row.getValue();
            ShipTo target = keyed.masterKey == null ? null : byKey.get(keyed.masterKey);
            if ("CREATE".equals(keyed.action)) {
                if (keyed.masterKey != null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "CREATE must have a blank Key"));
                if (keyed.rowVersion != null) errors.add(new ImportRowError(row.getRowNumber(), "rowVersion", "CREATE must have a blank Row Version"));
            } else {
                if (keyed.masterKey == null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", keyed.action + " requires a Key"));
                else if (target == null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "Key does not exist: " + keyed.masterKey));
                else if (!sameVersion(keyed.rowVersion, target.getVersion())) errors.add(new ImportRowError(row.getRowNumber(), "rowVersion", "Data has changed. Download a new edit file."));
            }
            if (target != null && "DELETE".equals(keyed.action) && isUsed(target)) {
                errors.add(new ImportRowError(row.getRowNumber(), "action", "Cannot delete Ship To because an MPR is using it"));
            }
            if ("DELETE".equals(keyed.action) || keyed.request == null) continue;
            ShipTo duplicateName = byName.get(nameKey(keyed.request.shipToName()));
            if (duplicateName != null && (target == null || !duplicateName.getId().equals(target.getId()))) {
                errors.add(new ImportRowError(row.getRowNumber(), "shipToName", "Ship To name already exists"));
            }
            String codeKey = codeKey(keyed.request.shipToCode());
            ShipTo duplicateCode = codeKey == null ? null : byCode.get(codeKey);
            if (duplicateCode != null && (target == null || !duplicateCode.getId().equals(target.getId()))) {
                errors.add(new ImportRowError(row.getRowNumber(), "shipToCode", "Ship To code already exists"));
            }
        }
        if (!errors.isEmpty()) return MasterDataImportResult.rejected(MASTER_DATA_NAME, ImportMode.UPSERT, totalRows, errors);

        MasterDataImportResult result = baseResult(ImportMode.UPSERT, totalRows);
        int createCount = (int) rows.stream().filter(row -> "CREATE".equals(row.getValue().action)).count();
        List<String> newKeys = reserveMasterKeys(createCount);
        int keyIndex = 0;
        LocalDateTime now = LocalDateTime.now();
        List<ShipTo> toSave = new ArrayList<>();
        List<ShipTo> toDelete = new ArrayList<>();
        for (ImportCandidate<KeyedShipToRequest> row : rows) {
            KeyedShipToRequest keyed = row.getValue();
            if ("DELETE".equals(keyed.action)) {
                toDelete.add(byKey.get(keyed.masterKey));
                result.setDeleted(result.getDeleted() + 1);
                continue;
            }
            ShipTo entity;
            if ("CREATE".equals(keyed.action)) {
                entity = new ShipTo();
                entity.setMasterKey(newKeys.get(keyIndex++));
                entity.setCreatedAt(now);
                result.setCreated(result.getCreated() + 1);
            } else {
                entity = byKey.get(keyed.masterKey);
                result.setUpdated(result.getUpdated() + 1);
            }
            apply(entity, keyed.request);
            entity.setUpdatedAt(now);
            toSave.add(entity);
        }
        if (!toSave.isEmpty()) repository.saveAll(toSave);
        if (!toDelete.isEmpty()) repository.deleteAll(toDelete);
        return result;
    }

    private List<ImportCandidate<ShipToRequest>> parseStandardWorkbook(MultipartFile file, List<ImportRowError> errors) {
        List<ImportCandidate<ShipToRequest>> rows = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(sheet, evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Ship To Code"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Ship To Name"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "Active"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "Remark"));
            Set<String> names = new HashSet<>();
            Set<String> codes = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 4, evaluator)) continue;
                int excelRow = rowIndex + 1;
                try {
                    ShipToRequest request = request(row, evaluator, 0);
                    addBeanErrors(errors, excelRow, beanValidator.validate(request));
                    String nameKey = nameKey(request.shipToName());
                    if (!names.add(nameKey)) errors.add(new ImportRowError(excelRow, "shipToName", "Duplicate Ship To name inside uploaded file"));
                    String codeKey = codeKey(request.shipToCode());
                    if (codeKey != null && !codes.add(codeKey)) errors.add(new ImportRowError(excelRow, "shipToCode", "Duplicate Ship To code inside uploaded file"));
                    rows.add(new ImportCandidate<>(excelRow, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import SHIP TO: " + cleanMessage(ex)));
        }
        return rows;
    }

    private List<ImportCandidate<KeyedShipToRequest>> parseEditedWorkbook(MultipartFile file, List<ImportRowError> errors) {
        List<ImportCandidate<KeyedShipToRequest>> rows = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(sheet, evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Key"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Row Version"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "Action"),
                    new MasterDataExcelSupport.HeaderRequirement(3, "Ship To Code"),
                    new MasterDataExcelSupport.HeaderRequirement(4, "Ship To Name"),
                    new MasterDataExcelSupport.HeaderRequirement(5, "Active"),
                    new MasterDataExcelSupport.HeaderRequirement(6, "Remark"));
            Set<String> keys = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 7, evaluator)) continue;
                int excelRow = rowIndex + 1;
                try {
                    KeyedShipToRequest keyed = new KeyedShipToRequest();
                    keyed.masterKey = normalizeUploadedMasterKey(excelSupport.text(row, 0, evaluator));
                    keyed.rowVersion = optionalLong(excelSupport.text(row, 1, evaluator));
                    keyed.action = normalizeAction(excelSupport.text(row, 2, evaluator), keyed.masterKey);
                    keyed.request = request(row, evaluator, 3);
                    if (!"DELETE".equals(keyed.action)) addBeanErrors(errors, excelRow, beanValidator.validate(keyed.request));
                    if (keyed.masterKey != null && !keys.add(keyed.masterKey)) errors.add(new ImportRowError(excelRow, "masterKey", "Duplicate Key inside uploaded file"));
                    rows.add(new ImportCandidate<>(excelRow, keyed));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import edited SHIP TO: " + cleanMessage(ex)));
        }
        return rows;
    }

    private ShipToRequest request(Row row, FormulaEvaluator evaluator, int offset) {
        String code = excelSupport.text(row, offset, evaluator);
        String name = excelSupport.text(row, offset + 1, evaluator);
        String activeText = MasterDataTextNormalizer.upper(excelSupport.text(row, offset + 2, evaluator));
        Boolean active = activeText == null || Set.of("TRUE", "YES", "Y", "1", "ACTIVE").contains(activeText);
        if (activeText != null && !Set.of("TRUE", "YES", "Y", "1", "ACTIVE", "FALSE", "NO", "N", "0", "INACTIVE").contains(activeText)) {
            throw new MasterDataValidationException("Active must be TRUE/FALSE, YES/NO, 1/0 or ACTIVE/INACTIVE");
        }
        String remark = excelSupport.text(row, offset + 3, evaluator);
        return new ShipToRequest(name, code, active, remark);
    }

    private void apply(ShipTo entity, ShipToRequest request) {
        String name = MasterDataTextNormalizer.trimToNull(request == null ? null : request.shipToName());
        if (name == null) throw new MasterDataValidationException("Ship To name is required");
        String code = MasterDataTextNormalizer.trimToNull(request.shipToCode());
        entity.setShipToName(name);
        entity.setShipToNameKey(MasterDataTextNormalizer.key(name));
        entity.setShipToCode(code);
        entity.setShipToCodeKey(codeKey(code));
        entity.setActive(request.active() == null || request.active());
        entity.setRemark(MasterDataTextNormalizer.trimToNull(request.remark()));
    }

    private boolean isUsed(ShipTo entity) {
        return entity != null && mprRepository.existsByLinesShipToIdsContaining(entity.getId());
    }

    private String nameKey(String value) {
        String key = MasterDataTextNormalizer.key(value);
        if (key == null) throw new MasterDataValidationException("Ship To name is required");
        return key;
    }

    private String codeKey(String value) {
        return MasterDataTextNormalizer.key(value);
    }

    private Pageable pageable(int page, int size) {
        if (page < 0) throw new MasterDataValidationException("page must be >= 0");
        if (size < 1 || size > 200) throw new MasterDataValidationException("size must be between 1 and 200");
        return PageRequest.of(page, size);
    }

    private void addContains(Query query, String field, String value) {
        String clean = MasterDataTextNormalizer.trimToNull(value);
        if (clean != null) query.addCriteria(Criteria.where(field).regex(Pattern.compile(Pattern.quote(clean), Pattern.CASE_INSENSITIVE)));
    }

    private synchronized void backfillMissingMasterKeys() {
        if (masterKeysBackfilled) return;
        Query query = new Query(new Criteria().orOperator(
                Criteria.where("masterKey").exists(false),
                Criteria.where("masterKey").is(null),
                Criteria.where("masterKey").is("")
        ));
        List<ShipTo> missing = mongoTemplate.find(query, ShipTo.class);
        if (!missing.isEmpty()) {
            List<String> keys = reserveMasterKeys(missing.size());
            for (int i = 0; i < missing.size(); i++) missing.get(i).setMasterKey(keys.get(i));
            repository.saveAll(missing);
        }
        masterKeysBackfilled = true;
    }

    private ShipTo ensureMasterKeyPersisted(ShipTo entity) {
        if (entity.getMasterKey() == null || entity.getMasterKey().isBlank()) {
            entity.setMasterKey(nextMasterKey());
            return repository.save(entity);
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
        ShipTo latest = mongoTemplate.findOne(query, ShipTo.class);
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
        try { return new java.math.BigDecimal(clean.replace(",", "")).longValueExact(); }
        catch (RuntimeException ex) { throw new MasterDataValidationException("Row Version must be a whole number"); }
    }

    private boolean sameVersion(Long uploaded, Long current) {
        return uploaded == null ? current == null : uploaded.equals(current);
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
            errors.add(new ImportRowError(row, parts[0], parts.length > 1 ? parts[1] : message));
        }
    }

    private String cleanMessage(Exception ex) {
        String message = MasterDataTextNormalizer.trimToNull(ex.getMessage());
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private static class KeyedShipToRequest {
        private String masterKey;
        private Long rowVersion;
        private String action;
        private ShipToRequest request;
    }
}
