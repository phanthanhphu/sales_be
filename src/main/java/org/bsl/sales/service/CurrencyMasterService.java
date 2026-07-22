package org.bsl.sales.service;

import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.sales.dto.CurrencyMasterRequest;
import org.bsl.sales.dto.ImportMode;
import org.bsl.sales.dto.ImportRowError;
import org.bsl.sales.dto.MasterDataImportResult;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.CurrencyMaster;
import org.bsl.sales.model.CurrencyRateHistory;
import org.bsl.sales.model.MatInfo;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.model.MprLine;
import org.bsl.sales.repository.CurrencyMasterRepository;
import org.bsl.sales.repository.CurrencyRateHistoryRepository;
import org.bsl.sales.repository.MatInfoRepository;
import org.bsl.sales.repository.MprDocumentRepository;
import org.bsl.sales.support.ImportCandidate;
import org.bsl.sales.support.MasterDataBeanValidator;
import org.bsl.sales.support.MasterDataExcelSupport;
import org.bsl.sales.support.MasterDataTextNormalizer;
import org.bsl.sales.support.NewestFirstSort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Currency Master is a rate ledger, not a single mutable record per code.
 *
 * - USD 23,000 and USD 24,000 may both exist as separate rows.
 * - The most recently added row for each Currency Code is used by MAT_INFO and MPR.
 * - A row referenced by MAT_INFO or MPR is immutable and cannot be deleted.
 */
@Service
public class CurrencyMasterService {

    private static final String MASTER_DATA_NAME = "CURRENCY";
    private static final BigDecimal VND_RATE_TO_VND = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_USD_RATE_TO_VND = new BigDecimal("23000");

    private final CurrencyMasterRepository currencyMasterRepository;
    private final CurrencyRateHistoryRepository currencyRateHistoryRepository;
    private final MatInfoRepository matInfoRepository;
    private final MprDocumentRepository mprDocumentRepository;
    private final MasterDataBeanValidator beanValidator;
    private final MasterDataExcelSupport excelSupport;
    private final MongoTemplate mongoTemplate;

