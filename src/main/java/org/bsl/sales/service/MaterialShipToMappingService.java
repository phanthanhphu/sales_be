package org.bsl.sales.service;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.ImportRowError;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.dto.MaterialShipToMappingRequest;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.MaterialShipToMapping;
import org.bsl.sales.model.ShipTo;
import org.bsl.sales.repository.MaterialShipToMappingRepository;
import org.bsl.sales.repository.ShipToRepository;
import org.bsl.sales.security.BuyerAccessService;
import org.bsl.sales.support.BuyerKeys;
import org.bsl.sales.support.ImportCandidate;
import org.bsl.sales.support.MasterDataBeanValidator;
import org.bsl.sales.support.MasterDataEditWorkbookExporter;
import org.bsl.sales.support.MasterDataExcelSupport;
import org.bsl.sales.support.MasterDataTextNormalizer;
import org.bsl.sales.support.MaterialShipToMappingKeys;
import org.bsl.sales.support.NewestFirstSort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MaterialShipToMappingService {
    private static final String MASTER_DATA_NAME = "MATERIAL SHIP TO";
    private static final String MASTER_KEY_PREFIX = "MS";
    private static final String SEQUENCE_NAME = "material_ship_to_mapping";

    private final MaterialShipToMappingRepository repository;
    private final ShipToRepository shipToRepository;
    private final MasterDataBeanValidator beanValidator;
    private final MasterDataExcelSupport excelSupport;
    private final BuyerAccessService buyerAccess;
    private final MongoTemplate mongoTemplate;
    private final MasterDataSequenceService sequenceService;
    private volatile boolean masterKeysBackfilled;

    public MaterialShipToMappingService(
            MaterialShipToMappingRepository repository,
            ShipToRepository shipToRepository,
            MasterDataBeanValidator beanValidator,
            MasterDataExcelSupport excelSupport,
            BuyerAccessService buyerAccess,
            MongoTemplate mongoTemplate,
            MasterDataSequenceService sequenceService
    ) {
        this.repository = repository;
        this.shipToRepository = shipToRepository;
        this.beanValidator = beanValidator;
        this.excelSupport = excelSupport;
        this.buyerAccess = buyerAccess;
        this.mongoTemplate = mongoTemplate;
        this.sequenceService = sequenceService;
    }

    public String fileBuyerKey(String buyerKey) {
        return buyerAccess.requireBuyer(buyerKey);
    }

    public MaterialShipToMapping create(String buyerKey, MaterialShipToMappingRequest request) {
        String buyer = buyerKey(buyerKey);
        String materialKey = materialKey(request);
        if (repository.findByBuyerKeyAndMaterialKey(buyer, materialKey).isPresent()) {
            throw new MasterDataConflictException("This material already has a dedicated Ship To for Buyer " + buyer);
        }
        ShipTo shipTo = requireActiveShipTo(buyer, request.shipToId());
        MaterialShipToMapping entity = new MaterialShipToMapping();
        entity.setMasterKey(nextMasterKey());
        apply(entity, buyer, request, shipTo);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return repository.save(entity);
    }

    public Page<MaterialShipToMapping> list(
            String buyerKey,
            String sapCode,
            String materialType,
            String matFullDescription,
            String matColor,
            String matUnit,
            String shipTo,
            Boolean active,
            int page,
            int size
    ) {
        backfillMissingMasterKeys();
        String buyer = buyerKey(buyerKey);
        Pageable pageable = pageable(page, size);
        Query query = new Query(Criteria.where("buyerKey").is(buyer));
        addContains(query, "sapCode", sapCode);
        addContains(query, "materialType", materialType);
        addContains(query, "matFullDescription", matFullDescription);
        addContains(query, "matColor", matColor);
        addContains(query, "matUnit", matUnit);
        String shipToSearch = MasterDataTextNormalizer.trimToNull(shipTo);
        if (shipToSearch != null) {
            Pattern pattern = Pattern.compile(Pattern.quote(shipToSearch), Pattern.CASE_INSENSITIVE);
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("shipToCode").regex(pattern),
                    Criteria.where("shipToName").regex(pattern)
            ));
        }
        if (active != null) query.addCriteria(Criteria.where("active").is(active));
        long total = mongoTemplate.count(query, MaterialShipToMapping.class);
        query.with(NewestFirstSort.mongo());
        query.skip(pageable.getOffset()).limit(pageable.getPageSize());
        return new PageImpl<>(mongoTemplate.find(query, MaterialShipToMapping.class), pageable, total);
    }

    public MaterialShipToMapping getForBuyer(String buyerKey, String id) {
        String buyer = buyerKey(buyerKey);
        MaterialShipToMapping entity = repository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Material Ship To mapping not found"));
        if (!buyer.equals(BuyerKeys.legacyDefault(entity.getBuyerKey()))) {
            throw new MasterDataNotFoundException("Material Ship To mapping not found for Buyer " + buyer);
        }
        return ensureMasterKeyPersisted(entity);
    }

    public MaterialShipToMapping update(String buyerKey, String id, MaterialShipToMappingRequest request) {
        String buyer = buyerKey(buyerKey);
        MaterialShipToMapping entity = getForBuyer(buyer, id);
        String nextMaterialKey = materialKey(request);
        Optional<MaterialShipToMapping> duplicate = repository.findByBuyerKeyAndMaterialKey(buyer, nextMaterialKey);
        if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
            throw new MasterDataConflictException("This material already has a dedicated Ship To for Buyer " + buyer);
        }
        ShipTo shipTo = requireActiveShipTo(buyer, request.shipToId());
        apply(entity, buyer, request, shipTo);
        entity.setUpdatedAt(LocalDateTime.now());
        return repository.save(entity);
    }

    public void delete(String buyerKey, String id) {
        repository.delete(getForBuyer(buyerKey, id));
    }

    public List<MaterialShipToMapping> activeForBuyer(String buyerKey) {
        return repository.findByBuyerKeyAndActiveTrue(buyerKey(buyerKey));
    }

    public byte[] template(String buyerKey) {
        String buyer = buyerKey(buyerKey);
        List<ShipTo> activeShipTos = activeShipTosForWorkbook(buyer);
        return MasterDataEditWorkbookExporter.materialShipToTemplate(activeShipTos);
    }

    public byte[] exportForEdit(String buyerKey) {
        backfillMissingMasterKeys();
        String buyer = buyerKey(buyerKey);
        return MasterDataEditWorkbookExporter.materialShipToMappings(
                repository.findByBuyerKeyOrderByUpdatedAtDesc(buyer),
                activeShipTosForWorkbook(buyer)
        );
    }

    private List<ShipTo> activeShipTosForWorkbook(String buyer) {
        List<ShipTo> activeShipTos = shipToRepository.findByBuyerKeyAndActiveTrueOrderByShipToNameAsc(buyer);
        if (activeShipTos == null || activeShipTos.isEmpty()) {
            throw new MasterDataValidationException(
                    "Buyer " + buyer + " has no active Ship To. Add or activate a Ship To in Ship To Master before downloading this file."
            );
        }
        return activeShipTos;
    }

    public MasterDataImportResult upload(String buyerKey, MultipartFile file, ImportMode mode) {
        String buyer = buyerKey(buyerKey);
        ImportMode effectiveMode = mode == null ? ImportMode.CREATE_ONLY : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<MaterialShipToMappingRequest>> rows = parseStandardWorkbook(buyer, file, errors);
        int totalRows = rows.size();

        validateIncomingDuplicates(rows, errors);
        Set<String> materialKeys = rows.stream().map(row -> safeMaterialKey(row.getValue())).filter(v -> v != null).collect(Collectors.toSet());
        Map<String, MaterialShipToMapping> existing = repository.findAllByBuyerKeyAndMaterialKeyIn(buyer, materialKeys).stream()
                .collect(Collectors.toMap(MaterialShipToMapping::getMaterialKey, item -> item, (a, b) -> a, LinkedHashMap::new));

        for (ImportCandidate<MaterialShipToMappingRequest> row : rows) {
            String key = safeMaterialKey(row.getValue());
            if (key == null) continue;
            if (effectiveMode == ImportMode.CREATE_ONLY && existing.containsKey(key)) {
                errors.add(new ImportRowError(row.getRowNumber(), "material", "Material mapping already exists; CREATE_ONLY does not allow updates"));
            }
        }
        if (!errors.isEmpty()) return MasterDataImportResult.rejected(MASTER_DATA_NAME, effectiveMode, totalRows, errors);

        MasterDataImportResult result = baseResult(effectiveMode, totalRows);
        LocalDateTime now = LocalDateTime.now();
        List<MaterialShipToMapping> toSave = new ArrayList<>();

        if (effectiveMode == ImportMode.REPLACE_ALL) {
            List<MaterialShipToMapping> currentBuyerRows = repository.findByBuyerKeyOrderByUpdatedAtDesc(buyer);
            List<String> keys = reserveMasterKeys(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                MaterialShipToMappingRequest request = rows.get(i).getValue();
                MaterialShipToMapping entity = new MaterialShipToMapping();
                entity.setMasterKey(keys.get(i));
                entity.setCreatedAt(now);
                apply(entity, buyer, request, requireActiveShipTo(buyer, request.shipToId()));
                entity.setUpdatedAt(now);
                toSave.add(entity);
            }
            if (!currentBuyerRows.isEmpty()) repository.deleteAll(currentBuyerRows);
            if (!toSave.isEmpty()) repository.saveAll(toSave);
            result.setCreated(toSave.size());
            return result;
        }

        int creates = (int) rows.stream().filter(row -> !existing.containsKey(materialKey(row.getValue()))).count();
        List<String> newKeys = reserveMasterKeys(creates);
        int keyIndex = 0;
        for (ImportCandidate<MaterialShipToMappingRequest> row : rows) {
            MaterialShipToMappingRequest request = row.getValue();
            String materialKey = materialKey(request);
            MaterialShipToMapping entity = existing.get(materialKey);
            if (entity == null) {
                entity = new MaterialShipToMapping();
                entity.setMasterKey(newKeys.get(keyIndex++));
                entity.setCreatedAt(now);
                result.setCreated(result.getCreated() + 1);
            } else {
                result.setUpdated(result.getUpdated() + 1);
            }
            apply(entity, buyer, request, requireActiveShipTo(buyer, request.shipToId()));
            entity.setUpdatedAt(now);
            toSave.add(entity);
        }
        if (!toSave.isEmpty()) repository.saveAll(toSave);
        return result;
    }

    public MasterDataImportResult uploadEdited(String buyerKey, MultipartFile file) {
        String buyer = buyerKey(buyerKey);
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<KeyedRequest>> rows = parseEditedWorkbook(buyer, file, errors);
        int totalRows = rows.size();

        Set<String> requestedKeys = rows.stream().map(row -> row.getValue().masterKey)
                .filter(value -> value != null).collect(Collectors.toSet());
        Map<String, MaterialShipToMapping> byKey = repository.findAllByMasterKeyIn(requestedKeys).stream()
                .filter(item -> buyer.equals(BuyerKeys.legacyDefault(item.getBuyerKey())))
                .collect(Collectors.toMap(item -> normalizeMasterKey(item.getMasterKey()), item -> item, (a, b) -> a));

        Set<String> materialKeys = rows.stream()
                .filter(row -> row.getValue().request != null && !"DELETE".equals(row.getValue().action))
                .map(row -> safeMaterialKey(row.getValue().request)).filter(v -> v != null).collect(Collectors.toSet());
        Map<String, MaterialShipToMapping> existingByMaterial = repository.findAllByBuyerKeyAndMaterialKeyIn(buyer, materialKeys).stream()
                .collect(Collectors.toMap(MaterialShipToMapping::getMaterialKey, item -> item, (a, b) -> a));

        Set<String> fileMaterialKeys = new HashSet<>();
        for (ImportCandidate<KeyedRequest> row : rows) {
            KeyedRequest keyed = row.getValue();
            MaterialShipToMapping target = keyed.masterKey == null ? null : byKey.get(keyed.masterKey);
            if ("CREATE".equals(keyed.action)) {
                if (keyed.masterKey != null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "CREATE must have a blank Key"));
            } else {
                if (keyed.masterKey == null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", keyed.action + " requires a Key"));
                else if (target == null) errors.add(new ImportRowError(row.getRowNumber(), "masterKey", "Key does not exist for this Buyer: " + keyed.masterKey));
            }
            if ("DELETE".equals(keyed.action) || keyed.request == null) continue;
            String materialKey = safeMaterialKey(keyed.request);
            if (materialKey == null) continue;
            if (!fileMaterialKeys.add(materialKey)) {
                errors.add(new ImportRowError(row.getRowNumber(), "material", "Duplicate material identity inside uploaded file"));
            }
            MaterialShipToMapping duplicate = existingByMaterial.get(materialKey);
            if (duplicate != null && (target == null || !duplicate.getId().equals(target.getId()))) {
                errors.add(new ImportRowError(row.getRowNumber(), "material", "This material already has a dedicated Ship To for this Buyer"));
            }
        }
        if (!errors.isEmpty()) return MasterDataImportResult.rejected(MASTER_DATA_NAME, ImportMode.UPSERT, totalRows, errors);

        MasterDataImportResult result = baseResult(ImportMode.UPSERT, totalRows);
        int createCount = (int) rows.stream().filter(row -> "CREATE".equals(row.getValue().action)).count();
        List<String> newKeys = reserveMasterKeys(createCount);
        int keyIndex = 0;
        LocalDateTime now = LocalDateTime.now();
        List<MaterialShipToMapping> toSave = new ArrayList<>();
        List<MaterialShipToMapping> toDelete = new ArrayList<>();

        for (ImportCandidate<KeyedRequest> row : rows) {
            KeyedRequest keyed = row.getValue();
            if ("DELETE".equals(keyed.action)) {
                toDelete.add(byKey.get(keyed.masterKey));
                result.setDeleted(result.getDeleted() + 1);
                continue;
            }
            MaterialShipToMapping entity;
            if ("CREATE".equals(keyed.action)) {
                entity = new MaterialShipToMapping();
                entity.setMasterKey(newKeys.get(keyIndex++));
                entity.setCreatedAt(now);
                result.setCreated(result.getCreated() + 1);
            } else {
                entity = byKey.get(keyed.masterKey);
                result.setUpdated(result.getUpdated() + 1);
            }
            apply(entity, buyer, keyed.request, requireActiveShipTo(buyer, keyed.request.shipToId()));
            entity.setUpdatedAt(now);
            toSave.add(entity);
        }
        if (!toSave.isEmpty()) repository.saveAll(toSave);
        if (!toDelete.isEmpty()) repository.deleteAll(toDelete);
        return result;
    }

    private List<ImportCandidate<MaterialShipToMappingRequest>> parseStandardWorkbook(String buyer, MultipartFile file, List<ImportRowError> errors) {
        List<ImportCandidate<MaterialShipToMappingRequest>> rows = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            DataColumns columns = requireDataHeaders(sheet, evaluator, 0);
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, columns.endExclusive, evaluator)) continue;
                int excelRow = rowIndex + 1;
                try {
                    MaterialShipToMappingRequest request = request(buyer, row, evaluator, 0, columns);
                    addBeanErrors(errors, excelRow, beanValidator.validate(request));
                    validateRequestForImport(buyer, request, excelRow, errors);
                    rows.add(new ImportCandidate<>(excelRow, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import MATERIAL SHIP TO: " + cleanMessage(ex)));
        }
        return rows;
    }

    private List<ImportCandidate<KeyedRequest>> parseEditedWorkbook(String buyer, MultipartFile file, List<ImportRowError> errors) {
        List<ImportCandidate<KeyedRequest>> rows = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(sheet, evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Key"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Action"));
            DataColumns columns = requireDataHeaders(sheet, evaluator, 2);
            Set<String> keys = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, columns.endExclusive, evaluator)) continue;
                int excelRow = rowIndex + 1;
                try {
                    KeyedRequest keyed = new KeyedRequest();
                    keyed.masterKey = normalizeUploadedMasterKey(excelSupport.text(row, 0, evaluator));
                    keyed.action = normalizeAction(excelSupport.text(row, 1, evaluator), keyed.masterKey);
                    if (!"DELETE".equals(keyed.action)) {
                        keyed.request = request(buyer, row, evaluator, 2, columns);
                        addBeanErrors(errors, excelRow, beanValidator.validate(keyed.request));
                        validateRequestForImport(buyer, keyed.request, excelRow, errors);
                    }
                    if (keyed.masterKey != null && !keys.add(keyed.masterKey)) {
                        errors.add(new ImportRowError(excelRow, "masterKey", "Duplicate Key inside uploaded file"));
                    }
                    rows.add(new ImportCandidate<>(excelRow, keyed));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import edited MATERIAL SHIP TO: " + cleanMessage(ex)));
        }
        return rows;
    }

    private DataColumns requireDataHeaders(Sheet sheet, FormulaEvaluator evaluator, int offset) {
        Row header = sheet.getRow(sheet.getFirstRowNum());
        String firstShipToHeader = header == null ? null : excelSupport.text(header, offset + 5, evaluator);
        boolean legacyShipToCodeColumn = MasterDataTextNormalizer.headerKey("Ship To Code")
                .equals(MasterDataTextNormalizer.headerKey(firstShipToHeader));

        if (legacyShipToCodeColumn) {
            excelSupport.requireHeaders(sheet, evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(offset, "SAP Code"),
                    new MasterDataExcelSupport.HeaderRequirement(offset + 1, "Material Type"),
                    new MasterDataExcelSupport.HeaderRequirement(offset + 2, "MAT FULL DESCRIPTION"),
                    new MasterDataExcelSupport.HeaderRequirement(offset + 3, "MAT COLOR"),
                    new MasterDataExcelSupport.HeaderRequirement(offset + 4, "MAT UNIT"),
                    new MasterDataExcelSupport.HeaderRequirement(offset + 5, "Ship To Code"),
                    new MasterDataExcelSupport.HeaderRequirement(offset + 6, "Ship To Name"),
                    new MasterDataExcelSupport.HeaderRequirement(offset + 7, "Active"),
                    new MasterDataExcelSupport.HeaderRequirement(offset + 8, "Remark"));
            return new DataColumns(offset + 5, offset + 6, offset + 7, offset + 8, offset + 9);
        }

        excelSupport.requireHeaders(sheet, evaluator,
                new MasterDataExcelSupport.HeaderRequirement(offset, "SAP Code"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 1, "Material Type"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 2, "MAT FULL DESCRIPTION"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 3, "MAT COLOR"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 4, "MAT UNIT"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 5, "Ship To Name"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 6, "Active"),
                new MasterDataExcelSupport.HeaderRequirement(offset + 7, "Remark"));
        return new DataColumns(-1, offset + 5, offset + 6, offset + 7, offset + 8);
    }

    private MaterialShipToMappingRequest request(
            String buyer,
            Row row,
            FormulaEvaluator evaluator,
            int offset,
            DataColumns columns
    ) {
        String sapCode = excelSupport.text(row, offset, evaluator);
        String materialType = excelSupport.text(row, offset + 1, evaluator);
        String description = excelSupport.text(row, offset + 2, evaluator);
        String color = excelSupport.text(row, offset + 3, evaluator);
        String unit = excelSupport.text(row, offset + 4, evaluator);
        String shipToCode = columns.shipToCode < 0 ? null : excelSupport.text(row, columns.shipToCode, evaluator);
        String shipToName = excelSupport.text(row, columns.shipToName, evaluator);
        ShipTo shipTo = resolveShipTo(buyer, shipToCode, shipToName);
        String activeText = MasterDataTextNormalizer.upper(excelSupport.text(row, columns.active, evaluator));
        Boolean active = parseActive(activeText);
        String remark = excelSupport.text(row, columns.remark, evaluator);
        return new MaterialShipToMappingRequest(sapCode, materialType, description, color, unit, shipTo.getId(), active, remark);
    }

    private Boolean parseActive(String activeText) {
        if (activeText == null) return true;
        if (Set.of("TRUE", "YES", "Y", "1", "ACTIVE").contains(activeText)) return true;
        if (Set.of("FALSE", "NO", "N", "0", "INACTIVE").contains(activeText)) return false;
        throw new MasterDataValidationException("Active must be TRUE/FALSE, YES/NO, 1/0 or ACTIVE/INACTIVE");
    }

    private void validateIncomingDuplicates(List<ImportCandidate<MaterialShipToMappingRequest>> rows, List<ImportRowError> errors) {
        Set<String> keys = new HashSet<>();
        for (ImportCandidate<MaterialShipToMappingRequest> row : rows) {
            String key = safeMaterialKey(row.getValue());
            if (key != null && !keys.add(key)) {
                errors.add(new ImportRowError(row.getRowNumber(), "material", "Duplicate material identity inside uploaded file"));
            }
        }
    }

    private void validateRequestForImport(String buyer, MaterialShipToMappingRequest request, int row, List<ImportRowError> errors) {
        try {
            materialKey(request);
            requireActiveShipTo(buyer, request.shipToId());
        } catch (RuntimeException ex) {
            errors.add(new ImportRowError(row, "material", cleanMessage(ex)));
        }
    }

    private void apply(MaterialShipToMapping entity, String buyer, MaterialShipToMappingRequest request, ShipTo shipTo) {
        if (request == null) throw new MasterDataValidationException("Material Ship To data is required");
        String sapCode = MasterDataTextNormalizer.trimToNull(request.sapCode());
        String materialType = MasterDataTextNormalizer.trimToNull(request.materialType());
        String description = MasterDataTextNormalizer.trimToNull(request.matFullDescription());
        String color = MasterDataTextNormalizer.trimToNull(request.matColor());
        String unit = MasterDataTextNormalizer.trimToNull(request.matUnit());

        entity.setBuyerKey(buyer);
        entity.setSapCode(sapCode);
        entity.setMaterialType(materialType);
        entity.setMatFullDescription(description);
        entity.setMatColor(color);
        entity.setMatUnit(unit);
        entity.setMaterialKey(MaterialShipToMappingKeys.build(sapCode, materialType, description, null, color, unit));
        entity.setShipToId(shipTo.getId());
        entity.setShipToCode(MasterDataTextNormalizer.trimToNull(shipTo.getShipToCode()));
        entity.setShipToName(MasterDataTextNormalizer.trimToNull(shipTo.getShipToName()));
        entity.setActive(request.active() == null || request.active());
        entity.setRemark(MasterDataTextNormalizer.trimToNull(request.remark()));
    }

    private String materialKey(MaterialShipToMappingRequest request) {
        if (request == null) throw new MasterDataValidationException("Material Ship To data is required");
        String sapCode = MasterDataTextNormalizer.trimToNull(request.sapCode());
        String materialType = MasterDataTextNormalizer.trimToNull(request.materialType());
        String description = MasterDataTextNormalizer.trimToNull(request.matFullDescription());
        String unit = MasterDataTextNormalizer.trimToNull(request.matUnit());
        if (unit == null) throw new MasterDataValidationException("MAT Unit is required for Material Ship To matching");
        if (sapCode == null && materialType == null) {
            throw new MasterDataValidationException("Material Type is required when SAP Code is blank");
        }
        if (sapCode == null && description == null) {
            throw new MasterDataValidationException("MAT Full Description is required when SAP Code is blank");
        }
        return MaterialShipToMappingKeys.build(
                sapCode, materialType, description, null,
                MasterDataTextNormalizer.trimToNull(request.matColor()), unit
        );
    }

    private String safeMaterialKey(MaterialShipToMappingRequest request) {
        try { return materialKey(request); }
        catch (RuntimeException ex) { return null; }
    }

    private ShipTo requireActiveShipTo(String buyer, String id) {
        String clean = MasterDataTextNormalizer.trimToNull(id);
        if (clean == null) throw new MasterDataValidationException("Ship To is required");
        ShipTo shipTo = shipToRepository.findById(clean)
                .orElseThrow(() -> new MasterDataValidationException("Selected Ship To does not exist"));
        if (!buyer.equals(BuyerKeys.legacyDefault(shipTo.getBuyerKey()))) {
            throw new MasterDataValidationException("Selected Ship To does not belong to Buyer " + buyer);
        }
        if (!shipTo.isActive()) throw new MasterDataValidationException("Selected Ship To is inactive");
        return shipTo;
    }

    private ShipTo resolveShipTo(String buyer, String code, String name) {
        String cleanCode = MasterDataTextNormalizer.trimToNull(code);
        String cleanName = MasterDataTextNormalizer.trimToNull(name);
        ShipTo byCode = cleanCode == null ? null : shipToRepository.findByBuyerKeyAndShipToCodeIgnoreCase(buyer, cleanCode).orElse(null);
        ShipTo byName = cleanName == null ? null : shipToRepository.findByBuyerKeyAndShipToNameIgnoreCase(buyer, cleanName).orElse(null);
        if (byCode != null && byName != null && !byCode.getId().equals(byName.getId())) {
            throw new MasterDataValidationException("Ship To Code and Ship To Name refer to different records");
        }
        ShipTo shipTo = byCode != null ? byCode : byName;
        if (shipTo == null) {
            throw new MasterDataValidationException("Ship To Code or Ship To Name must match an existing Ship To Master record for Buyer " + buyer);
        }
        if (!shipTo.isActive()) throw new MasterDataValidationException("Selected Ship To is inactive");
        return shipTo;
    }

    private String buyerKey(String value) {
        String clean = MasterDataTextNormalizer.trimToNull(value);
        if (clean == null) throw new MasterDataValidationException("Buyer is required");
        return buyerAccess.requireBuyer(clean);
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
        List<MaterialShipToMapping> missing = mongoTemplate.find(query, MaterialShipToMapping.class);
        if (!missing.isEmpty()) {
            List<String> keys = reserveMasterKeys(missing.size());
            for (int i = 0; i < missing.size(); i++) missing.get(i).setMasterKey(keys.get(i));
            repository.saveAll(missing);
        }
        masterKeysBackfilled = true;
    }

    private MaterialShipToMapping ensureMasterKeyPersisted(MaterialShipToMapping entity) {
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
        MaterialShipToMapping latest = mongoTemplate.findOne(query, MaterialShipToMapping.class);
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
        if (!Set.of("CREATE", "UPDATE", "DELETE").contains(value)) {
            throw new MasterDataValidationException("Action must be CREATE, UPDATE or DELETE");
        }
        return value;
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

    private static class KeyedRequest {
        private String masterKey;
        private String action;
        private MaterialShipToMappingRequest request;
    }

    private static class DataColumns {
        private final int shipToCode;
        private final int shipToName;
        private final int active;
        private final int remark;
        private final int endExclusive;

        private DataColumns(int shipToCode, int shipToName, int active, int remark, int endExclusive) {
            this.shipToCode = shipToCode;
            this.shipToName = shipToName;
            this.active = active;
            this.remark = remark;
            this.endExclusive = endExclusive;
        }
    }
}
