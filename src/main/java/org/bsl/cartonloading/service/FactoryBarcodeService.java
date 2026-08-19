package org.bsl.cartonloading.service;

import org.bsl.cartonloading.dto.barcode.FactoryBarcodeGenerateRequest;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeGenerateResponse;
import org.bsl.cartonloading.dto.barcode.FactoryBarcodeSequenceResponse;
import org.bsl.cartonloading.enums.FactoryBarcodeStatus;
import org.bsl.cartonloading.model.CartonScanTransaction;
import org.bsl.cartonloading.model.FactoryBarcode;
import org.bsl.cartonloading.repository.FactoryBarcodeRepository;
import org.bsl.cartonloading.repository.CartonScanTransactionRepository;
import org.bson.Document;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class FactoryBarcodeService {
    private static final int RUNNING_LENGTH = 9;
    private static final long MAX_RUNNING = 999_999_999L;

    private final FactoryBarcodeRepository repository;
    private final CartonScanTransactionRepository cartonRepository;
    private final MongoTemplate mongoTemplate;

    public FactoryBarcodeService(
            FactoryBarcodeRepository repository,
            CartonScanTransactionRepository cartonRepository,
            MongoTemplate mongoTemplate
    ) {
        this.repository = repository;
        this.cartonRepository = cartonRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public FactoryBarcodeGenerateResponse generate(FactoryBarcodeGenerateRequest request) {
        int year = validateYear(request == null ? null : request.year());
        String factoryCode = validateFactoryCode(request == null ? null : request.factoryCode());
        int quantity = validateQuantity(request == null ? null : request.quantity());

        String sequenceId = sequenceCounterId(year, factoryCode);
        long last = reserveCounter(sequenceId, quantity);
        long first = last - quantity + 1;
        if (first <= 0 || last > MAX_RUNNING) {
            throw new IllegalArgumentException("Factory barcode running number exceeded the supported 9-digit range.");
        }

        long batchNo = reserveCounter("factory_barcode_batch", 1);
        String batchId = String.format(
                Locale.ROOT,
                "FB-%s-%06d",
                LocalDate.now().toString().replace("-", ""),
                batchNo
        );
        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        List<FactoryBarcode> rows = new ArrayList<>(quantity);

        for (long running = first; running <= last; running++) {
            FactoryBarcode row = new FactoryBarcode();
            row.setBarcode(formatBarcode(year, factoryCode, running));
            row.setYear(year);
            row.setFactoryCode(factoryCode);
            row.setRunningNumber(running);
            row.setBatchId(batchId);
            row.setStatus(FactoryBarcodeStatus.AVAILABLE);
            row.setPrintCount(0);
            row.setCreatedBy(actor);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            rows.add(row);
        }

        List<FactoryBarcode> saved = repository.saveAll(rows);
        return new FactoryBarcodeGenerateResponse(
                batchId,
                year,
                factoryCode,
                first,
                last,
                saved.size(),
                saved
        );
    }

    public Page<FactoryBarcode> list(
            String keyword,
            String status,
            String factoryCode,
            Integer year,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)));
        List<Criteria> criteria = new ArrayList<>();
        String key = clean(keyword);
        if (key != null) {
            String escaped = java.util.regex.Pattern.quote(key);
            criteria.add(new Criteria().orOperator(
                    Criteria.where("barcode").regex(escaped, "i"),
                    Criteria.where("batchId").regex(escaped, "i"),
                    Criteria.where("assignedOrderName").regex(escaped, "i"),
                    Criteria.where("assignedCartonCode").regex(escaped, "i"),
                    Criteria.where("assignedPoNumber").regex(escaped, "i"),
                    Criteria.where("assignedArticleNumber").regex(escaped, "i")
            ));
        }
        String statusKey = clean(status);
        if (statusKey != null && !"ALL".equalsIgnoreCase(statusKey)) {
            try {
                criteria.add(Criteria.where("status").is(FactoryBarcodeStatus.valueOf(statusKey.toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unsupported barcode status: " + status);
            }
        }
        String code = clean(factoryCode);
        if (code != null) criteria.add(Criteria.where("factoryCode").is(validateFactoryCode(code)));
        if (year != null) criteria.add(Criteria.where("year").is(validateYear(year)));

        Query query = new Query();
        if (!criteria.isEmpty()) query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        long total = mongoTemplate.count(query, FactoryBarcode.class);
        query.with(Sort.by(Sort.Direction.DESC, "createdAt", "runningNumber"));
        query.with(pageable);
        List<FactoryBarcode> content = mongoTemplate.find(query, FactoryBarcode.class);
        return new PageImpl<>(content, pageable, total);
    }

    public FactoryBarcode get(String barcode) {
        String code = required(barcode, "Factory Barcode is required");
        return repository.findByBarcode(code)
                .orElseThrow(() -> new IllegalArgumentException("Factory Barcode does not exist: " + code));
    }

    public FactoryBarcodeSequenceResponse sequence(Integer yearValue, String factoryCodeValue) {
        int year = validateYear(yearValue);
        String factoryCode = validateFactoryCode(factoryCodeValue);
        Document counter = mongoTemplate.findById(sequenceCounterId(year, factoryCode), Document.class, "system_counters");
        Number current = counter == null ? null : (Number) counter.get("value");
        long next = current == null ? 1L : current.longValue() + 1L;
        if (next > MAX_RUNNING) throw new IllegalArgumentException("Factory barcode running number is exhausted for this year/factory.");
        return new FactoryBarcodeSequenceResponse(year, factoryCode, next, formatBarcode(year, factoryCode, next));
    }

    public List<FactoryBarcode> markPrinted(List<String> barcodeValues) {
        if (barcodeValues == null || barcodeValues.isEmpty()) {
            throw new IllegalArgumentException("Select at least one Factory Barcode to print.");
        }
        if (barcodeValues.size() > 500) {
            throw new IllegalArgumentException("A maximum of 500 labels can be marked printed in one request.");
        }
        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        List<FactoryBarcode> updated = new ArrayList<>();
        for (String value : barcodeValues) {
            FactoryBarcode row = get(value);
            if (row.getStatus() == FactoryBarcodeStatus.VOID) {
                throw new IllegalArgumentException("VOID barcode cannot be printed: " + row.getBarcode());
            }
            row.setPrintCount((row.getPrintCount() == null ? 0 : row.getPrintCount()) + 1);
            row.setLastPrintedAt(now);
            row.setLastPrintedBy(actor);
            row.setUpdatedAt(now);
            updated.add(row);
        }
        return repository.saveAll(updated);
    }

    public FactoryBarcode voidBarcode(String barcode, String reason) {
        FactoryBarcode row = get(barcode);
        if (row.getStatus() == FactoryBarcodeStatus.ASSIGNED) {
            throw new IllegalArgumentException("Assigned Factory Barcode cannot be VOID. Unassign it from the carton first.");
        }
        if (row.getStatus() == FactoryBarcodeStatus.VOID) return row;
        LocalDateTime now = LocalDateTime.now();
        row.setStatus(FactoryBarcodeStatus.VOID);
        row.setVoidReason(clean(reason));
        row.setVoidBy(RequestActor.current());
        row.setVoidAt(now);
        row.setUpdatedAt(now);
        return repository.save(row);
    }

    public synchronized FactoryBarcode assignToCarton(FactoryBarcode barcode, CartonScanTransaction carton) {
        if (barcode == null) throw new IllegalArgumentException("Factory Barcode is required");
        if (carton == null) throw new IllegalArgumentException("Carton is required");

        FactoryBarcode current = get(barcode.getBarcode());
        if (current.getStatus() == FactoryBarcodeStatus.ASSIGNED) {
            if (carton.getId() != null && carton.getId().equals(current.getAssignedCartonId())) return current;
            throw new IllegalArgumentException("Factory Barcode " + current.getBarcode() + " is already assigned to another carton.");
        }
        if (current.getStatus() == FactoryBarcodeStatus.VOID) {
            throw new IllegalArgumentException("Factory Barcode " + current.getBarcode() + " is VOID and cannot be assigned.");
        }

        LocalDateTime now = LocalDateTime.now();
        current.setStatus(FactoryBarcodeStatus.ASSIGNED);
        current.setAssignedBuyerCode(carton.getBuyerCode());
        current.setAssignedOrderId(carton.getOrderId());
        current.setAssignedOrderName(carton.getOrderName());
        current.setAssignedCartonId(carton.getId());
        current.setAssignedCartonCode(carton.getCartonCode());
        current.setAssignedCartonNumber(carton.getCartonNumber());
        current.setAssignedMasterLineId(carton.getMasterLineId());
        current.setAssignedPoNumber(carton.getPoNumber());
        current.setAssignedArticleNumber(carton.getArticleNumber());
        current.setAssignedColor(carton.getColor());
        current.setAssignedSize(carton.getSize());
        current.setAssignedQtyPerCarton(carton.getQtyPerCarton() == null ? null : carton.getQtyPerCarton().stripTrailingZeros().toPlainString());
        current.setAssignedQuantity(carton.getCartonPcs() == null ? null : carton.getCartonPcs().stripTrailingZeros().toPlainString());
        current.setAssignedBy(RequestActor.current());
        current.setAssignedAt(now);
        current.setUpdatedAt(now);
        return repository.save(current);
    }

    public synchronized FactoryBarcode releaseAssignment(String barcodeValue, String cartonId) {
        FactoryBarcode current = get(barcodeValue);
        if (current.getStatus() != FactoryBarcodeStatus.ASSIGNED) return current;
        if (cartonId != null && current.getAssignedCartonId() != null && !cartonId.equals(current.getAssignedCartonId())) {
            throw new IllegalArgumentException("Factory Barcode is assigned to a different carton.");
        }
        current.setStatus(FactoryBarcodeStatus.AVAILABLE);
        current.setAssignedBuyerCode(null);
        current.setAssignedOrderId(null);
        current.setAssignedOrderName(null);
        current.setAssignedCartonId(null);
        current.setAssignedCartonCode(null);
        current.setAssignedCartonNumber(null);
        current.setAssignedMasterLineId(null);
        current.setAssignedPoNumber(null);
        current.setAssignedArticleNumber(null);
        current.setAssignedColor(null);
        current.setAssignedSize(null);
        current.setAssignedQtyPerCarton(null);
        current.setAssignedQuantity(null);
        current.setAssignedBy(null);
        current.setAssignedAt(null);
        current.setUpdatedAt(LocalDateTime.now());
        return repository.save(current);
    }

    public FactoryBarcode requireAvailable(String barcodeValue) {
        FactoryBarcode row = get(barcodeValue);
        if (row.getStatus() == FactoryBarcodeStatus.VOID) {
            throw new IllegalArgumentException("Factory Barcode is VOID: " + row.getBarcode());
        }
        if (row.getStatus() == FactoryBarcodeStatus.ASSIGNED) {
            throw new IllegalArgumentException("Factory Barcode is already assigned to carton "
                    + (row.getAssignedCartonCode() == null ? row.getAssignedCartonId() : row.getAssignedCartonCode()) + ".");
        }
        return row;
    }

    public FactoryBarcode requireAssigned(String barcodeValue) {
        FactoryBarcode row = get(barcodeValue);
        if (row.getStatus() != FactoryBarcodeStatus.ASSIGNED) {
            throw new IllegalArgumentException("Factory Barcode has not been assigned to a carton: " + row.getBarcode());
        }
        return row;
    }

    public CartonScanTransaction getAssignedCarton(String barcodeValue) {
        FactoryBarcode row = requireAssigned(barcodeValue);
        if (row.getAssignedCartonId() == null) {
            throw new IllegalArgumentException("Factory Barcode assignment is incomplete: " + row.getBarcode());
        }
        return cartonRepository.findById(row.getAssignedCartonId())
                .orElseThrow(() -> new IllegalArgumentException("Assigned carton record no longer exists for Factory Barcode " + row.getBarcode()));
    }

    private long reserveCounter(String counterId, long increment) {
        Query query = Query.query(Criteria.where("_id").is(counterId));
        Update update = new Update().inc("value", increment);
        Document counter = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                Document.class,
                "system_counters"
        );
        Number value = counter == null ? null : (Number) counter.get("value");
        if (value == null) throw new IllegalStateException("Unable to allocate Factory Barcode running number.");
        return value.longValue();
    }

    private String sequenceCounterId(int year, String factoryCode) {
        return "factory_barcode:" + year + ":" + factoryCode;
    }

    private String formatBarcode(int year, String factoryCode, long running) {
        String yy = String.format(Locale.ROOT, "%02d", Math.floorMod(year, 100));
        return yy + factoryCode + String.format(Locale.ROOT, "%0" + RUNNING_LENGTH + "d", running);
    }

    private int validateYear(Integer value) {
        int year = value == null ? LocalDate.now().getYear() : value;
        if (year < 2000 || year > 2099) throw new IllegalArgumentException("Year must be between 2000 and 2099.");
        return year;
    }

    private String validateFactoryCode(String value) {
        String code = required(value, "Factory Code is required");
        if (!code.matches("\\d{3}")) throw new IllegalArgumentException("Factory Code must contain exactly 3 digits, for example 002.");
        return code;
    }

    private int validateQuantity(Integer value) {
        int quantity = value == null ? 1 : value;
        if (quantity < 1 || quantity > 1000) throw new IllegalArgumentException("Quantity must be between 1 and 1000.");
        return quantity;
    }

    private String clean(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    private String required(String value, String message) {
        String clean = clean(value);
        if (clean == null) throw new IllegalArgumentException(message);
        return clean;
    }
}