    public CurrencyMasterService(
            CurrencyMasterRepository currencyMasterRepository,
            CurrencyRateHistoryRepository currencyRateHistoryRepository,
            MatInfoRepository matInfoRepository,
            MprDocumentRepository mprDocumentRepository,
            MasterDataBeanValidator beanValidator,
            MasterDataExcelSupport excelSupport,
            MongoTemplate mongoTemplate
    ) {
        this.currencyMasterRepository = currencyMasterRepository;
        this.currencyRateHistoryRepository = currencyRateHistoryRepository;
        this.matInfoRepository = matInfoRepository;
        this.mprDocumentRepository = mprDocumentRepository;
        this.beanValidator = beanValidator;
        this.excelSupport = excelSupport;
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void initialize() {
        migrateIndexes();
        migrateLegacyRows();
        createVndIfMissing();
        migrateLegacyCurrencyReferences();
    }

    /** Adds a new rate row. The same Code + Rate To VND cannot be duplicated. */
    public CurrencyMaster create(CurrencyMasterRequest request) {
        validateRequest(request);

        String code = codeKey(request.getCurrencyCode());
        BigDecimal rate = normalizeRate(request.getRateToVnd());
        if (currencyMasterRepository.existsByCurrencyCodeKeyAndRateToVnd(code, rate)) {
            throw new MasterDataConflictException(
                    "Currency rate already exists: 1 " + code + " = " + rate.toPlainString() + " VND"
            );
        }

        LocalDateTime now = LocalDateTime.now();
        CurrencyMaster entity = new CurrencyMaster();
        entity.setCurrencyCodeKey(code);
        entity.setCurrencyCode(code);
        entity.setCurrencyName(required(request.getCurrencyName(), "Currency name is required"));
        entity.setRateToVnd(rate);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return decorateUsage(currencyMasterRepository.save(entity));
    }

    /** Shows every rate row, including older rows, in the Currency screen. */
    public Page<CurrencyMaster> list(String keyword, int page, int size) {
        Pageable pageable = pageable(page, size);
        String filter = MasterDataTextNormalizer.key(keyword);

        List<CurrencyMaster> rows = currencyMasterRepository.findAll().stream()
                .filter(item -> matches(item, filter))
                .sorted(NewestFirstSort.comparator(
                        CurrencyMaster::getCreatedAt,
                        CurrencyMaster::getUpdatedAt,
                        CurrencyMaster::getId
                ))
                .map(this::decorateUsage)
                .collect(Collectors.toList());

        return page(rows, pageable);
    }

    /** Returns only one newest rate row for each code. Used by MAT_INFO select. */
    public List<CurrencyMaster> listCurrent() {
        Map<String, CurrencyMaster> newestByCode = new LinkedHashMap<>();
        currencyMasterRepository.findAll().stream()
                .filter(item -> {
                    String code = MasterDataTextNormalizer.upper(item.getCurrencyCode());
                    return code != null && code.matches("^[A-Z]{3}$");
                })
                .sorted(Comparator
                        .comparing(CurrencyMaster::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CurrencyMaster::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(item -> newestByCode.putIfAbsent(codeKey(item.getCurrencyCode()), item));

        return newestByCode.values().stream()
                .sorted(Comparator.comparing(CurrencyMaster::getCurrencyCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::decorateUsage)
                .collect(Collectors.toList());
    }

    /** Current/latest rate rows without usage decoration; optimized for bulk imports. */
    public Map<String, CurrencyMaster> currentCurrencyMap() {
        Map<String, CurrencyMaster> newestByCode = new LinkedHashMap<>();
        currencyMasterRepository.findAll().stream()
                .filter(item -> {
                    String code = MasterDataTextNormalizer.upper(item.getCurrencyCode());
                    return code != null && code.matches("^[A-Z]{3}$")
                            && item.getRateToVnd() != null
                            && item.getRateToVnd().compareTo(BigDecimal.ZERO) > 0;
                })
                .sorted(Comparator
                        .comparing(CurrencyMaster::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CurrencyMaster::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(item -> newestByCode.putIfAbsent(codeKey(item.getCurrencyCode()), item));
        return newestByCode;
    }

    public Set<String> currentCurrencyCodes() {
        return new LinkedHashSet<>(currentCurrencyMap().keySet());
    }

    public CurrencyMaster getById(String id) {
        return decorateUsage(findCurrency(id));
    }

    /** The current/latest rate is the most recently added row for this Currency Code. */
    public CurrencyMaster resolveCurrent(String currencyCode) {
        String code = codeKey(currencyCode);
        CurrencyMaster result = currencyMasterRepository
                .findFirstByCurrencyCodeKeyOrderByCreatedAtDescUpdatedAtDesc(code)
                .orElseThrow(() -> new MasterDataNotFoundException("Currency is not configured: " + code));

        if (result.getRateToVnd() == null || result.getRateToVnd().compareTo(BigDecimal.ZERO) <= 0) {
            throw new MasterDataValidationException("Currency does not have a usable Rate To VND: " + code);
        }
        return decorateUsage(result);
    }

    public BigDecimal calculateAmountInVnd(String currencyCode, BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new MasterDataValidationException("Amount must be zero or greater");
        }
        CurrencyMaster currency = resolveCurrent(currencyCode);
        return amount.multiply(currency.getRateToVnd()).setScale(2, RoundingMode.HALF_UP);
    }

    /** Existing rows can be edited only while they are not referenced. */
    public CurrencyMaster update(String id, CurrencyMasterRequest request) {
        validateRequest(request);
        CurrencyMaster existing = findCurrency(id);
        ensureMutable(existing);

        String code = codeKey(request.getCurrencyCode());
        if (!code.equals(existing.getCurrencyCodeKey())) {
            throw new MasterDataValidationException("Currency Code cannot be changed after creation");
        }
        if ("VND".equals(code)) {
            throw new MasterDataConflictException("VND is the base currency and cannot be edited");
        }

        BigDecimal rate = normalizeRate(request.getRateToVnd());
        Optional<CurrencyMaster> duplicate = currencyMasterRepository.findByCurrencyCodeKeyAndRateToVnd(code, rate);
        if (duplicate.isPresent() && !existing.getId().equals(duplicate.get().getId())) {
            throw new MasterDataConflictException(
                    "Another Currency row already has this Rate To VND. Add a different rate instead."
            );
        }

        existing.setCurrencyName(required(request.getCurrencyName(), "Currency name is required"));
        existing.setRateToVnd(rate);
        existing.setUpdatedAt(LocalDateTime.now());
        return decorateUsage(currencyMasterRepository.save(existing));
    }

    public void delete(String id) {
        CurrencyMaster existing = findCurrency(id);
        ensureMutable(existing);
        currencyRateHistoryRepository.deleteByCurrencyId(existing.getId());
        currencyMasterRepository.delete(existing);
    }

    /**
     * Currency import treats each Code + Rate as an independent rate row.
     * UPSERT adds new rates, skips exact existing rows, and never rewrites a
     * referenced rate row. REPLACE_ALL remains disabled for audit safety.
     */
    public MasterDataImportResult upload(MultipartFile file, ImportMode mode) {
        ImportMode effectiveMode = mode == null ? ImportMode.UPSERT : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<ImportCandidate<CurrencyMasterRequest>> rows = new ArrayList<>();
        int totalRows = 0;

        if (effectiveMode == ImportMode.REPLACE_ALL) {
            errors.add(new ImportRowError(1, "mode", "REPLACE_ALL is disabled for Currency Master."));
            return MasterDataImportResult.rejected(MASTER_DATA_NAME, effectiveMode, totalRows, errors);
        }

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, MASTER_DATA_NAME);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            excelSupport.requireHeaders(
                    sheet,
                    evaluator,
                    new MasterDataExcelSupport.HeaderRequirement(0, "Currency Code", "CUR", "Currency"),
                    new MasterDataExcelSupport.HeaderRequirement(1, "Currency Name", "Name"),
                    new MasterDataExcelSupport.HeaderRequirement(2, "Rate To VND", "Convert To VND", "Exchange Rate To VND", "Rate")
            );

            Set<String> incomingKeys = new HashSet<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (excelSupport.isBlank(row, 3, evaluator)) continue;

                totalRows++;
                int excelRow = rowIndex + 1;
                try {
                    CurrencyMasterRequest request = new CurrencyMasterRequest();
                    request.setCurrencyCode(excelSupport.text(row, 0, evaluator));
                    request.setCurrencyName(excelSupport.text(row, 1, evaluator));
                    request.setRateToVnd(excelSupport.decimal(row, 2, evaluator));

                    List<String> beanErrors = beanValidator.validate(request);
                    addBeanErrors(errors, excelRow, beanErrors);
                    if (beanErrors.isEmpty()) validateRequest(request);

                    String identity = rateIdentity(request.getCurrencyCode(), request.getRateToVnd());
                    if (!incomingKeys.add(identity)) {
                        errors.add(new ImportRowError(excelRow, "rateToVnd", "Duplicate Currency Code and Rate To VND inside uploaded file"));
                    }
                    rows.add(new ImportCandidate<>(excelRow, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(excelRow, "row", cleanMessage(ex)));
                }
            }
        } catch (MasterDataValidationException ex) {
            errors.add(new ImportRowError(1, "file", cleanMessage(ex)));
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import CURRENCY: " + cleanMessage(ex)));
        }

        validateImportRows(effectiveMode, rows, errors);
        if (!errors.isEmpty()) {
            return MasterDataImportResult.rejected(MASTER_DATA_NAME, effectiveMode, totalRows, errors);
        }

        MasterDataImportResult result = new MasterDataImportResult();
        result.setMasterData(MASTER_DATA_NAME);
        result.setMode(effectiveMode);
        result.setApplied(true);
        result.setTotalRows(totalRows);
        result.setValidRows(rows.size());

        for (ImportCandidate<CurrencyMasterRequest> row : rows) {
            CurrencyMasterRequest request = row.getValue();
            String code = codeKey(request.getCurrencyCode());
            BigDecimal rate = normalizeRate(request.getRateToVnd());
            Optional<CurrencyMaster> existing = currencyMasterRepository.findByCurrencyCodeKeyAndRateToVnd(code, rate);
            if (existing.isPresent()) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            create(request);
            result.setCreated(result.getCreated() + 1);
        }
        return result;
    }

    private void validateImportRows(ImportMode mode, List<ImportCandidate<CurrencyMasterRequest>> rows, List<ImportRowError> errors) {
        for (ImportCandidate<CurrencyMasterRequest> row : rows) {
            try {
                validateRequest(row.getValue());
                String code = codeKey(row.getValue().getCurrencyCode());
                BigDecimal rate = normalizeRate(row.getValue().getRateToVnd());
                if (mode == ImportMode.CREATE_ONLY && currencyMasterRepository.existsByCurrencyCodeKeyAndRateToVnd(code, rate)) {
                    errors.add(new ImportRowError(row.getRowNumber(), "rateToVnd", "This Currency Code and Rate To VND already exist; CREATE_ONLY does not allow duplicates"));
                }
            } catch (RuntimeException ex) {
                errors.add(new ImportRowError(row.getRowNumber(), "currencyCode", cleanMessage(ex)));
            }
        }
    }

    private void ensureMutable(CurrencyMaster currency) {
        if (isVnd(currency)) {
            throw new MasterDataConflictException("VND is the base currency and cannot be edited or deleted");
        }
        String message = usageMessage(currency);
        if (message != null) {
            throw new MasterDataConflictException("Cannot change this Currency row because it is used by " + message);
        }
    }

    private CurrencyMaster decorateUsage(CurrencyMaster currency) {
        String message = usageMessage(currency);
        currency.setLocked(message != null || isVnd(currency));
        currency.setLockMessage(
                isVnd(currency)
                        ? "VND is the fixed base currency."
                        : message
        );
        return currency;
    }

    private boolean isVnd(CurrencyMaster currency) {
        return currency != null && "VND".equals(MasterDataTextNormalizer.upper(currency.getCurrencyCode()));
    }

    /** Precise references use currencyMasterId; legacy code-only data is locked conservatively. */
    private String usageMessage(CurrencyMaster currency) {
        String id = currency.getId();
        String code = codeKey(currency.getCurrencyCode());

        for (MatInfo item : matInfoRepository.findAll()) {
            if (id != null && id.equals(item.getCurrencyMasterId())) {
                return "MAT_INFO";
            }
            if (item.getCurrencyMasterId() == null || item.getCurrencyMasterId().isBlank()) {
                if (code.equals(MasterDataTextNormalizer.upper(item.getCurrency()))) {
                    return "legacy MAT_INFO";
                }
            }
        }

        for (MprDocument mpr : mprDocumentRepository.findAll()) {
            for (MprLine line : safe(mpr.getLines())) {
                if (line == null) continue;
                if (id != null && id.equals(line.getCurrencyMasterId())) {
                    return "MPR";
                }
                if (line.getCurrencyMasterId() == null || line.getCurrencyMasterId().isBlank()) {
                    if (code.equals(MasterDataTextNormalizer.upper(line.getCurrency()))) {
                        return "legacy MPR";
                    }
                }
            }
        }
        return null;
    }

    private void validateRequest(CurrencyMasterRequest request) {
        if (request == null) throw new MasterDataValidationException("Currency data is required");
        String code = codeKey(request.getCurrencyCode());
        String name = required(request.getCurrencyName(), "Currency name is required");
        if (name.length() > 100) throw new MasterDataValidationException("Currency name must not exceed 100 characters");
        validateRate(code, request.getRateToVnd());
    }

    private void validateRate(String code, BigDecimal rateToVnd) {
        if (rateToVnd == null || rateToVnd.compareTo(BigDecimal.ZERO) <= 0) {
            throw new MasterDataValidationException("Rate To VND must be greater than 0");
        }
        if ("VND".equals(code) && rateToVnd.compareTo(VND_RATE_TO_VND) != 0) {
            throw new MasterDataValidationException("VND Rate To VND must be exactly 1");
        }
    }

    private void migrateIndexes() {
        try {
            List<IndexInfo> indexes = mongoTemplate.indexOps(CurrencyMaster.class).getIndexInfo();
            for (IndexInfo index : indexes) {
                if ("currencyCodeKey_1".equals(index.getName()) || "currencyCodeKey".equals(index.getName())) {
                    mongoTemplate.indexOps(CurrencyMaster.class).dropIndex(index.getName());
                }
            }
            mongoTemplate.indexOps(CurrencyMaster.class).ensureIndex(
                    new Index()
                            .on("currencyCodeKey", Sort.Direction.ASC)
                            .on("rateToVnd", Sort.Direction.ASC)
                            .unique()
                            .named("currency_code_rate_to_vnd_unique")
            );
        } catch (Exception ignored) {
            // The next normal application start retries the safe migration.
        }
    }

    /** Copies a usable legacy rate from the old private history projection once. */
    private void migrateLegacyRows() {
        for (CurrencyMaster row : currencyMasterRepository.findAll()) {
            String code = MasterDataTextNormalizer.upper(row.getCurrencyCode());
            if (code == null || !code.matches("^[A-Z]{3}$")) continue;

            boolean changed = false;
            if (!code.equals(row.getCurrencyCode())) { row.setCurrencyCode(code); changed = true; }
            if (!code.equals(row.getCurrencyCodeKey())) { row.setCurrencyCodeKey(code); changed = true; }
            if (MasterDataTextNormalizer.trimToNull(row.getCurrencyName()) == null) { row.setCurrencyName(code); changed = true; }
            if (row.getCreatedAt() == null) { row.setCreatedAt(LocalDateTime.now()); changed = true; }
            if (row.getUpdatedAt() == null) { row.setUpdatedAt(row.getCreatedAt()); changed = true; }

            if (row.getRateToVnd() == null || row.getRateToVnd().compareTo(BigDecimal.ZERO) <= 0) {
                CurrencyRateHistory legacy = currencyRateHistoryRepository
                        .findFirstByCurrencyCodeKeyOrderByEffectiveAtDescCreatedAtDesc(code)
                        .orElse(null);
                if (legacy != null && legacy.getRateToVnd() != null && legacy.getRateToVnd().compareTo(BigDecimal.ZERO) > 0) {
                    row.setRateToVnd(normalizeRate(legacy.getRateToVnd()));
                    changed = true;
                } else if ("VND".equals(code)) {
                    row.setRateToVnd(VND_RATE_TO_VND);
                    changed = true;
                } else if ("USD".equals(code)) {
                    row.setRateToVnd(DEFAULT_USD_RATE_TO_VND);
                    changed = true;
                }
            }
            if (changed) currencyMasterRepository.save(row);
        }
    }

    /**
     * Legacy MAT_INFO/MPR documents did not contain a Currency rate-row id.
     * Bind them once so later edit/delete locks apply to the exact rate row,
     * rather than blocking every row that shares the same Currency Code.
     *
     * MAT_INFO did not store a historical rate, so it is bound to the rate
     * that was current at migration time. MPR Lines do store rateToVnd, so
     * they are matched to the exact Code + Rate row whenever possible.
     */
    private void migrateLegacyCurrencyReferences() {
        for (MatInfo item : matInfoRepository.findAll()) {
            if (MasterDataTextNormalizer.trimToNull(item.getCurrencyMasterId()) != null) {
                continue;
            }

            CurrencyMaster current = currentCurrencyOrNull(item.getCurrency());
            if (current != null) {
                item.setCurrencyMasterId(current.getId());
                matInfoRepository.save(item);
            }
        }

        for (MprDocument document : mprDocumentRepository.findAll()) {
            boolean changed = false;

            for (MprLine line : safe(document.getLines())) {
                if (line == null || MasterDataTextNormalizer.trimToNull(line.getCurrencyMasterId()) != null) {
                    continue;
                }

                CurrencyMaster current = null;
                String code = MasterDataTextNormalizer.upper(line.getCurrency());

                if (code != null && code.matches("^[A-Z]{3}$")) {
                    if (line.getRateToVnd() != null && line.getRateToVnd().compareTo(BigDecimal.ZERO) > 0) {
                        current = currencyMasterRepository
                                .findByCurrencyCodeKeyAndRateToVnd(code, normalizeRate(line.getRateToVnd()))
                                .orElse(null);
                    }

                    if (current == null) {
                        current = currentCurrencyOrNull(code);
                    }
                }

                if (current != null) {
                    line.setCurrencyMasterId(current.getId());
                    changed = true;
                }
            }

            if (changed) {
                mprDocumentRepository.save(document);
            }
        }
    }

    private CurrencyMaster currentCurrencyOrNull(String currencyCode) {
        String code = MasterDataTextNormalizer.upper(currencyCode);
        if (code == null || !code.matches("^[A-Z]{3}$")) {
            return null;
        }

        return currencyMasterRepository
                .findFirstByCurrencyCodeKeyOrderByCreatedAtDescUpdatedAtDesc(code)
                .orElse(null);
    }

    private void createVndIfMissing() {
        if (currencyMasterRepository.existsByCurrencyCodeKeyAndRateToVnd("VND", VND_RATE_TO_VND)) return;
        CurrencyMasterRequest request = new CurrencyMasterRequest();
        request.setCurrencyCode("VND");
        request.setCurrencyName("Vietnamese Dong");
        request.setRateToVnd(VND_RATE_TO_VND);
        create(request);
    }

    private CurrencyMaster findCurrency(String id) {
        if (MasterDataTextNormalizer.trimToNull(id) == null) {
            throw new MasterDataValidationException("Currency id is required");
        }
        return currencyMasterRepository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Currency not found"));
    }

    private String codeKey(String value) {
        String code = MasterDataTextNormalizer.upper(value);
        if (code == null) throw new MasterDataValidationException("Currency code is required");
        if (!code.matches("^[A-Z]{3}$")) throw new MasterDataValidationException("Currency code must contain exactly 3 letters");
        return code;
    }

    private String required(String value, String message) {
        String text = MasterDataTextNormalizer.trimToNull(value);
        if (text == null) throw new MasterDataValidationException(message);
        return text;
    }

    private boolean matches(CurrencyMaster item, String filter) {
        if (filter == null) return true;
        return contains(item.getCurrencyCode(), filter) || contains(item.getCurrencyName(), filter);
    }

    private boolean contains(String value, String filter) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(filter);
    }

    private String rateIdentity(String code, BigDecimal rate) {
        return codeKey(code) + "|" + normalizeRate(rate).toPlainString();
    }

    private BigDecimal normalizeRate(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private Pageable pageable(int page, int size) {
        if (page < 0) throw new MasterDataValidationException("page must be >= 0");
        if (size < 1 || size > 200) throw new MasterDataValidationException("size must be between 1 and 200");
        return PageRequest.of(page, size);
    }

    private <T> Page<T> page(List<T> all, Pageable pageable) {
        int from = Math.min((int) pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    private void addBeanErrors(List<ImportRowError> errors, int row, List<String> messages) {
        for (String message : messages) {
            String[] parts = message.split(": ", 2);
            errors.add(new ImportRowError(row, parts.length == 2 ? parts[0] : "row", parts.length == 2 ? parts[1] : message));
        }
    }

    private String cleanMessage(Exception error) {
        return MasterDataTextNormalizer.trimToNull(error.getMessage()) == null ? "Invalid data" : error.getMessage();
    }

    private <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : value;
    }
}
