package org.bsl.sales.service;

import jakarta.annotation.PostConstruct;
import org.bsl.sales.dto.BuyerRequest;
import org.bsl.sales.exception.MasterDataConflictException;
import org.bsl.sales.exception.MasterDataNotFoundException;
import org.bsl.sales.exception.MasterDataValidationException;
import org.bsl.sales.model.Buyer;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.MatInfo;
import org.bsl.sales.model.Loss;
import org.bsl.sales.model.MaterialShipToMapping;
import org.bsl.sales.model.MprDocument;
import org.bsl.sales.model.ProductColorMaster;
import org.bsl.sales.model.SalesOrder;
import org.bsl.sales.model.User;
import org.bsl.sales.repository.BuyerRepository;
import org.bsl.sales.support.BuyerKeys;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BuyerService {
    private final BuyerRepository repository;
    private final MongoTemplate mongoTemplate;

    public BuyerService(BuyerRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void seedDefaultBuyers() {
        // Legacy bootstrap only. Once Buyer Master exists, it is the source of truth:
        // deleted/inactive Buyers must never be recreated or reactivated on restart.
        // A completely clean installation starts with an empty Buyer Master and
        // the administrator can create the required Buyers from the UI.
        if (repository.count() > 0 || !hasLegacyBusinessData()) return;

        int sequence = 1;
        for (Map.Entry<String, String> entry : BuyerKeys.DEFAULT_BUYERS.entrySet()) {
            Buyer buyer = new Buyer();
            buyer.setBuyerKey(entry.getKey());
            buyer.setBuyerName(entry.getValue());
            buyer.setActive(true);
            buyer.setSequence(sequence++);
            buyer.setCreatedAt(LocalDateTime.now());
            buyer.setUpdatedAt(LocalDateTime.now());
            repository.save(buyer);
        }
    }


    private boolean hasLegacyBusinessData() {
        Query any = new Query().limit(1);
        return mongoTemplate.exists(any, SalesOrder.class)
                || mongoTemplate.exists(any, BomDocument.class)
                || mongoTemplate.exists(any, MprDocument.class)
                || mongoTemplate.exists(any, MatInfo.class)
                || mongoTemplate.exists(any, ProductColorMaster.class)
                || mongoTemplate.exists(any, Loss.class)
                || mongoTemplate.exists(any, MaterialShipToMapping.class);
    }

    public List<Buyer> list(String keyword, Boolean active) {
        String needle = keyword == null ? "" : keyword.trim().toUpperCase(Locale.ROOT);
        return repository.findAll().stream()
                .filter(item -> active == null || item.isActive() == active)
                .filter(item -> needle.isEmpty()
                        || text(item.getBuyerKey()).contains(needle)
                        || text(item.getBuyerName()).contains(needle))
                .sorted(Comparator.comparingInt(Buyer::getSequence)
                        .thenComparing(Buyer::getBuyerName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
    }

    public Page<Buyer> listPage(
            String keyword,
            Boolean active,
            int page,
            int size,
            String sortBy,
            String sortDir
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String requestedSortBy = sortBy == null ? "" : sortBy.trim();
        String safeSortBy = Set.of("buyerKey", "buyerName", "sequence", "active", "createdAt", "updatedAt")
                .contains(requestedSortBy) ? requestedSortBy : "createdAt";
        Sort.Direction direction = sortDir == null || sortDir.isBlank() || "desc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        Sort sort = Sort.by(direction, safeSortBy);
        if ("sequence".equals(safeSortBy)) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, "buyerName"));
        } else if ("createdAt".equals(safeSortBy) && direction == Sort.Direction.DESC) {
            sort = sort.and(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("_id")));
        }
        Pageable pageable = PageRequest.of(safePage, safeSize, sort);

        Query query = new Query();
        if (active != null) {
            query.addCriteria(Criteria.where("active").is(active));
        }

        String needle = keyword == null ? "" : keyword.trim();
        if (!needle.isEmpty()) {
            Pattern pattern = Pattern.compile(Pattern.quote(needle), Pattern.CASE_INSENSITIVE);
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("buyerKey").regex(pattern),
                    Criteria.where("buyerName").regex(pattern)
            ));
        }

        long total = mongoTemplate.count(query, Buyer.class);
        query.with(pageable);
        List<Buyer> content = mongoTemplate.find(query, Buyer.class);
        return new PageImpl<>(content, pageable, total);
    }

    public Buyer get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new MasterDataNotFoundException("Buyer not found"));
    }

    public Buyer getByKey(String buyerKey) {
        String key = BuyerKeys.normalize(buyerKey);
        return repository.findByBuyerKey(key)
                .orElseThrow(() -> new MasterDataNotFoundException("Buyer not found: " + key));
    }

    public Buyer create(BuyerRequest request) {
        String rawKey = request == null || request.buyerKey() == null ? "" : request.buyerKey().trim();
        if (rawKey.isBlank()) throw new MasterDataValidationException("Buyer Key is required");
        String key = BuyerKeys.normalize(rawKey);
        if (repository.existsByBuyerKey(key)) {
            throw new MasterDataConflictException("Buyer Key already exists: " + key);
        }
        Buyer buyer = new Buyer();
        buyer.setBuyerKey(key);
        apply(buyer, request);
        buyer.setCreatedAt(LocalDateTime.now());
        buyer.setUpdatedAt(LocalDateTime.now());
        return repository.save(buyer);
    }

    public Buyer update(String id, BuyerRequest request) {
        Buyer buyer = get(id);
        String rawKey = request == null || request.buyerKey() == null ? "" : request.buyerKey().trim();
        if (rawKey.isBlank()) throw new MasterDataValidationException("Buyer Key is required");
        String newKey = BuyerKeys.normalize(rawKey);
        if (!newKey.equals(buyer.getBuyerKey())) {
            throw new MasterDataValidationException("Buyer Key cannot be changed after creation");
        }
        apply(buyer, request);
        buyer.setUpdatedAt(LocalDateTime.now());
        return repository.save(buyer);
    }

    public void delete(String id) {
        Buyer buyer = get(id);
        if (isReferenced(buyer.getBuyerKey())) {
            throw new MasterDataValidationException(
                    "Buyer is already assigned to users or business data. Remove those references before deleting it."
            );
        }
        repository.delete(buyer);
    }

    private boolean isReferenced(String buyerKey) {
        Query entityQuery = Query.query(Criteria.where("buyerKey").is(buyerKey));
        if (mongoTemplate.exists(entityQuery, SalesOrder.class)) return true;
        if (mongoTemplate.exists(entityQuery, BomDocument.class)) return true;
        if (mongoTemplate.exists(entityQuery, MprDocument.class)) return true;
        if (mongoTemplate.exists(entityQuery, MatInfo.class)) return true;
        if (mongoTemplate.exists(entityQuery, ProductColorMaster.class)) return true;
        if (mongoTemplate.exists(entityQuery, Loss.class)) return true;
        if (mongoTemplate.exists(entityQuery, MaterialShipToMapping.class)) return true;
        Query userAssignment = Query.query(new Criteria().andOperator(
                Criteria.where("buyerKeys").is(buyerKey),
                Criteria.where("role").nin(User.ROLE_ADMIN, "ROLE_ADMIN", "ADMINISTRATOR")
        ));
        return mongoTemplate.exists(userAssignment, User.class);
    }

    private void apply(Buyer buyer, BuyerRequest request) {
        buyer.setBuyerName(required(request.buyerName(), "Buyer Name is required"));
        buyer.setActive(request.active() == null || request.active());
        buyer.setSequence(request.sequence() == null ? 0 : Math.max(0, request.sequence()));
        buyer.setDescription(request.description() == null ? "" : request.description().trim());
    }

    private String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new MasterDataValidationException(message);
        return clean;
    }

    private String text(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
