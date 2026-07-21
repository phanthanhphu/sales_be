package org.bsl.sales.service;

import org.bsl.sales.model.AuditLog;
import org.bsl.sales.repository.AuditLogRepository;
import org.bsl.sales.support.AuditActionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AuditLogService {
    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repository;
    private final MongoTemplate mongoTemplate;

    public AuditLogService(AuditLogRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @Async("auditLogExecutor")
    public void saveAsync(AuditLog log) {
        try {
            if (log == null || !AuditActionResolver.isSupportedAction(log.getAction())) return;
            if (log.getCreatedAt() == null) log.setCreatedAt(LocalDateTime.now());
            repository.save(log);
        } catch (Exception exception) {
            // Audit logging must never break the business action.
            logger.error("Unable to persist audit log: {}", exception.getMessage());
        }
    }

    public Page<AuditLog> search(
            String keyword,
            String username,
            String action,
            String module,
            String status,
            String httpMethod,
            LocalDateTime from,
            LocalDateTime to,
            int page,
            int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(200, Math.max(10, size));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("action").in("ADD", "EDIT", "DELETE"));
        if (StringUtils.hasText(keyword)) {
            String pattern = escapeRegex(keyword.trim());
            filters.add(new Criteria().orOperator(
                    Criteria.where("username").regex(pattern, "i"),
                    Criteria.where("userEmail").regex(pattern, "i"),
                    Criteria.where("description").regex(pattern, "i"),
                    Criteria.where("resourceId").regex(pattern, "i"),
                    Criteria.where("fileName").regex(pattern, "i"),
                    Criteria.where("endpoint").regex(pattern, "i")
            ));
        }
        if (StringUtils.hasText(username)) {
            String pattern = escapeRegex(username.trim());
            filters.add(new Criteria().orOperator(
                    Criteria.where("username").regex(pattern, "i"),
                    Criteria.where("userEmail").regex(pattern, "i")
            ));
        }
        if (StringUtils.hasText(action)) filters.add(Criteria.where("action").is(action.trim().toUpperCase()));
        if (StringUtils.hasText(module)) filters.add(Criteria.where("module").is(module.trim().toUpperCase()));
        if (StringUtils.hasText(status)) filters.add(Criteria.where("status").is(status.trim().toUpperCase()));
        if (StringUtils.hasText(httpMethod)) filters.add(Criteria.where("httpMethod").is(httpMethod.trim().toUpperCase()));
        if (from != null || to != null) {
            Criteria dateCriteria = Criteria.where("createdAt");
            if (from != null) dateCriteria = dateCriteria.gte(from);
            if (to != null) dateCriteria = dateCriteria.lte(to);
            filters.add(dateCriteria);
        }

        Query query = new Query();
        if (!filters.isEmpty()) query.addCriteria(new Criteria().andOperator(filters.toArray(new Criteria[0])));

        long total = mongoTemplate.count(query, AuditLog.class);
        query.with(pageable);
        List<AuditLog> rows = mongoTemplate.find(query, AuditLog.class);
        return new PageImpl<>(rows, pageable, total);
    }

    public Page<AuditLog> getAll(int page, int size) {
        return search(null, null, null, null, null, null, null, null, page, size);
    }

    private String escapeRegex(String value) {
        return value.replace("\\", "\\\\")
                .replace(".", "\\.")
                .replace("*", "\\*")
                .replace("+", "\\+")
                .replace("?", "\\?")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("|", "\\|");
    }
}
