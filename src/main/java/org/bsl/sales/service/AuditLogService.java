package org.bsl.sales.service;

import org.bsl.sales.model.AuditLog;
import org.bsl.sales.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditLogService {

    @Autowired
    private AuditLogRepository repository;

    public AuditLog log(
            String username,
            String action,
            String resourceType,
            String resourceId,
            String description,
            String ipAddress,
            String userAgent
    ) {

        AuditLog log = new AuditLog();

        log.setUsername(username);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setDescription(description);
        log.setIpAddress(ipAddress);
        log.setUserAgent(userAgent);
        log.setCreatedAt(LocalDateTime.now());

        return repository.save(log);
    }

    public Page<AuditLog> getAll(int page, int size) {

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return repository.findAll(pageable);
    }

    public Page<AuditLog> search(String username, int page, int size) {

        Pageable pageable = PageRequest.of(page, size);

        return repository.findAll(
                Example.of(new AuditLog() {{
                               setUsername(username);
                           }},
                        ExampleMatcher.matching()
                                .withMatcher("username",
                                        ExampleMatcher.GenericPropertyMatchers.contains().ignoreCase())
                ),
                pageable
        );
    }

}